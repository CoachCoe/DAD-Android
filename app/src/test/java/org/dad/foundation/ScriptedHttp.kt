package org.dad.foundation

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList

/** One request the app made, as the scripted transport saw it. */
data class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String)

/**
 * A scripted in-process transport: the real Retrofit/OkHttp stack runs, only the network is replaced.
 * [handler] returns an HTTP status and JSON body for each request.
 */
class ScriptedHttp(private val handler: (Recorded) -> Pair<Int, String>) : Interceptor {
    val requests = CopyOnWriteArrayList<Recorded>()
    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()
    fun count(path: String) = requests.count { it.path == path }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request: Request = chain.request()
        val body = request.body?.let { Buffer().also { buffer -> it.writeTo(buffer) }.readUtf8() }.orEmpty()
        val recorded = Recorded(request.method, request.url.encodedPath, request.headers.toMultimap().mapValues { it.value.first() }, body)
        requests += recorded
        val (code, json) = handler(recorded)
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("scripted")
            .body(json.toResponseBody("application/json".toMediaType())).build()
    }
}

const val TEST_ORIGIN = "https://api.test"

fun nativeSessionJson(access: String = "access-1", refresh: String = "refresh-1", expiresIn: Int = 900) =
    """{"access_token":"$access","expires_in":$expiresIn,"refresh_token":"$refresh","refresh_expires_in":5184000}"""

fun profileJson(version: Int = 3, vararg overrides: Pair<String, Boolean>): String {
    val flags = linkedMapOf(
        "referral_emails" to true, "marketing_emails" to false, "push_referral_converted" to true,
        "push_streak_at_risk" to true, "push_streak_milestone" to true, "push_streak_lapsed" to true,
        "push_badge_earned" to true, "push_event_nearby" to false, "push_event_reminder" to true,
        "push_event_promoted" to false, "push_gift_processed" to true, "rankings_opted_out" to false,
        "network_visible" to true, "volunteer" to true,
    ).apply { putAll(overrides) }
    val rendered = flags.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
    return """{"email":"alex@example.org","public_name":"Alex","account_role":"DONOR","zip":"78701","city":"Austin","timezone":"America/Chicago","phone":null,"volunteer_interests":[],"version":$version,"consent_version":"v1",$rendered}"""
}

class RecordingStore(var value: Credentials? = null) : CredentialStore {
    var saves = 0
    var clears = 0
    override fun load() = value
    override fun save(value: Credentials) { this.value = value; saves++ }
    override fun clear() { value = null; clears++ }
}

fun validCredentials(access: String = "access-0", refresh: String = "refresh-0") = Credentials(access, refresh, Long.MAX_VALUE)
