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
 * Email signup answers 200 `{"sent":true}` (an address containing `bad` is a 422, `throttle` a 429); a sign-in link
 * request answers 202 for any address (`throttle` a 429); a claim token
 * starting with `good` signs in (anything else is a 401). Password sign-in accepts alex@example.org with
 * `correct-horse-battery` (others 401, `throttle` 429). Set-password: a wrong or missing current password
 * is a 422 `CURRENT_PASSWORD_INCORRECT` (the session stays), a new password under 12 characters a plain 422.
 * Profile/preferences/logout/refresh behave like a live account; account deletion succeeds (200)
 * unless `stub_delete_blocked` (409), after which the account is gone (profile -> 401).
 * Contact details, email change, sessions, the employer link and the Wallet pass are scripted by [StubAccount].
 * The partner sign-in link and portal are scripted by [StubPartner]; `stub_partner_signed_in` starts with a stored
 * partner session.
 * Events (`/donor/me/events`) are scripted by [StubEvents] (`stub_events_invited`, `stub_events_fail_first`, `stub_events_not_eligible`).
 * `stub_signed_in` starts with a stored session. The donor-home reads follow the scenario extras
 * (`stub_dashboard_empty`, `stub_dashboard_fail_first`, `stub_dashboard_unranked`); see [StubDonorHome].
 */
object DebugBackend {
    /** Every request path the stub transport has served since [resetRequestLog], for tests. */
    val requestLog: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    fun resetRequestLog() = requestLog.clear()

    const val ORIGIN = "https://stub.invalid"
    /** The public (link) origin the stub accepts claim links for, so a pasted sign-in link routes in UI tests. */
    const val PUBLIC_ORIGIN = ORIGIN

    fun stub(context: Context, scope: CoroutineScope, options: LaunchOptions): AppServices? {
        if (!options.stubBackend) return null
        val interceptor = StubInterceptor(options.stubDeleteBlocked, options.stubDashboard, options.stubGift, options.stubEvents, options.stubEngagement)
        val http = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val store = InMemoryCredentialStore(
            if (options.stubSignedIn) Credentials("stub-access-0", "stub-refresh-0-xxxxxxxxxxxxxxxxxxxxxxxxxxxx", Long.MAX_VALUE) else null,
        )
        val session = AccountSession(WireAPI(ORIGIN, http), store, scope)
        val attribution = PrefsAttributionStore(context.applicationContext)
        val credentialAuth = WireCredentialAuth(ORIGIN, "Stub device", http)
        return AppServices(
            session = session,
            signIn = WireAuth(ORIGIN, "Stub device", http),
            settings = WireSettings(ProductClient(ORIGIN, session, http), onLocalWipe = { attribution.clear() }),
            signup = credentialAuth,
            signInLink = credentialAuth,
            passwordSignIn = credentialAuth,
            passwordSettings = WirePasswordSettings(ProductClient(ORIGIN, session, http)),
            dashboard = WireDashboard(ProductClient(ORIGIN, session, http)),
            giving = GivingKit(WireGiving(ProductClient(ORIGIN, session, http)), stubComplete = interceptor::completeHandoff, pollDelayMs = 200),
            events = WireEvents(ProductClient(ORIGIN, session, http)),
            engagement = WireEngagement(ProductClient(ORIGIN, session, http)),
            management = WireAccountManagement(ProductClient(ORIGIN, session, http)),
            wallet = WireWalletPass(ProductClient(ORIGIN, session, http)),
            partner = PartnerSession(InMemoryCredentialStore(
                if (options.stubPartnerSignedIn) Credentials("stub-partner-0", "", Long.MAX_VALUE) else null,
            )).let { PartnerKit(it, WirePartnerAuth(ORIGIN, it, http), WirePartner(ORIGIN, it, http)) },
        )
    }
}

class InMemoryCredentialStore(private var value: Credentials? = null) : CredentialStore {
    @Synchronized override fun load() = value
    @Synchronized override fun save(value: Credentials) { this.value = value }
    @Synchronized override fun clear() { value = null }
}

private class StubInterceptor(private val deletionBlocked: Boolean, scenario: StubDashboard, gift: StubGift, eventsScenario: StubEventsScenario, engagementScenario: StubEngagementScenario) : Interceptor {
    private val home = StubDonorHome(scenario)
    private val giving = StubGiving(gift)
    private val events = StubEvents(eventsScenario)
    private val engagement = StubEngagement(engagementScenario)
    private val account = StubAccount()
    private val partner = StubPartner()
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

    /** Plays the Stripe webhook after the stubbed hosted page "finishes": a gift starts, or a card is applied. */
    @Synchronized fun completeHandoff(purpose: HostedHandoff.Purpose) = giving.complete(purpose)

    @Synchronized private fun session(): String {
        generation++
        return """{"signed_in":true,"access_token":"stub-access-$generation","expires_in":900,"refresh_token":"stub-refresh-$generation-xxxxxxxxxxxxxxxxxxxxxxxxxxxx","refresh_expires_in":5184000}"""
    }

