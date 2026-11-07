package org.dad.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The partner conformers over a scripted transport: every operation maps every status the same way, bodies that are
 * malformed or oversized are an invalid response, enum values this build does not know are tolerated, and a partner
 * request never carries the donor's token (nor the donor's the partner's).
 */
class WirePartnerStatusTest {
    private class MemoryStore(var value: Credentials? = null) : CredentialStore {
        override fun load() = value
        override fun save(value: Credentials) { this.value = value }
        override fun clear() { value = null }
    }

    private fun wire(http: ScriptedHttp, store: MemoryStore = MemoryStore()): WirePartner =
        WirePartner(TEST_ORIGIN, PartnerSession(store).also { it.install("PB") }, http.client())

    private val operations: Map<String, suspend (WirePartner) -> Any?> = mapOf(
        "overview" to { it.overview() },
        "initiatives" to { it.initiatives() },
        "spend" to { it.spend() },
        "people" to { it.people() },
        "teams" to { it.teams() },
        "users" to { it.users() },
        "invite" to { it.inviteUser("b@x.com", "Bea", PartnerRole.VIEWER) },
        "remove" to { it.removeUser("u2") },
        "saveProfile" to { it.saveProfile("Acme", "About") },
        "saveWorkforce" to { it.saveWorkforce(10) },
        "saveLogo" to { it.saveLogo(byteArrayOf(1)) },
        "exports" to { it.exports() },
        "report" to { it.report() },
        "retained" to { it.retainedReport("r1") },
    )

    @Test fun everyOperationMapsEveryStatusTheSameWay() = runTest {
        val expected = mapOf(
            401 to AccountFailure.SignedOut, 403 to AccountFailure.Forbidden, 404 to AccountFailure.NotFound,
            409 to AccountFailure.Conflict, 422 to AccountFailure.InvalidInput, 429 to AccountFailure.RateLimited,
            500 to AccountFailure.Unavailable, 503 to AccountFailure.Unavailable,
        )
        for ((name, call) in operations) for ((status, failure) in expected) {
            // The ingress limiter's 429 has no body; every other error body here is empty JSON.
            val body = if (status == 429) "" else "{}"
            val actual = runCatching { call(wire(ScriptedHttp { status to body })) }.exceptionOrNull()
            assertEquals("$name -> $status", failure, actual)
        }
    }

    @Test fun anEndedSessionIsForgottenByEveryOperation() = runTest {
        for ((name, call) in operations) {
            val store = MemoryStore()
            val failure = runCatching { call(wire(ScriptedHttp { 401 to "{}" }, store)) }.exceptionOrNull()
            assertEquals(name, AccountFailure.SignedOut, failure)
            assertNull(name, store.value)
        }
    }

    @Test fun aMalformedOrEmptySuccessBodyIsAnInvalidResponse() = runTest {
        val reads = listOf("overview", "initiatives", "spend", "people", "teams", "users", "exports")
        for (name in reads) for (body in listOf("", "not json", "[]", "{\"unrelated\":1}")) {
            val actual = runCatching { operations.getValue(name)(wire(ScriptedHttp { 200 to body })) }.exceptionOrNull()
            assertEquals("$name <- '$body'", AccountFailure.InvalidResponse, actual)
        }
    }

    @Test fun anOversizedBodyIsAnInvalidResponse() = runTest {
        val huge = "{\"users\":[],\"pad\":\"" + "x".repeat(1_048_577) + "\"}"
        assertEquals(AccountFailure.InvalidResponse, runCatching { wire(ScriptedHttp { 200 to huge }).users() }.exceptionOrNull())
        val bigPdf = "x".repeat(16 * 1_048_576 + 1)
        assertEquals(AccountFailure.InvalidResponse, runCatching { wire(ScriptedHttp { 200 to bigPdf }).report() }.exceptionOrNull())
    }

