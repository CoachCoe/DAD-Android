package org.dad.foundation

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WireAPITest {
    @Test fun nativeReadUsesBearerWithoutBrowserHeaders() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("native", request.header("X-DAD-Client"))
            assertEquals("Bearer synthetic-access", request.header("Authorization"))
            assertNull(request.header("Origin"))
            assertNull(request.header("Cookie"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"email":"donor@example.org","public_name":null,"account_role":"DONOR","zip":null,"city":null,"timezone":null,"phone":null,"referral_emails":true,"marketing_emails":false,"push_referral_converted":true,"push_streak_at_risk":true,"push_streak_milestone":true,"push_streak_lapsed":true,"push_badge_earned":true,"push_gift_processed":true,"push_event_nearby":true,"push_event_reminder":true,"push_event_promoted":true,"rankings_opted_out":false,"network_visible":false,"volunteer":false,"volunteer_interests":[],"version":3,"consent_version":"synthetic-v1"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertEquals(AccountProfile("donor@example.org", null), WireAPI("https://api.test", http).profile("synthetic-access"))
    }
    @Test fun oversizedResponsesAreRejected() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("x".repeat(1_048_577).toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertEquals(AccountFailure.InvalidResponse, runCatching { WireAPI("https://api.test", http).profile("synthetic-access") }.exceptionOrNull())
    }
    @Test fun insecureOrNonOriginConfigurationIsRejected() {
        listOf("http://api.test", "https://user:pass@api.test", "https://api.test/path", "https://api.test?query=x").forEach {
            assertTrue(it, runCatching { WireAPI(it) }.isFailure)
        }
    }
    @Test fun logoutAcknowledgementRequiresTrue() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"signed_out":false}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertEquals(AccountFailure.InvalidResponse, runCatching { WireAPI("https://api.test", http).logout("synthetic-access") }.exceptionOrNull())
    }
}
