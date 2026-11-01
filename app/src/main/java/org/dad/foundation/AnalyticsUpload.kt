package org.dad.foundation

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import retrofit2.http.Body
import retrofit2.http.POST
import java.time.Instant
import java.util.UUID

/** The donor's analytics choice: null until asked. Opt-in, as on the website: nothing is recorded until it is true. */
interface AnalyticsConsent {
    var choice: Boolean?
}

class PrefsAnalyticsConsent(context: Context) : AnalyticsConsent {
    private val prefs = context.getSharedPreferences("org.dad.analytics", Context.MODE_PRIVATE)
    override var choice: Boolean?
        get() = if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null
        set(value) = prefs.edit { if (value == null) remove(KEY) else putBoolean(KEY, value) }

    private companion object { const val KEY = "consent" }
}

class MemoryAnalyticsConsent(override var choice: Boolean? = null) : AnalyticsConsent

/** One event as `ingestAnalytics` takes it; [id] makes a resent batch idempotent. */
data class AnalyticsRecord(val id: String, val journeyId: String, val name: String, val occurredAt: Instant, val properties: Map<String, String>)

fun interface AnalyticsSink {
    /** Delivers one batch of at most [AnalyticsUploader.BATCH] records; a [AccountFailure.InvalidInput] batch is never retried. */
    suspend fun send(records: List<AnalyticsRecord>)
}

/**
 * The app's analytics emitter. Records nothing until the donor allows it, then buffers events (bounded, oldest
 * dropped) and uploads them in the background; a failed upload keeps them for the next event. Declining drops
 * anything still buffered. One journey id per launch groups a visit, like the website's per-tab journey.
 */
class AnalyticsUploader(
    private val consent: AnalyticsConsent,
    private val sink: AnalyticsSink,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
) : AnalyticsEmitter {
    var choice by mutableStateOf(consent.choice)
        private set
    private val journey = UUID.randomUUID().toString()
    private val buffer = ArrayDeque<AnalyticsRecord>()
    private val flushing = Mutex()

    fun choose(allowed: Boolean) {
        consent.choice = allowed
        choice = allowed
        if (allowed) emit(AnalyticsEvent.Plain(PlainEvent.APP_OPENED)) else synchronized(buffer) { buffer.clear() }
    }

    override fun emit(event: AnalyticsEvent) {
        if (consent.choice != true) return
        synchronized(buffer) {
            buffer.addLast(AnalyticsRecord(UUID.randomUUID().toString(), journey, event.name, now(), event.properties))
            while (buffer.size > LIMIT) buffer.removeFirst()
        }
        scope.launch { flush() }
    }

    suspend fun flush() = flushing.withLock {
        while (true) {
            val batch = synchronized(buffer) { buffer.take(BATCH) }
            if (batch.isEmpty()) return@withLock
            try {
                sink.send(batch)
            } catch (_: AccountFailure.InvalidInput) {
                // The server rejects the whole batch for one bad event, so resending it would fail forever.
            } catch (_: Exception) {
                return@withLock
            }
            synchronized(buffer) { buffer.removeAll { sent -> batch.any { it.id == sent.id } } }
        }
    }

    val pending: Int get() = synchronized(buffer) { buffer.size }

    companion object {
        const val BATCH = 100
        private const val LIMIT = 500
    }
}

private interface AnalyticsApi {
    @POST("analytics/events") suspend fun ingest(@Body body: JsonObject): retrofit2.Response<JsonObject>
}

/** `POST /analytics/events`, attributed to the donor when [access] yields a session token and anonymous otherwise. */
class WireAnalytics(
    origin: String,
    private val access: suspend () -> String?,
    private val baseClient: OkHttpClient = defaultNativeClient(),
) : AnalyticsSink {
    private val url: HttpUrl = validatedOrigin(origin)

    override suspend fun send(records: List<AnalyticsRecord>) {
        val events = JsonArray().apply {
            records.forEach { record ->
                add(JsonObject().apply {
                    addProperty("id", record.id)
                    addProperty("journey_id", record.journeyId)
                    addProperty("name", record.name)
                    addProperty("occurred_at", record.occurredAt.toString())
                    if (record.properties.isNotEmpty()) add("properties", JsonObject().apply { record.properties.forEach(::addProperty) })
                })
            }
        }
        val token = runCatching { access() }.getOrNull()
        decoding { nativeRetrofit(url, baseClient, token).create(AnalyticsApi::class.java).ingest(JsonObject().apply { add("events", events) }).requireBody() }
    }
}

/**
 * The §9 events a successful API call stands for, derived exactly as the website derives them from the same calls
 * (DAD-Main `apps/web/lib/analytics.ts` `observeAPI`), so a funnel counts the same thing on every platform.
 * [requestBody] is read only for preferences and [responseBody] only for an event reservation.
 */
internal fun observedEvents(method: String, path: String, requestBody: () -> String?, responseBody: () -> String?): List<PlainEvent> {
    if (method == "GET") {
        return if (Regex("^/(donor/me/)?events/[^/]+$").matches(path)) listOf(PlainEvent.EVENT_VIEWED)
        else if (path == "/donor/partner/report.pdf") listOf(PlainEvent.PARTNER_REPORT_EXPORTED)
        else emptyList()
    }
    if (method != "POST") return emptyList()
    when (path) {
        "/donor/signup" -> return listOf(PlainEvent.SIGNUP_STARTED)
        "/donor/claim" -> return listOf(PlainEvent.SIGNUP_COMPLETED)
        "/donor/me/recurring/checkout" -> return listOf(PlainEvent.GIFT_STARTED)
        "/donor/me/delete" -> return listOf(PlainEvent.ACCOUNT_DELETED)
        "/donor/me/recurring/cancel" -> return listOf(PlainEvent.GIFT_CANCELLED)
        "/donor/me/recurring/amount" -> return listOf(PlainEvent.AMOUNT_CHANGED)
        "/donor/me/team" -> return listOf(PlainEvent.TEAM_JOINED)
        "/donor/me/events/check-in" -> return listOf(PlainEvent.EVENT_CHECKED_IN)
        "/donor/me/preferences" -> {
            val body = runCatching { JsonParser.parseString(requestBody()).asJsonObject }.getOrNull() ?: return emptyList()
            return buildList {
                if (body.has("rankings_opted_out")) add(PlainEvent.RANKINGS_TOGGLED)
                if (runCatching { body.get("volunteer").asBoolean }.getOrDefault(false)) add(PlainEvent.VOLUNTEER_OPTED_IN)
            }
        }
    }
    if (Regex("^/donor/me/events/[^/]+/reserve$").matches(path)) {
        val status = runCatching { JsonParser.parseString(responseBody()).asJsonObject.get("status").asString }.getOrNull()
        return when (status) {
            "WAITLISTED" -> listOf(PlainEvent.EVENT_WAITLISTED)
            "RESERVED" -> listOf(PlainEvent.EVENT_RESERVED)
            else -> emptyList()
        }
    }
    return emptyList()
}

/** Emits [observedEvents] for each successful call through the client it is installed on. */
class AnalyticsObserver(private val emitter: AnalyticsEmitter) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.isSuccessful) {
            observedEvents(
                request.method, request.url.encodedPath,
                requestBody = { request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() } },
                responseBody = { response.peekBody(PEEK_BYTES).string() },
            ).forEach { emitter.emit(AnalyticsEvent.Plain(it)) }
        }
        return response
    }

    private companion object { const val PEEK_BYTES = 4096L }
}
