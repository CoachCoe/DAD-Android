package org.dad.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalSignInTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val goodClient = "1234567890-abc.apps.googleusercontent.com"

    // MARK: gating

    @Test fun methodsAreOffByDefaultAndAClientIdAloneNeverSwitchesOneOn() {
        assertEquals(SignInCapabilities.NONE, SignInCapabilities.of(SignInConfig(null, null)))
        assertEquals(SignInCapabilities.NONE, SignInCapabilities.of(SignInConfig("", "")))
        // configured, but no platform provider linked
        assertEquals(SignInCapabilities.NONE, SignInCapabilities.of(SignInConfig(goodClient, "dad.example")))
        assertTrue(SignInCapabilities.NONE.hasUnavailableMethod)
    }

    @Test fun malformedConfigurationStaysOff() {
        val bad = SignInConfig("not-a-client-id", "http://dad.example/path")
        assertNull(bad.validGoogleClientId); assertNull(bad.validPasskeyRelyingParty)
        assertEquals(SignInCapabilities.NONE, SignInCapabilities.of(bad, googleProviderAvailable = true, passkeyProviderAvailable = true))
    }

    @Test fun configuredAndProvidedTurnsEachMethodOnIndependently() {
        val config = SignInConfig(goodClient, "DAD.example")
        assertEquals("dad.example", config.validPasskeyRelyingParty)
        val google = SignInCapabilities.of(config, googleProviderAvailable = true)
        assertTrue(google.google); assertFalse(google.passkey)
        val both = SignInCapabilities.of(config, googleProviderAvailable = true, passkeyProviderAvailable = true)
        assertTrue(both.isAvailable(SignInMethod.GOOGLE)); assertTrue(both.isAvailable(SignInMethod.PASSKEY))
        assertFalse(both.hasUnavailableMethod)
        assertTrue(SignInCapabilities.NONE.isAvailable(SignInMethod.PHONE)); assertTrue(SignInCapabilities.NONE.isAvailable(SignInMethod.PASSWORD))
        assertFalse(SignInCapabilities.NONE.isAvailable(SignInMethod.GOOGLE)); assertFalse(SignInCapabilities.NONE.isAvailable(SignInMethod.PASSKEY))
    }

    @Test fun anUnconfiguredBuildFailsClearly() = runTest {
        assertEquals(AccountFailure.InvalidConfiguration, runCatching { UnconfiguredExternalSignIn.signIn("t") }.exceptionOrNull())
        assertEquals(AccountFailure.InvalidConfiguration, runCatching { UnconfiguredExternalSignIn.begin() }.exceptionOrNull())
    }

    // MARK: wire

    private fun wire(status: Int, json: String = "{}"): Pair<WireExternalSignIn, ScriptedHttp> {
        val http = ScriptedHttp { status to json }
        return WireExternalSignIn(TEST_ORIGIN, "Pixel", http.client()) to http
    }
    private suspend fun failure(block: suspend () -> Any?): Throwable? = runCatching { block() }.exceptionOrNull()

    @Test fun googleExchangeSendsProviderPathTokenAndPlatform() = runTest {
        val (w, http) = wire(200, nativeSessionJson("g-access", "g-refresh"))
        val credentials = w.signIn("id-token-value")
        assertEquals("g-access", credentials.access)
        val sent = http.requests.single()
        assertEquals("/donor/oidc/google/sign-in", sent.path)
        assertTrue(sent.body.contains("\"id_token\":\"id-token-value\""))
        assertTrue(sent.body.contains("\"platform\":\"ANDROID\""))
        assertTrue(sent.body.contains("\"device_name\":\"Pixel\""))
        assertNull(sent.headers["authorization"])
    }

    @Test fun googleExchangeRejectsEmptyOrOversizedTokensBeforeTheWire() = runTest {
        val (w, http) = wire(200, nativeSessionJson())
        assertEquals(AccountFailure.InvalidInput, failure { w.signIn("") })
        assertEquals(AccountFailure.InvalidInput, failure { w.signIn("x".repeat(8193)) })
        assertTrue(http.requests.isEmpty())
    }

    @Test fun googleExchangeMapsStatusesAndBadBodies() = runTest {
        assertEquals(AccountFailure.SignedOut, failure { wire(401).first.signIn("t") })
        assertEquals(AccountFailure.InvalidInput, failure { wire(422).first.signIn("t") })
        assertEquals(AccountFailure.RateLimited, failure { wire(429).first.signIn("t") })
        assertEquals(AccountFailure.Unavailable, failure { wire(503).first.signIn("t") })
        assertEquals(AccountFailure.InvalidResponse, failure { wire(200, "not json {").first.signIn("t") })
        assertEquals(AccountFailure.InvalidResponse, failure { wire(200, """{"signed_in":true}""").first.signIn("t") })
    }

    @Test fun passkeyBeginReadsTheChallenge() = runTest {
        val challenge = byteArrayOf(1, 2, 3, 4, 5).base64Url()
        val (w, http) = wire(200, """{"ceremony":"cer-1","options":{"challenge":"$challenge","rpId":"dad.example"}}""")
        val value = w.begin()
        assertEquals("cer-1", value.ceremony)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), value.challenge)
        assertEquals("dad.example", value.relyingParty)
        assertEquals("/donor/passkeys/sign-in/options", http.requests.single().path)
    }

    @Test fun passkeyBeginRejectsMalformedCeremonies() = runTest {
        assertEquals(AccountFailure.InvalidResponse, failure { wire(200, """{"ceremony":"","options":{"challenge":"AQID"}}""").first.begin() })
        assertEquals(AccountFailure.InvalidResponse, failure { wire(200, """{"ceremony":"c","options":{}}""").first.begin() })
        assertEquals(AccountFailure.InvalidResponse, failure { wire(200, """{"ceremony":"c","options":{"challenge":"!!!"}}""").first.begin() })
        assertEquals(AccountFailure.RateLimited, failure { wire(429).first.begin() })
        assertEquals(AccountFailure.Unavailable, failure { wire(503).first.begin() })
    }

    private val challenge = PasskeyChallenge("cer-1", byteArrayOf(1), "dad.example")
    private val assertion = PasskeyAssertion(byteArrayOf(9, 9), byteArrayOf(1, 2), byteArrayOf(3, 4), byteArrayOf(5, 6), byteArrayOf(7))

    @Test fun passkeyFinishSendsTheWebAuthnJson() = runTest {
        val (w, http) = wire(200, nativeSessionJson("p-access", "p-refresh"))
        assertEquals("p-access", w.finish(challenge, assertion).access)
        val sent = http.requests.single()
        assertEquals("/donor/passkeys/sign-in", sent.path)
        val body = sent.body
        assertTrue(body.contains("\"ceremony\":\"cer-1\""))
        assertTrue(body.contains("\"type\":\"public-key\""))
        assertTrue(body.contains("\"id\":\"${byteArrayOf(9, 9).base64Url()}\""))
        assertTrue(body.contains("\"signature\":\"${byteArrayOf(5, 6).base64Url()}\""))
        assertTrue(body.contains("\"userHandle\":\"${byteArrayOf(7).base64Url()}\""))
        assertTrue(body.contains("\"platform\":\"ANDROID\""))
    }

    @Test fun passkeyFinishMapsStatuses() = runTest {
        assertEquals(AccountFailure.SignedOut, failure { wire(401).first.finish(challenge, assertion) })
        assertEquals(AccountFailure.InvalidInput, failure { wire(422).first.finish(challenge, assertion) })
        assertEquals(AccountFailure.RateLimited, failure { wire(429).first.finish(challenge, assertion) })
        assertEquals(AccountFailure.Unavailable, failure { wire(500).first.finish(challenge, assertion) })
    }

    @Test fun base64UrlRoundTripsWithoutPadding() {
        val data = byteArrayOf(-1, -2, -3, 0, 1, 62, 63)
        val text = data.base64Url()
        assertFalse(text.contains("=") || text.contains("+") || text.contains("/"))
        assertArrayEquals(data, text.fromBase64Url())
        assertNull("!!!".fromBase64Url())
    }

    // MARK: models

    private class FakeOidc(var failure: Exception? = null) : OidcSignInService {
        val tokens = mutableListOf<String>()
        override suspend fun signIn(idToken: String): Credentials { tokens += idToken; failure?.let { throw it }; return validCredentials("g") }
    }
    private val passThrough: Establish = { produce -> produce() }

    @Test fun googleModelHappyPath() {
        val oidc = FakeOidc()
        val m = GoogleSignInModel({ "tok" }, oidc, passThrough)
        m.start()
        assertEquals(ExternalSignInPhase.SignedIn, m.phase)
        assertEquals(listOf("tok"), oidc.tokens)
    }

    @Test fun googleModelTreatsBackingOutAsIdleAndMapsErrors() {
        val cancelled = GoogleSignInModel({ throw ExternalSignInError.Cancelled }, FakeOidc(), passThrough)
        cancelled.start()
        assertEquals(ExternalSignInPhase.Idle, cancelled.phase)
        val unsupported = GoogleSignInModel({ throw ExternalSignInError.Unsupported }, FakeOidc(), passThrough)
        unsupported.start()
        assertEquals(ExternalSignInPhase.Failed("Google sign-in isn’t available on this device."), unsupported.phase)
        mapOf(
            AccountFailure.SignedOut to "We couldn’t find a DAD account for that Google sign-in. Create your account with your email first.",
            AccountFailure.RateLimited to "Too many attempts. Please wait a moment and try again.",
            AccountFailure.Unavailable to "Google sign-in isn’t available right now. Please try again in a moment.",
            AccountFailure.InvalidResponse to "Something went wrong. Please try again.",
        ).forEach { (failure, copy) ->
            val m = GoogleSignInModel({ "t" }, FakeOidc(failure), passThrough)
            m.start()
            assertEquals(ExternalSignInPhase.Failed(copy), m.phase)
        }
    }

    private class FakePasskey(var failure: Exception? = null) : PasskeySignInService {
        var finished = 0
        override suspend fun begin() = PasskeyChallenge("c", byteArrayOf(1), null)
        override suspend fun finish(challenge: PasskeyChallenge, assertion: PasskeyAssertion): Credentials {
            finished++; failure?.let { throw it }; return validCredentials("p")
        }
    }
    private val sampleAssertion = PasskeyAssertion(byteArrayOf(1), byteArrayOf(1), byteArrayOf(1), byteArrayOf(1), null)

    @Test fun passkeyModelHappyPathAndBackingOut() {
        val service = FakePasskey()
        val m = PasskeySignInModel({ sampleAssertion }, service, passThrough)
        m.start()
        assertEquals(ExternalSignInPhase.SignedIn, m.phase)
        assertEquals(1, service.finished)
        val backedOut = PasskeySignInModel({ throw ExternalSignInError.Cancelled }, FakePasskey(), passThrough)
        backedOut.start()
        assertEquals(ExternalSignInPhase.Idle, backedOut.phase)
    }

    @Test fun passkeyModelMapsErrors() {
        mapOf(
            AccountFailure.SignedOut to "That passkey didn’t work. Try another sign-in method.",
            AccountFailure.RateLimited to "Too many attempts. Please wait a moment and try again.",
            AccountFailure.Unavailable to "Passkey sign-in isn’t available right now. Please try again in a moment.",
        ).forEach { (failure, copy) ->
            val m = PasskeySignInModel({ sampleAssertion }, FakePasskey(failure), passThrough)
            m.start()
            assertEquals(ExternalSignInPhase.Failed(copy), m.phase)
        }
        val unsupported = PasskeySignInModel({ throw ExternalSignInError.Unsupported }, FakePasskey(), passThrough)
        unsupported.start()
        assertEquals(ExternalSignInPhase.Failed("Passkeys aren’t available on this device."), unsupported.phase)
    }

    @Test fun cancellationIsRethrownNotShown() {
        val m = GoogleSignInModel({ throw CancellationException("x") }, FakeOidc(), passThrough)
        runCatching { m.start() }
        assertFalse(m.phase is ExternalSignInPhase.Failed)
    }
}
