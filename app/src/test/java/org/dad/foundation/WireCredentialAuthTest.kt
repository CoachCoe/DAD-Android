package org.dad.foundation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real signup / password conformers over a scripted transport (Retrofit + OkHttp run for real). */
class WireCredentialAuthTest {
    private fun auth(status: Int, json: String = "{}", device: String? = "Pixel"): Pair<WireCredentialAuth, ScriptedHttp> {
        val http = ScriptedHttp { status to json }
        return WireCredentialAuth(TEST_ORIGIN, device, http.client()) to http
    }
    private suspend fun failure(block: suspend () -> Any?): Throwable? = runCatching { block() }.exceptionOrNull()
    private val form = SignupForm("Alex Doe", "alex@example.org", "78701", "Austin")

    // MARK: sign-in link

    @Test fun signInLinkPostsOnlyTheEmailWithoutCredentials() = runTest {
        val (wire, http) = auth(202, """{"message":"If this email matches a donor account, a sign-in link will arrive shortly."}""")
        wire.requestSignInLink("alex@example.org")
        val sent = http.requests.single()
        assertEquals("/donor/login", sent.path)
        assertEquals("POST", sent.method)
        assertEquals("""{"email":"alex@example.org"}""", sent.body)
        assertEquals("native", sent.headers["x-dad-client"])
        assertNull(sent.headers["authorization"])
    }

    @Test fun signInLinkStatusMapping() = runTest {
        mapOf(422 to AccountFailure.InvalidInput, 429 to AccountFailure.RateLimited, 503 to AccountFailure.Unavailable, 403 to AccountFailure.Unavailable)
            .forEach { (status, expected) -> assertEquals("$status", expected, failure { auth(status).first.requestSignInLink("alex@example.org") }) }
    }

    // MARK: signup

    @Test fun signupSendsTheFormWithNativeHeadersAndNoCredentials() = runTest {
        val (wire, http) = auth(200, """{"sent":true}""")
        wire.requestSignup(form)
        val sent = http.requests.single()
        assertEquals("/donor/signup", sent.path)
        assertEquals("POST", sent.method)
        listOf("\"Alex Doe\"", "\"alex@example.org\"", "\"78701\"", "\"Austin\"").forEach { assertTrue(it, sent.body.contains(it)) }
        assertEquals("native", sent.headers["x-dad-client"])
        assertNull(sent.headers["authorization"])
        assertNull(sent.headers["origin"])
        assertNull(sent.headers["cookie"])
    }

    @Test fun signupAcceptsAnyTwoXxAndIgnoresTheBody() = runTest {
        auth(200, """{"sent":true}""").first.requestSignup(form)
        auth(202, """{"message":"Check your email"}""").first.requestSignup(form)
        auth(200, "not json {").first.requestSignup(form)
        auth(200, "").first.requestSignup(form)
    }

    @Test fun signupMapsStatuses() = runTest {
        assertEquals(AccountFailure.InvalidInput, failure { auth(422).first.requestSignup(form) })
        assertEquals(AccountFailure.RateLimited, failure { auth(429).first.requestSignup(form) })
        assertEquals(AccountFailure.Unavailable, failure { auth(503, """{"detail":"x","code":"EMAIL_UNCONFIGURED"}""").first.requestSignup(form) })
        assertEquals(AccountFailure.Unavailable, failure { auth(500).first.requestSignup(form) })
        assertEquals(AccountFailure.Unavailable, failure { auth(403).first.requestSignup(form) })
    }

    @Test fun signupIngressRateLimitWithAnEmptyBodyAndNoRetryAfterIsRateLimited() = runTest {
        assertEquals(AccountFailure.RateLimited, failure { auth(429, "").first.requestSignup(form) })
    }

    // MARK: password sign-in

    @Test fun passwordSignInSendsDeviceNameAndAndroidPlatformAndReturnsCredentials() = runTest {
        val (wire, http) = auth(200, """{"signed_in":true,"access_token":"a","expires_in":900,"refresh_token":"r","refresh_expires_in":5184000}""")
        val credentials = wire.signIn("alex@example.org", "correct-horse-battery")
        assertEquals("a", credentials.access)
        assertEquals("r", credentials.refresh)
        assertTrue(credentials.expiresAtMillis > System.currentTimeMillis())
        val sent = http.requests.single()
        assertEquals("/donor/password/sign-in", sent.path)
        assertTrue(sent.body.contains("\"device_name\":\"Pixel\""))
        assertTrue(sent.body.contains("\"platform\":\"ANDROID\""))
        assertTrue(sent.body.contains("\"email\":\"alex@example.org\""))
        assertNull(sent.headers["authorization"])
        assertEquals("native", sent.headers["x-dad-client"])
    }

