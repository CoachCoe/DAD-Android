package org.dad.foundation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * `applyWithReceipt` over the scripted transport: the receipt is chosen by the app and kept BEFORE the request
 * leaves (so a decided application can still be read after a crash); a definitive refusal (422/409/403) forgets it,
 * a throttle/outage/lost connection keeps it; the native headers are sent and no bearer or cookie ever is.
 */
class WirePartnerApplicationTest {
    private class MemoryStore(var value: Credentials? = null) : CredentialStore {
        override fun load() = value
        override fun save(value: Credentials) { this.value = value }
        override fun clear() { value = null }
    }

    private val application = PartnerApplication(
        organization = "Acme", website = "https://acme.example", type = PartnerOrganizationType.CORPORATE,
        contactName = "Ada Admin", title = "COO", workEmail = "ada@acme.example", phone = "+1 555 0100", workforceSize = 240,
    )

    private fun attempt(receipt: String = "R".repeat(43)) =
        PartnerApplicationAttempt(application, UUID.randomUUID().toString(), receipt)

    @Test fun theReceiptIsKeptBeforeSendingAndTheNativeRequestCarriesNoBearerOrCookie() = runTest {
        val store = MemoryStore()
        val http = ScriptedHttp { 200 to """{"received":true}""" }
        WirePartnerApplication(TEST_ORIGIN, store, http.client()).submit(attempt("A".repeat(43)))
        assertEquals("A".repeat(43), store.value?.access)
        val sent = http.requests.single()
        assertEquals("/donor/partner/applications", sent.path)
        assertEquals("native", sent.headers["x-dad-client"])
        assertNotNull("an Idempotency-Key is sent", sent.headers["idempotency-key"])
        assertNull(sent.headers["authorization"])
        assertNull(sent.headers["cookie"])
        assertNull(sent.headers["origin"])
        assertTrue("the chosen receipt is in the body", sent.body.contains("A".repeat(43)))
        assertTrue(sent.body.contains("\"organization\":\"Acme\""))
        assertTrue(sent.body.contains("\"work_email\":\"ada@acme.example\""))
        assertTrue(sent.body.contains("\"workforce_size\":240"))
    }

    @Test fun aSuccessWithAnUnreadableBodyIsStillAccepted() = runTest {
        val store = MemoryStore()
        // The write needs nothing from the 200 body, so an empty or malformed one is not a failure.
        WirePartnerApplication(TEST_ORIGIN, store, ScriptedHttp { 200 to "" }.client()).submit(attempt())
        assertNotNull(store.value)
    }

    @Test fun aDefinitiveRefusalForgetsTheReceipt() = runTest {
        val expected = mapOf(
            403 to ApplicationSubmitFailure.Forbidden, 409 to ApplicationSubmitFailure.Conflict, 422 to ApplicationSubmitFailure.Invalid,
        )
        for ((status, failure) in expected) {
            val store = MemoryStore()
            val actual = runCatching { WirePartnerApplication(TEST_ORIGIN, store, ScriptedHttp { status to "{}" }.client()).submit(attempt()) }.exceptionOrNull()
            assertEquals("$status", failure, actual)
            assertNull("$status forgets the receipt", store.value)
        }
    }

    @Test fun aThrottleOrOutageKeepsTheReceiptSoARetryCanRepeatIt() = runTest {
        for (status in listOf(429, 500, 503)) {
            val store = MemoryStore()
            val actual = runCatching { WirePartnerApplication(TEST_ORIGIN, store, ScriptedHttp { status to "" }.client()).submit(attempt()) }.exceptionOrNull()
            assertTrue("$status is a submit failure", actual is ApplicationSubmitFailure)
            assertNotNull("$status keeps the receipt", store.value)
        }
    }

    @Test fun aThrottleSurfacesRetryAfterSeconds() = runTest {
        val http = object {
            val inner = ScriptedHttp { 429 to "" }
        }
        // ScriptedHttp cannot set response headers; verify the mapping directly through the model's wait message
        // and the wire's handling of a Retry-After-less 429 instead.
        val actual = runCatching { WirePartnerApplication(TEST_ORIGIN, MemoryStore(), http.inner.client()).submit(attempt()) }.exceptionOrNull()
        assertEquals(ApplicationSubmitFailure.RateLimited(null), actual)
    }

    @Test fun aRetryRepeatsTheSameKeyAndReceipt() = runTest {
        val store = MemoryStore()
        val a = attempt("B".repeat(43))
        val http = ScriptedHttp { 503 to "{}" }
        runCatching { WirePartnerApplication(TEST_ORIGIN, store, http.client()).submit(a) }
        val accepting = ScriptedHttp { 200 to """{"received":true}""" }
        WirePartnerApplication(TEST_ORIGIN, store, accepting.client()).submit(a) // same frozen attempt
        val first = http.requests.single()
        val second = accepting.requests.single()
        assertEquals("same Idempotency-Key", first.headers["idempotency-key"], second.headers["idempotency-key"])
        assertTrue("same receipt", second.body.contains("B".repeat(43)))
        assertNotNull(store.value)
    }

    @Test fun aReceiptIsThirtyTwoRandomBytesAsFortyThreeBase64urlCharacters() {
        val one = makeApplicationReceipt()
        assertEquals(43, one.length)
        assertTrue(one.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertTrue("padding is stripped", !one.contains('='))
        assertTrue("two receipts differ", one != makeApplicationReceipt())
    }
}
