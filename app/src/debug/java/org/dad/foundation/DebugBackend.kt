package org.dad.foundation

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * DEBUG-only scripted backend for the Compose instrumentation tests (`stub_backend` launch extra).
 * It is NOT a preview double: the app runs the REAL [WireAuth], [WireAPI], [WireSettings] and
 * [AccountSession] on top of it, so a UI test drives the code a release build uses with only the
 * network replaced. This source set is compiled into debug builds only.
 *
 * Script: phone sign-in accepts code `123456`; `000000` is throttled (429); anything else is a 401.
 * Profile/preferences/logout/refresh behave like a live account; account deletion succeeds (200)
 * unless `stub_delete_blocked` (409), after which the account is gone (profile -> 401).
 */
object DebugBackend {
    const val ORIGIN = "https://stub.invalid"

    fun stub(context: Context, scope: CoroutineScope, options: LaunchOptions): AppServices? {
        if (!options.stubBackend) return null
        val http = OkHttpClient.Builder().addInterceptor(StubInterceptor(options.stubDeleteBlocked)).build()
        val store = InMemoryCredentialStore()
        val session = AccountSession(WireAPI(ORIGIN, http), store, scope)
        val attribution = PrefsAttributionStore(context.applicationContext)
        return AppServices(
            session = session,
            signIn = WireAuth(ORIGIN, "Stub device", http),
            settings = WireSettings(ProductClient(ORIGIN, session, http), onLocalWipe = { attribution.clear() }),
        )
    }
}

class InMemoryCredentialStore(private var value: Credentials? = null) : CredentialStore {
    @Synchronized override fun load() = value
    @Synchronized override fun save(value: Credentials) { this.value = value }
    @Synchronized override fun clear() { value = null }
}

private class StubInterceptor(private val deletionBlocked: Boolean) : Interceptor {
    private var generation = 0
    private var deleted = false
    private var version = 3
    private val prefs = linkedMapOf(
        "referral_emails" to true, "marketing_emails" to false, "push_referral_converted" to true,
        "push_streak_at_risk" to true, "push_streak_milestone" to true, "push_streak_lapsed" to true,
        "push_badge_earned" to true, "push_event_nearby" to false, "push_event_reminder" to true,
        "push_event_promoted" to false, "push_gift_processed" to true, "rankings_opted_out" to false,
        "network_visible" to true, "volunteer" to true,
    )

    @Synchronized private fun session(): String {
        generation++
        return """{"signed_in":true,"access_token":"stub-access-$generation","expires_in":900,"refresh_token":"stub-refresh-$generation-xxxxxxxxxxxxxxxxxxxxxxxxxxxx","refresh_expires_in":5184000}"""
    }

    @Synchronized private fun profile(): String {
        val flags = prefs.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
        return """{"email":"alex@example.org","public_name":"Alex","account_role":"DONOR","zip":"78701","city":"Austin",""" +
            """"timezone":"America/Chicago","phone":"","volunteer_interests":[],"version":$version,"consent_version":"stub-v1",$flags}"""
    }

    @Synchronized override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val payload = request.body?.let { Buffer().also { buffer -> it.writeTo(buffer) }.readUtf8() }.orEmpty()
        val authorized = request.header("Authorization")?.startsWith("Bearer stub-access-") == true
        fun reply(code: Int, json: String = "{}") = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("stub").body(json.toResponseBody("application/json".toMediaType())).build()
        return when (request.method to request.url.encodedPath) {
            "POST" to "/donor/phone/sign-in/options" -> reply(200, """{"sent":true}""")
            "POST" to "/donor/phone/sign-in" -> when {
                payload.contains("\"123456\"") -> reply(200, session())
                payload.contains("\"000000\"") -> reply(429)
                else -> reply(401)
            }
            "POST" to "/donor/refresh" -> if (deleted) reply(401) else reply(200, session())
            "GET" to "/donor/me/profile" -> if (authorized && !deleted) reply(200, profile()) else reply(401)
            "POST" to "/donor/me/preferences" -> when {
                !authorized || deleted -> reply(401)
                else -> {
                    prefs.keys.forEach { key ->
                        Regex("\"$key\"\\s*:\\s*(true|false)").find(payload)?.let { prefs[key] = it.groupValues[1] == "true" }
                    }
                    version++
                    reply(200, profile())
                }
            }
            "POST" to "/donor/me/delete" -> when {
                !authorized || deleted -> reply(401)
                deletionBlocked -> reply(409)
                else -> { deleted = true; reply(200, """{"deleted":true}""") }
            }
            "POST" to "/donor/logout" -> reply(200, """{"signed_out":true}""")
            else -> reply(404)
        }
    }
}
