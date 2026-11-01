package org.dad.foundation

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import java.security.MessageDigest

/**
 * The visitor tokens DAD-Main handed back for this install's latest referral and company-code link visits
 * (contract 2.10). They stand in for the browser's `dad_referral` / `dad_company` cookies: the referral token is
 * sent with the first monthly gift, the company token is bound once the donor is signed in.
 */
data class LinkTokens(val referral: String? = null, val company: String? = null)

interface LinkTokenStore {
    fun load(): LinkTokens
    fun save(tokens: LinkTokens)
    fun clear()
}

/**
 * SharedPreferences, like [PrefsAttributionStore]: a token only steers attribution credit at the next gift and is
 * worth nothing to anyone else, so it is not a Keystore credential.
 */
class PrefsLinkTokenStore(context: Context, private val key: String = "org.dad.link-tokens") : LinkTokenStore {
    private val prefs = context.getSharedPreferences("org.dad.attribution", Context.MODE_PRIVATE)
    private val gson = Gson()
    override fun load(): LinkTokens =
        prefs.getString(key, null)?.let { runCatching { gson.fromJson(it, LinkTokens::class.java) }.getOrNull() } ?: LinkTokens()
    override fun save(tokens: LinkTokens) {
        if (tokens == LinkTokens()) clear() else prefs.edit { putString(key, gson.toJson(tokens)) }
    }
    override fun clear() { prefs.edit { remove(key) } }
}

class MemoryLinkTokenStore(private var value: LinkTokens = LinkTokens()) : LinkTokenStore {
    @Synchronized override fun load() = value
    @Synchronized override fun save(tokens: LinkTokens) { value = tokens }
    @Synchronized override fun clear() { value = LinkTokens() }
}

/** [token] is the one to keep, or null when the visit started or kept no referral window (discard any kept one). */
data class ReferralCapture(val token: String?, val referrerName: String?)

interface LinkAttributionService {
    suspend fun captureReferral(code: String, inPerson: Boolean, token: String?): ReferralCapture
    /** The token to keep, or null when nothing was captured. */
    suspend fun captureCompany(code: String, token: String?): String?
    /** Whether the token named an unexpired company-code visit, now bound to the signed-in donor. */
    suspend fun bindCompanyTouch(token: String): Boolean
}

private interface LinkAttributionApi {
    @POST("donor/referrals/{code}") suspend fun captureReferral(@Path("code") code: String, @Body body: JsonObject): Response<JsonElement>
    @POST("donor/company/{code}") suspend fun captureCompany(@Path("code") code: String, @Body body: JsonObject): Response<JsonElement>
    @POST("donor/me/company-touch") suspend fun bindCompanyTouch(@Body body: JsonObject): Response<JsonElement>
}

/** The captures are anonymous (the visit usually precedes sign-in); the binding goes through the donor session. */
class WireLinkAttribution(
    origin: String,
    private val product: ProductClient,
    private val baseClient: OkHttpClient = defaultNativeClient(),
) : LinkAttributionService {
    private val url: HttpUrl = validatedOrigin(origin)
    private fun anonymous() = nativeRetrofit(url, baseClient).create(LinkAttributionApi::class.java)

    override suspend fun captureReferral(code: String, inPerson: Boolean, token: String?): ReferralCapture = decoding {
        val body = JsonObject().apply {
            addProperty("kind", if (inPerson) "IRL" else "LINK")
            token?.let { addProperty("token", it) }
        }
        val response = anonymous().captureReferral(code, body)
        if (response.code() == 404) return@decoding ReferralCapture(token, null)
        val json = response.requireBody().asObjectOrInvalid()
        ReferralCapture(if (json.bool("captured")) json.optStr("token") else null, json.optStr("public_name"))
    }

    override suspend fun captureCompany(code: String, token: String?): String? = decoding {
        val body = JsonObject().apply { token?.let { addProperty("token", it) } }
        val response = anonymous().captureCompany(code, body)
        if (response.code() == 404) return@decoding token
        val json = response.requireBody().asObjectOrInvalid()
        if (json.bool("captured")) json.optStr("token") else null
    }

    override suspend fun bindCompanyTouch(token: String): Boolean = product.perform { retrofit ->
        retrofit.create(LinkAttributionApi::class.java).bindCompanyTouch(JsonObject().apply { addProperty("token", token) })
            .requireBody().asObjectOrInvalid().bool("bound")
    }
}

/**
 * Keeps the tokens a link visit earns and spends them, as the website's cookies would be: the latest valid link
 * wins, a capture that keeps nothing discards the kept token, and a company token is bound once after sign-in.
 * Attribution is best effort, so a failed call leaves the kept tokens as they were.
 */
class LinkAttribution(
    private val service: LinkAttributionService,
    private val store: LinkTokenStore,
    private val analytics: AnalyticsEmitter? = null,
) {
    private val lock = Mutex()

    /** Records a referral visit and returns the referrer's public name, if the server named one. */
    suspend fun captureReferral(code: String, inPerson: Boolean): String? {
        val result = attempt { service.captureReferral(code, inPerson, store.load().referral) } ?: return null
        update { it.copy(referral = result.token) }
        if (result.token != null) analytics?.emit(AnalyticsEvent.AttributionCaptured(if (inPerson) AttributionSource.IRL else AttributionSource.LINK))
        return result.referrerName
    }

    suspend fun captureCompany(code: String) {
        val kept = attempt { Kept(service.captureCompany(code, store.load().company)) } ?: return
        val token = kept.token
        update { it.copy(company = token) }
        if (token != null) analytics?.emit(AnalyticsEvent.AttributionCaptured(AttributionSource.COMPANY))
    }

    /**
     * Binds a kept company token to the signed-in donor. The token is spent once the server has answered either
     * way (bound, or naming nothing); a transient failure keeps it for the next sign-in.
     */
    suspend fun bindCompany() {
        val token = store.load().company ?: return
        try { service.bindCompanyTouch(token) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: AccountFailure.InvalidInput) {}
        catch (_: Exception) { return }
        update { if (it.company == token) it.copy(company = null) else it }
    }

    val referralToken: String? get() = store.load().referral

    /** A call's answer, so an answer of "nothing" is told apart from a failed call. */
    private class Kept(val token: String?)

    private suspend fun <T> attempt(call: suspend () -> T): T? =
        try { call() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }

    private suspend fun update(change: (LinkTokens) -> LinkTokens) = lock.withLock { store.save(change(store.load())) }
}

/**
 * A short, stable stand-in for a referral token in an idempotency fingerprint, so a checkout retried with a different
 * referral is a different command (the server refuses a key reused with a different referral).
 */
internal fun referralFingerprint(token: String?): String =
    token?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).take(8).joinToString("") { b -> "%02x".format(b) } } ?: "none"
