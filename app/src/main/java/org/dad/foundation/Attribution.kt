package org.dad.foundation

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson

/**
 * Where a new donor came from, captured BEFORE sign-up and carried across a Play install
 * and cold launch so it can be attached at account creation (PRD D-3 deferred attribution,
 * D-4 company code, R-3/R-4 referral attribution). The payload is independent of how it
 * arrived — deep link, Play Install Referrer, or a scanned IRL code.
 */
data class Attribution(
    /** An invite code from a sharing link (person-to-person referral, R-1). */
    val inviteCode: String? = null,
    /** An in-real-life code (printed/QR at an event), distinct from an online invite (R-2). */
    val irlCode: String? = null,
    /** An employer/company code (D-4) — joins the donor to a workforce team. */
    val companyCode: String? = null,
    /** The raw source/campaign tag, kept for the analytics `attribution_captured` event (§9). */
    val source: String? = null,
) {
    /** True when nothing worth persisting was captured. */
    val isEmpty: Boolean get() = inviteCode == null && irlCode == null && companyCode == null && source == null

    companion object {
        /**
         * Codes are short alphanumerics; reject anything that is not, so a crafted deep link
         * or spoofed install-referrer string cannot stuff arbitrary data through the attribution
         * channel into sign-up.
         */
        fun sanitized(raw: String?): String? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.length !in 1..64 || !trimmed.matches(Regex("^[A-Za-z0-9_-]+$"))) return null
            return trimmed
        }

        /** Build a sanitized Attribution from raw inputs in one step. */
        fun of(inviteCode: String? = null, irlCode: String? = null, companyCode: String? = null, source: String? = null) =
            Attribution(sanitized(inviteCode), sanitized(irlCode), sanitized(companyCode), sanitized(source))
    }
}

/**
 * Persists captured attribution across launches. An interface so tests drive an in-memory
 * store and the app uses SharedPreferences (survives a cold launch; not the Keystore, because
 * this is non-secret marketing attribution, not a credential).
 */
interface AttributionStore {
    fun load(): Attribution?
    fun save(attribution: Attribution)
    fun clear()
}

/** SharedPreferences-backed store. */
class PrefsAttributionStore(context: Context, private val key: String = "org.dad.attribution") : AttributionStore {
    private val prefs = context.getSharedPreferences("org.dad.attribution", Context.MODE_PRIVATE)
    private val gson = Gson()
    override fun load(): Attribution? =
        prefs.getString(key, null)?.let { runCatching { gson.fromJson(it, Attribution::class.java) }.getOrNull() }
    override fun save(attribution: Attribution) {
        if (attribution.isEmpty) return
        prefs.edit { putString(key, gson.toJson(attribution)) }
    }
    override fun clear() { prefs.edit { remove(key) } }
}

/** In-memory store for tests. */
class MemoryAttributionStore(private var value: Attribution? = null) : AttributionStore {
    override fun load() = value
    override fun save(attribution: Attribution) { if (!attribution.isEmpty) value = attribution }
    override fun clear() { value = null }
}

/**
 * Captures attribution from an incoming source and MERGES it with whatever was already stored.
 *
 * Precedence is LAST-TOUCH-WINS per PRD R-3 ("last-touch wins"): a newly arriving non-null
 * field OVERWRITES the stored one, because the most recent referral is the one that converted
 * the donor. (This deliberately differs from the iOS first-writer-wins note — R-3 is the
 * canonical rule and the server also counts conversion on last-touch.) A field absent from the
 * incoming payload leaves the stored value intact, so a later company-code touch does not wipe
 * an earlier invite that still applies.
 */
class AttributionCapture(private val store: AttributionStore, private val analytics: AnalyticsEmitter? = null) {
    fun capture(incoming: Attribution): Attribution {
        val existing = store.load() ?: Attribution()
        val merged = Attribution(
            inviteCode = incoming.inviteCode ?: existing.inviteCode,
            irlCode = incoming.irlCode ?: existing.irlCode,
            companyCode = incoming.companyCode ?: existing.companyCode,
            source = incoming.source ?: existing.source,
        )
        if (!merged.isEmpty && merged != existing) {
            store.save(merged)
            analytics?.emit(AnalyticsEvent.AttributionCaptured(merged))
        }
        return merged
    }

    fun current(): Attribution? = store.load()

    /** Called once attribution has been attached to a created account. */
    fun consume() { store.clear() }
}

/**
 * Play Install Referrer seam (D-3 deferred deep linking). The real implementation wraps
 * `com.android.installreferrer.api.InstallReferrerClient`; this interface lets tests replay a
 * referrer string without the Play Store. The referrer string is `utm`-style query params
 * (`invite=ABC&utm_source=...`), parsed into an Attribution.
 */
interface InstallReferrer {
    suspend fun fetch(): String?
}

/** Parses a Play Install Referrer query string into a sanitized Attribution. */
object InstallReferrerParser {
    fun parse(referrer: String?): Attribution {
        val raw = referrer?.takeIf { it.isNotBlank() } ?: return Attribution()
        val params = raw.split("&").mapNotNull { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                runCatching { java.net.URLDecoder.decode(kv[0], "UTF-8") to java.net.URLDecoder.decode(kv[1], "UTF-8") }.getOrNull()
            } else null
        }.toMap()
        return Attribution.of(
            inviteCode = params["invite"] ?: params["ref"],
            irlCode = params["irl"],
            companyCode = params["company"] ?: params["company_code"],
            source = params["utm_source"] ?: params["source"],
        )
    }
}