    @Test fun passwordSignInTruncatesTheDeviceNameAndOmitsItWhenNull() = runTest {
        val ok = nativeSessionJson()
        val (long, http) = auth(200, ok, device = "x".repeat(100))
        long.signIn("a@b.co", "pw")
        assertTrue(http.requests.single().body.contains("\"device_name\":\"${"x".repeat(64)}\""))
        val (none, http2) = auth(200, ok, device = null)
        none.signIn("a@b.co", "pw")
        assertFalse(http2.requests.single().body.contains("device_name"))
    }

    @Test fun passwordSignInMapsStatuses() = runTest {
        assertEquals(AccountFailure.SignedOut, failure { auth(401, """{"detail":"The email or password is incorrect"}""").first.signIn("a@b.co", "pw") })
        assertEquals(AccountFailure.InvalidInput, failure { auth(422).first.signIn("a@b.co", "pw") })
        assertEquals(AccountFailure.RateLimited, failure { auth(429).first.signIn("a@b.co", "pw") })
        assertEquals(AccountFailure.Unavailable, failure { auth(503).first.signIn("a@b.co", "pw") })
        assertEquals(AccountFailure.Unavailable, failure { auth(409).first.signIn("a@b.co", "pw") })
    }

    @Test fun passwordSignInNeverTreatsABodyWithoutTokensAsASession() = runTest {
        assertEquals(AccountFailure.InvalidResponse, failure { auth(200, """{"signed_in":true}""").first.signIn("a@b.co", "pw") })
        assertEquals(AccountFailure.InvalidResponse, failure { auth(200, "not json {").first.signIn("a@b.co", "pw") })
    }

    @Test fun anErrorNeverCarriesThePassword() = runTest {
        val error = failure { auth(401).first.signIn("a@b.co", "s3cret-pass-phrase") }
        assertFalse(error.toString().contains("s3cret"))
        assertFalse(error?.message.orEmpty().contains("s3cret"))
    }

