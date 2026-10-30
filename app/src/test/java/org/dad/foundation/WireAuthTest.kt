package org.dad.foundation

import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WireAuthTest {
    private fun auth(status: Int, json: String = "{}", device: String? = "Pixel"): Pair<WireAuth, ScriptedHttp> {
        val http = ScriptedHttp { status to json }
        return WireAuth(TEST_ORIGIN, device, http.client()) to http
    }
    private suspend fun failure(block: suspend () -> Any?): Throwable? = runCatching { block() }.exceptionOrNull()

    @Test fun requestCodeSendsPhoneWithNativeHeadersAndReturnsItAsHandle() = runTest {
        val (wire, http) = auth(200, """{"sent":true}""")
        assertEquals("+14155551234", wire.requestPhoneCode("+14155551234"))
        val sent = http.requests.single()
        assertEquals("/donor/phone/sign-in/options", sent.path)
        assertTrue(sent.body.contains("\"+14155551234\""))
        assertEquals("native", sent.headers["x-dad-client"])
        assertNull(sent.headers["authorization"])
        assertNull(sent.headers["origin"])
        assertNull(sent.headers["cookie"])
    }

    @Test fun requestCodeMapsStatuses() = runTest {
        assertEquals(AccountFailure.InvalidInput, failure { auth(422).first.requestPhoneCode("+14155551234") })
        assertEquals(AccountFailure.RateLimited, failure { auth(429).first.requestPhoneCode("+14155551234") })
        assertEquals(AccountFailure.Unavailable, failure { auth(500).first.requestPhoneCode("+14155551234") })
        assertEquals(AccountFailure.SignedOut, failure { auth(401).first.requestPhoneCode("+14155551234") })
    }

    @Test fun verifyReturnsCredentialsAndSendsAndroidPlatform() = runTest {
        val (wire, http) = auth(200, """{"signed_in":true,"access_token":"a","expires_in":900,"refresh_token":"r","refresh_expires_in":5184000}""")
        val credentials = wire.verifyPhone("+14155551234", "123456")
        assertEquals("a", credentials.access)
        assertEquals("r", credentials.refresh)
        assertTrue(credentials.expiresAtMillis > System.currentTimeMillis())
        val sent = http.requests.single()
        assertEquals("/donor/phone/sign-in", sent.path)
        assertTrue(sent.body.contains("\"ANDROID\""))
        assertTrue(sent.body.contains("\"123456\""))
        assertTrue(sent.body.contains("\"Pixel\""))
        assertNull(sent.headers["authorization"])
    }

    @Test fun verifyMapsStatuses() = runTest {
        assertEquals(AccountFailure.SignedOut, failure { auth(401).first.verifyPhone("+14155551234", "654321") })
        assertEquals(AccountFailure.InvalidInput, failure { auth(422).first.verifyPhone("+14155551234", "654321") })
        assertEquals(AccountFailure.RateLimited, failure { auth(429).first.verifyPhone("+14155551234", "654321") })
        assertEquals(AccountFailure.Unavailable, failure { auth(503).first.verifyPhone("+14155551234", "654321") })
    }

    @Test fun browserShapedBodyWithoutTokensIsNeverASession() = runTest {
        assertEquals(AccountFailure.InvalidResponse, failure { auth(200, """{"signed_in":true}""").first.verifyPhone("+14155551234", "123456") })
        assertEquals(AccountFailure.InvalidResponse, failure {
            auth(200, """{"signed_in":true,"access_token":"","expires_in":900,"refresh_token":"r","refresh_expires_in":1}""").first.verifyPhone("+14155551234", "123456")
        })
        assertEquals(AccountFailure.InvalidResponse, failure {
            auth(200, """{"signed_in":true,"access_token":"a","expires_in":0,"refresh_token":"r","refresh_expires_in":1}""").first.verifyPhone("+14155551234", "123456")
        })
    }

    @Test fun malformedBodyIsAnInvalidResponseNotACrash() = runTest {
        assertEquals(AccountFailure.InvalidResponse, failure { auth(200, "not json {").first.verifyPhone("+14155551234", "123456") })
    }

    @Test fun oversizedResponseIsRejected() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("x".repeat(1_048_577).toResponseBody()).build()
        }.build()
        assertEquals(AccountFailure.InvalidResponse, failure { WireAuth(TEST_ORIGIN, null, http).verifyPhone("+14155551234", "123456") })
    }

    @Test fun insecureOrNonOriginConfigurationIsRejected() {
        listOf("http://api.test", "https://user:pass@api.test", "https://api.test/path", "https://api.test?x=1").forEach {
            assertTrue(it, runCatching { WireAuth(it) }.isFailure)
        }
    }

    @Test fun cancellingTheCallerCancelsTheRequestAndRethrowsCancellation() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(nativeSessionJson().toResponseBody()).build()
        }.build()
        val call = async(Dispatchers.IO) { WireAuth(TEST_ORIGIN, null, http).verifyPhone("+14155551234", "123456") }
        withTimeout(10_000) { while (started.count > 0) kotlinx.coroutines.delay(10) }
        call.cancelAndJoin()
        release.countDown()
        assertTrue(call.isCancelled)
    }
}