    @Synchronized private fun profile(): String {
        val flags = prefs.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
        return """{"email":"alex@example.org","public_name":"Alex","account_role":"DONOR","zip":"${account.zip}","city":"${account.city}",""" +
            """"timezone":"America/Chicago","phone":"${account.phone}","volunteer_interests":[],"version":$version,"consent_version":"stub-v1",$flags}"""
    }

    @Synchronized override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        DebugBackend.requestLog.add("${request.method} ${request.url.encodedPath}")
        val payload = request.body?.let { Buffer().also { buffer -> it.writeTo(buffer) }.readUtf8() }.orEmpty()
        val authorized = request.header("Authorization")?.startsWith("Bearer stub-access-") == true
        fun reply(code: Int, json: String = "{}") = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("stub").body(json.toResponseBody("application/json".toMediaType())).build()
        if (partner.handles(request.url.encodedPath)) {
            val answer = partner.answer(request.method, request.url.encodedPath, payload, request.header("Authorization"))
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(answer.code).message("stub")
                .body(answer.body.toResponseBody(answer.type.toMediaType())).build()
        }
        return when (request.method to request.url.encodedPath) {
            "POST" to "/donor/phone/sign-in/options" -> reply(200, """{"sent":true}""")
            "POST" to "/donor/phone/sign-in" -> when {
                payload.contains("\"123456\"") -> reply(200, session())
                payload.contains("\"000000\"") -> reply(429)
                else -> reply(401)
            }
            "POST" to "/donor/signup" -> when {
                payload.contains("throttle") -> reply(429)
                payload.contains("bad") -> reply(422)
                else -> reply(200, """{"sent":true}""")
            }
            "POST" to "/donor/login" -> when {
                payload.contains("throttle") -> reply(429)
                !payload.contains("@") || payload.contains("\"next\"") -> reply(422)
                else -> reply(202, """{"message":"If this email matches a donor account, a sign-in link will arrive shortly."}""")
            }
            "POST" to "/donor/claim" -> if (payload.contains("\"token\":\"good")) reply(200, session()) else reply(401)
            "POST" to "/donor/password/sign-in" -> when {
                payload.contains("throttle") -> reply(429)
                payload.contains("alex@example.org") && payload.contains("correct-horse-battery") -> reply(200, session())
                else -> reply(401, """{"detail":"The email or password is incorrect"}""")
            }
            "POST" to "/donor/me/password" -> when {
                !authorized || deleted -> reply(401)
                !payload.contains("\"current_password\":\"correct-horse-battery\"") ->
                    reply(422, """{"detail":"The current password is incorrect.","code":"CURRENT_PASSWORD_INCORRECT"}""")
                Regex("\"password\":\"([^\"]*)\"").find(payload)?.groupValues?.get(1).orEmpty().length < 12 -> reply(422, """{"detail":"Invalid password."}""")
                else -> reply(200, """{"saved":true}""")
            }
            "POST" to "/donor/refresh" -> if (deleted) reply(401) else reply(200, session())
            "GET" to "/donor/me/profile" -> if (authorized && !deleted) reply(200, profile()) else reply(401)
            "POST" to "/donor/me/preferences" -> when {
                !authorized || deleted -> reply(401)
                engagement.failsPreferenceSave -> reply(503)
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
                deletionBlocked || giving.blocksDeletion -> reply(409)
                else -> { deleted = true; reply(200, """{"deleted":true}""") }
            }
            "POST" to "/donor/logout" -> reply(200, """{"signed_out":true}""")
            else -> if (account.handles(request.method, request.url.encodedPath)) {
                if (!authorized || deleted) reply(401)
                else account.answer(request.method, request.url.encodedPath, payload, ::profile).let { (code, json) -> reply(code, json) }
            } else if (request.url.encodedPath.startsWith("/donor/me/recurring") || request.url.encodedPath.startsWith("/donor/me/payment-method")) {
                if (!authorized || deleted) reply(401)
                else giving.answer(request.method, request.url.encodedPath, payload, request.header("Idempotency-Key")).let { (code, json) -> reply(code, json) }
            } else if (engagement.handles(request.url.encodedPath)) {
                if (!authorized || deleted) reply(401)
                else engagement.answer(request.method, request.url.encodedPath, payload).let { (code, json) -> reply(code, json) }
            } else if (request.url.encodedPath.startsWith("/donor/me/events")) {
                if (!authorized || deleted) reply(401)
                else events.answer(request.method, request.url.encodedPath, payload).let { (code, json) -> reply(code, json) }
            } else if (request.method == "GET" && request.url.encodedPath.startsWith("/donor/me/")) {
                if (!authorized || deleted) reply(401)
                else home.answer(request.url.encodedPath, request.url.queryParameter("offset")?.toIntOrNull()).let { (code, json) -> reply(code, json) }
            } else reply(404)
        }
    }
}