    @Test fun oversizedResponseIsRejected() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("x".repeat(1_048_577).toResponseBody()).build()
        }.build()
        assertEquals(AccountFailure.InvalidResponse, failure { WireCredentialAuth(TEST_ORIGIN, null, http).signIn("a@b.co", "pw") })
    }

    @Test fun insecureOrNonOriginConfigurationIsRejected() {
        listOf("http://api.test", "https://user:pass@api.test", "https://api.test/path").forEach {
            assertTrue(it, runCatching { WireCredentialAuth(it) }.isFailure)
        }
    }

    @Test fun cancellingTheCallerRethrowsCancellation() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(nativeSessionJson().toResponseBody()).build()
        }.build()
        val call = async(Dispatchers.IO) { WireCredentialAuth(TEST_ORIGIN, null, http).signIn("a@b.co", "pw") }
        withTimeout(10_000) { while (started.count > 0) kotlinx.coroutines.delay(10) }
        call.cancelAndJoin()
        release.countDown()
        assertTrue(call.isCancelled)
    }

    @Test fun signInThroughTheSessionInstallsCredentialsOnce() = runTest {
        val (wire, _) = auth(200, nativeSessionJson("acc", "ref"))
        val store = RecordingStore()
        val session = AccountSession(WireAPI(TEST_ORIGIN, ScriptedHttp { 401 to "{}" }.client()), store, backgroundScope)
        session.establish { wire.signIn("a@b.co", "pw") }
        assertEquals("acc", store.value?.access)
        assertEquals(1, store.saves)
    }

    // MARK: set / change password

    private class Rig(scope: kotlinx.coroutines.CoroutineScope, creds: Credentials? = validCredentials(), handler: (Recorded) -> Pair<Int, String>) {
        val http = ScriptedHttp(handler)
        val store = RecordingStore(creds)
        val client = http.client()
        val session = AccountSession(WireAPI(TEST_ORIGIN, client), store, scope)
        val passwords = WirePasswordSettings(ProductClient(TEST_ORIGIN, session, client))
    }

    @Test fun setPasswordSendsTheBearerAndBothPasswords() = runTest {
        val rig = Rig(backgroundScope) { 200 to """{"saved":true}""" }
        rig.passwords.setPassword("old-password-123", "brand-new-password")
        val sent = rig.http.requests.single()
        assertEquals("/donor/me/password", sent.path)
        assertEquals("Bearer access-0", sent.headers["authorization"])
        assertTrue(sent.body.contains("\"current_password\":\"old-password-123\""))
        assertTrue(sent.body.contains("\"password\":\"brand-new-password\""))
    }

    @Test fun setPasswordWithoutCurrentOmitsTheField() = runTest {
        val rig = Rig(backgroundScope) { 200 to """{"saved":true}""" }
        rig.passwords.setPassword(null, "brand-new-password")
        assertFalse(rig.http.requests.single().body.contains("current_password"))
    }

    @Test fun wrongCurrentPasswordIs422WithCodeAndKeepsTheSession() = runTest {
        val rig = Rig(backgroundScope) { 422 to """{"detail":"x","code":"CURRENT_PASSWORD_INCORRECT"}""" }
        assertEquals(AccountFailure.PasswordIncorrect, runCatching { rig.passwords.setPassword("nope", "brand-new-password") }.exceptionOrNull())
        assertEquals(1, rig.http.requests.size) // never refreshed or retried
        assertEquals(0, rig.store.clears)
        assertEquals("access-0", rig.store.value?.access)
        assertTrue(rig.session.isSignedIn())
    }

    @Test fun otherOrUnknownProblemCodesOn422AreInvalidInputNotWrongPassword() = runTest {
        for (body in listOf("""{"detail":"x"}""", """{"detail":"x","code":"SOMETHING_NEW"}""", "not json", "", """{"code":42}""")) {
            val rig = Rig(backgroundScope) { 422 to body }
            assertEquals(body, AccountFailure.InvalidInput, runCatching { rig.passwords.setPassword("old", "brand-new-password") }.exceptionOrNull())
            assertEquals(0, rig.store.clears)
        }
    }

    @Test fun anOlderServers401WrongPasswordSentenceIsAlsoWrongPasswordAndKeepsTheSession() = runTest {
        val rig = Rig(backgroundScope) { 401 to """{"detail":"The email or password is incorrect"}""" }
        assertEquals(AccountFailure.PasswordIncorrect, runCatching { rig.passwords.setPassword("nope", "brand-new-password") }.exceptionOrNull())
        assertEquals(1, rig.http.requests.size)
        assertEquals(0, rig.store.clears)
    }

    @Test fun aRealUnauthorizedRefreshesOnceThenRetriesWithTheNewBearer() = runTest {
        var calls = 0
        val rig = Rig(backgroundScope) { r ->
            if (r.path == "/donor/refresh") 200 to nativeSessionJson("access-new", "refresh-new")
            else if (++calls == 1) 401 to """{"detail":"Unauthorized"}""" else 200 to """{"saved":true}"""
        }
        rig.passwords.setPassword(null, "brand-new-password")
        assertEquals(1, rig.http.count("/donor/refresh"))
        assertEquals(listOf("Bearer access-0", "Bearer access-new"), rig.http.requests.filter { it.path == "/donor/me/password" }.map { it.headers["authorization"] })
    }

    @Test fun aSecondRealUnauthorizedEndsTheSession() = runTest {
        val rig = Rig(backgroundScope) { r -> if (r.path == "/donor/refresh") 200 to nativeSessionJson("n", "n-ref") else 401 to """{"detail":"Unauthorized"}""" }
        assertEquals(AccountFailure.SignedOut, runCatching { rig.passwords.setPassword(null, "brand-new-password") }.exceptionOrNull())
        assertEquals(1, rig.store.clears)
        assertFalse(rig.session.isSignedIn())
    }

    @Test fun setPasswordMapsTheRemainingStatuses() = runTest {
        assertEquals(AccountFailure.RateLimited, runCatching { Rig(backgroundScope) { 429 to "{}" }.passwords.setPassword(null, "brand-new-password") }.exceptionOrNull())
        assertEquals(AccountFailure.Unavailable, runCatching { Rig(backgroundScope) { 503 to "{}" }.passwords.setPassword(null, "brand-new-password") }.exceptionOrNull())
        assertEquals(AccountFailure.Unavailable, runCatching { Rig(backgroundScope) { 409 to "{}" }.passwords.setPassword(null, "brand-new-password") }.exceptionOrNull())
    }

    @Test fun malformedSuccessBodyIsNotACrash() = runTest {
        val rig = Rig(backgroundScope) { 200 to "not json {" }
        val error = runCatching { rig.passwords.setPassword(null, "brand-new-password") }.exceptionOrNull()
        assertTrue(error.toString(), error == null || error == AccountFailure.InvalidResponse)
    }

    @Test fun callerCancellationIsRethrownAndKeepsTheSession() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"saved":true}""".toResponseBody()).build()
        }.build()
        val store = RecordingStore(validCredentials())
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val session = AccountSession(WireAPI(TEST_ORIGIN, http), store, scope)
        val call = async(Dispatchers.IO) { WirePasswordSettings(ProductClient(TEST_ORIGIN, session, http)).setPassword(null, "brand-new-password") }
        withTimeout(10_000) { while (started.count > 0) kotlinx.coroutines.delay(10) }
        call.cancelAndJoin()
        release.countDown()
        assertTrue(call.isCancelled)
        assertEquals(0, store.clears)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
