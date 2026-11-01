package org.dad.foundation

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real partner conformers over a scripted transport (Retrofit + OkHttp run for real). */
class WirePartnerTest {
    private class MemoryStore(var value: Credentials? = null) : CredentialStore {
        override fun load() = value
        override fun save(value: Credentials) { this.value = value }
        override fun clear() { value = null }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun partnerRequestsCannotOutliveTheirSession() = runTest {
        for (rejected in listOf(false, true)) {
            val store = MemoryStore()
            val session = PartnerSession(store).also { it.install("old") }
            val gate = CompletableDeferred<Unit>()
            val request = async { runCatching { session.authorized {
                gate.await()
                if (rejected) throw AccountFailure.SignedOut
                "old private data"
            } } }
            runCurrent()
            session.install("new")
            gate.complete(Unit)
            assertEquals(AccountFailure.SignedOut, request.await().exceptionOrNull())
            assertEquals("new", session.token())
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun partnerSignInCannotRestoreASignedOutSession() = runTest {
        val store = MemoryStore()
        val session = PartnerSession(store)
        val gate = CompletableDeferred<String>()
        val request = async { runCatching { session.establish { gate.await() } } }
        runCurrent()
        session.clear()
        gate.complete("late")
        assertEquals(AccountFailure.SignedOut, request.await().exceptionOrNull())
        assertNull(store.value)
    }

    @Test fun cancelledPartnerSignInInstallsNothing() = runTest {
        val store = MemoryStore()
        val session = PartnerSession(store)
        val request = launch { session.establish { currentCoroutineContext().cancel(); "late" } }
        request.join()
        assertTrue(request.isCancelled)
        assertNull(store.value)
    }

    private val overviewJson = """{"user_id":"u1","organization":"Acme","description":"","role":"ADMIN","company_code":"ACME",""" +
        """"committed_minor":500000,"deployed_minor":300000,"deployed_percent":60,"people_reached":1200,"employees_enrolled":null,""" +
        """"workforce_size":120,"enrolled_percent":null,"updated_at":"2026-10-28T10:00:00Z","has_logo":false,""" +
        """"committed_verified":true,"deployed_verified":false,"people_reached_verified":true}"""

    @Test fun acceptKeepsTheBearerAndPortalCallsSendIt() = runTest {
        val http = ScriptedHttp {
            when (it.path) {
                "/donor/partner/accept-invite" -> 200 to """{"session":"PB"}"""
                "/donor/partner/overview" -> 200 to overviewJson
                else -> 404 to "{}"
            }
        }
        val store = MemoryStore()
        val session = PartnerSession(store)
        WirePartnerAuth(TEST_ORIGIN, session, http.client()).accept("INV")
        assertEquals("""{"invite":"INV"}""", http.requests.first().body)
        assertNull(http.requests.first().headers["authorization"])
        assertEquals("", store.value?.refresh)
        val overview = WirePartner(TEST_ORIGIN, session, http.client()).overview()
        assertEquals("Bearer PB", http.requests.last().headers["authorization"])
        assertEquals("native", http.requests.last().headers["x-dad-client"])
        assertNull(overview.deployed.label)
        assertNull(overview.enrolledLabel)
        assertEquals("u1", overview.userId)
    }

    @Test fun aUsedLinkAndABlockedPartnerAreTheirOwnFailures() = runTest {
        mapOf(422 to AccountFailure.InvalidInput, 403 to AccountFailure.Forbidden, 429 to AccountFailure.RateLimited).forEach { (status, expected) ->
            val wire = WirePartnerAuth(TEST_ORIGIN, PartnerSession(MemoryStore()), ScriptedHttp { status to "{}" }.client())
            assertEquals("$status", expected, runCatching { wire.accept("INV") }.exceptionOrNull())
        }
    }

    @Test fun aRejectedSessionIsForgotten() = runTest {
        val store = MemoryStore()
        val session = PartnerSession(store).also { it.install("PB") }
        val failure = runCatching { WirePartner(TEST_ORIGIN, session, ScriptedHttp { 401 to "{}" }.client()).users() }.exceptionOrNull()
        assertEquals(AccountFailure.SignedOut, failure)
        assertNull(store.value)
        assertFalse(session.isSignedIn)
    }

    @Test fun inviteRemoveAndLogoBodies() = runTest {
        val http = ScriptedHttp {
            when (it.path) {
                "/donor/partner/users" -> 200 to """{"invited":true}"""
                "/donor/partner/users/u2/remove" -> 200 to """{"removed":true}"""
                else -> 200 to """{"saved":true}"""
            }
        }
        val wire = WirePartner(TEST_ORIGIN, PartnerSession(MemoryStore()).also { it.install("PB") }, http.client())
        wire.inviteUser("b@x.com", "Bea", PartnerRole.VIEWER)
        wire.removeUser("u2")
        wire.saveLogo(byteArrayOf(1, 2, 3))
        wire.saveLogo(null)
        assertEquals("""{"email":"b@x.com","name":"Bea","role":"VIEWER"}""", http.requests[0].body)
        assertEquals("/donor/partner/users/u2/remove", http.requests[1].path)
        assertEquals("""{"data_url":"data:image/png;base64,AQID"}""", http.requests[2].body)
        assertEquals("""{"data_url":""}""", http.requests[3].body)
    }

    @Test fun aMissingLogoIsNoneAndAnAgedOutReportIsNotFound() = runTest {
        val wire = WirePartner(TEST_ORIGIN, PartnerSession(MemoryStore()).also { it.install("PB") }, ScriptedHttp { 404 to "{}" }.client())
        assertNull(wire.logo())
        assertEquals(AccountFailure.NotFound, runCatching { wire.retainedReport("r1") }.exceptionOrNull())
    }

    @Test fun signOutForgetsTheSessionEvenWhenTheServerIsDown() = runTest {
        val store = MemoryStore()
        val session = PartnerSession(store).also { it.install("PB") }
        WirePartner(TEST_ORIGIN, session, ScriptedHttp { 503 to "{}" }.client()).signOut()
        assertNull(store.value)
        assertTrue(!session.isSignedIn)
    }
}