    @Test fun enumValuesThisBuildDoesNotKnowAreTolerated() = runTest {
        val http = ScriptedHttp {
            when (it.path) {
                "/donor/partner/overview" -> 200 to ("""{"user_id":"u1","organization":"Acme","description":"","role":"AUDITOR","company_code":"ACME",""" +
                    """"committed_minor":1,"deployed_minor":1,"deployed_percent":1,"people_reached":1,"employees_enrolled":null,"workforce_size":1,""" +
                    """"enrolled_percent":null,"updated_at":null,"has_logo":false,"committed_verified":true,"deployed_verified":true,"people_reached_verified":true}""")
                "/donor/partner/initiatives" -> 200 to ("""{"initiatives":[{"id":"i1","title":"T","summary":"S","status":"PAUSED","region":"R","committed_minor":1,""" +
                    """"deployed_minor":1,"metrics":[{"label":"L","value":3,"unit":"HOURS"}]}]}""")
                "/donor/partner/users" -> 200 to """{"users":[{"id":"u9","email":"a@b.co","name":"N","role":"OWNER"}]}"""
                else -> 404 to "{}"
            }
        }
        val partner = wire(http)
        // An unknown role is the least-privileged viewer, so no admin control opens for it.
        assertEquals(PartnerRole.VIEWER, partner.overview().role)
        assertFalse(partner.overview().role.canManageUsers)
        assertEquals(InitiativeStatus.ACTIVE, partner.initiatives().single().status)
        assertEquals(PartnerMetric.Unit.PEOPLE, partner.initiatives().single().metrics.single().unit)
        assertEquals(PartnerRole.VIEWER, partner.users().single().role)
    }

    @Test fun partnerRequestsNeverCarryTheDonorTokenAndSignOutUsesThePartnerBearer() = runTest {
        val http = ScriptedHttp {
            when (it.path) {
                "/donor/partner/overview" -> 200 to """{}"""
                "/donor/partner/sign-out" -> 200 to """{"signed_out":true}"""
                else -> 404 to "{}"
            }
        }
        val donor = RecordingStore(validCredentials("DONOR-ACCESS", "DONOR-REFRESH"))
        val partnerStore = MemoryStore()
        val session = PartnerSession(partnerStore).also { it.install("PB") }
        val partner = WirePartner(TEST_ORIGIN, session, http.client())
        runCatching { partner.overview() }
        partner.signOut()
        assertTrue(http.requests.isNotEmpty())
        assertTrue(http.requests.all { it.headers["authorization"] == "Bearer PB" })
        assertTrue(http.requests.none { it.headers.values.any { value -> value.contains("DONOR") } || it.body.contains("DONOR") })
        assertTrue(http.requests.all { it.headers["origin"] == null && it.headers["cookie"] == null && it.path.startsWith("/donor/partner/") })
        // Signing out of the partner side leaves the donor's credentials alone.
        assertNull(partnerStore.value)
        assertEquals("DONOR-ACCESS", donor.value?.access)
        assertEquals(0, donor.clears)
    }

    @Test fun theSessionLastsTwelveHoursThenIsForgottenWithoutTouchingTheDonor() {
        var now = 1_000_000L
        val donor = RecordingStore(validCredentials())
        val store = MemoryStore()
        val session = PartnerSession(store, now = { now })
        session.install("PB")
        now += PartnerSession.LIFETIME_MILLIS - 1
        assertEquals("PB", session.token())
        now += 1
        assertFalse(session.isSignedIn)
        assertNull(store.value)
        assertNotNull(donor.value)
        assertEquals(0, donor.clears)
    }

    @Test fun cancellingAPartnerReadRethrowsCancellationAndKeepsTheSession() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = MemoryStore()
        val http = ScriptedHttp { started.countDown(); release.await(10, TimeUnit.SECONDS); 200 to """{"users":[]}""" }
        val partner = wire(http, store)
        val thrown = AtomicReference<Throwable?>()
        val read = async(Dispatchers.Default) { try { partner.users() } catch (error: Throwable) { thrown.set(error); throw error } }
        withContext(Dispatchers.IO) { assertTrue(started.await(10, TimeUnit.SECONDS)) }
        read.cancel()
        release.countDown()
        withContext(Dispatchers.IO) { read.join() }
        assertTrue(read.isCancelled)
        assertTrue("expected CancellationException, got ${thrown.get()}", thrown.get() is CancellationException)
        assertNotNull(store.value)
    }

    // --- application status (the receipt is the bearer, kept apart from both sessions) ---

    private fun receiptStore() = MemoryStore(Credentials("RECEIPT", "", Long.MAX_VALUE))

    @Test fun applicationStatusSendsTheReceiptAsItsOnlyBearer() = runTest {
        val http = ScriptedHttp { 200 to """{"organization":"Acme","status":"PENDING"}""" }
        val info = WireApplicationStatus(TEST_ORIGIN, receiptStore(), http.client()).status()
        assertEquals(ApplicationStatusInfo("Acme", ApplicationState.Pending), info)
        assertEquals("Bearer RECEIPT", http.requests.single().headers["authorization"])
        assertEquals("native", http.requests.single().headers["x-dad-client"])
        assertEquals("/donor/partner/application-status", http.requests.single().path)
    }

    @Test fun withoutAReceiptNothingIsReadOrSent() = runTest {
        val http = ScriptedHttp { 200 to "{}" }
        val wire = WireApplicationStatus(TEST_ORIGIN, MemoryStore(), http.client())
        assertNull(wire.status())
        assertTrue(http.requests.isEmpty())
    }

    @Test fun anUnrecognizedReceiptIsForgottenAndShowsNothing() = runTest {
        val store = receiptStore()
        assertNull(WireApplicationStatus(TEST_ORIGIN, store, ScriptedHttp { 401 to "{}" }.client()).status())
        assertNull(store.value)
    }

    @Test fun applicationStatusMapsTheOtherStatusesAndKeepsTheReceipt() = runTest {
        val expected = mapOf(
            403 to AccountFailure.Forbidden, 404 to AccountFailure.NotFound, 422 to AccountFailure.InvalidInput,
            429 to AccountFailure.RateLimited, 503 to AccountFailure.Unavailable,
        )
        for ((status, failure) in expected) {
            val store = receiptStore()
            val actual = runCatching { WireApplicationStatus(TEST_ORIGIN, store, ScriptedHttp { status to "" }.client()).status() }.exceptionOrNull()
            assertEquals("$status", failure, actual)
            assertNotNull("$status keeps the receipt", store.value)
        }
    }

    @Test fun applicationStatusToleratesNewStatesAndRejectsMalformedBodies() = runTest {
        val info = WireApplicationStatus(TEST_ORIGIN, receiptStore(),
            ScriptedHttp { 200 to """{"organization":"Acme","status":"UNDER_REVIEW"}""" }.client()).status()
        assertEquals(ApplicationState.Other("UNDER_REVIEW"), info?.state)
        for (body in listOf("", "nope", "[]", """{"organization":"Acme"}""", """{"status":"PENDING"}""")) {
            val actual = runCatching { WireApplicationStatus(TEST_ORIGIN, receiptStore(), ScriptedHttp { 200 to body }.client()).status() }.exceptionOrNull()
            assertEquals("'$body'", AccountFailure.InvalidResponse, actual)
        }
    }

    @Test fun theApplicantSeesOneSentencePerState() {
        assertTrue(ApplicationStatusInfo("Acme", ApplicationState.Pending).message.contains("being reviewed"))
        assertTrue(ApplicationStatusInfo("Acme", ApplicationState.Approved).message.contains("sign-in link"))
        assertTrue(ApplicationStatusInfo("Acme", ApplicationState.Rejected).message.contains("wasn’t approved"))
        val unknown = ApplicationStatusInfo("Acme", ApplicationState.from("SOMETHING_NEW"))
        assertEquals("Application status", unknown.title)
        assertFalse(unknown.message.contains("SOMETHING_NEW"))
    }
}
