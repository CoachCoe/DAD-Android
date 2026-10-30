package org.dad.foundation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real conformer + real AccountSession + real Retrofit stack over a scripted transport. */
class WireSettingsTest {
    private class Rig(
        creds: Credentials? = validCredentials(),
        scope: kotlinx.coroutines.CoroutineScope,
        handler: (Recorded) -> Pair<Int, String>,
    ) {
        val http = ScriptedHttp(handler)
        val store = RecordingStore(creds)
        val client = http.client()
        val session = AccountSession(WireAPI(TEST_ORIGIN, client), store, scope)
        var wiped = 0
        val settings = WireSettings(ProductClient(TEST_ORIGIN, session, client), onLocalWipe = { wiped++ })
    }
    private suspend fun failure(block: suspend () -> Any?): Throwable? = runCatching { block() }.exceptionOrNull()

    @Test fun loadMapsProfileAndSendsTheBearer() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to profileJson(version = 7) }
        val snapshot = rig.settings.load()
        assertEquals("alex@example.org", snapshot.profile.email)
        assertEquals("DONOR", snapshot.profile.accountRole)
        assertEquals("78701", snapshot.profile.zip)
        assertNull(snapshot.profile.phone)
        assertEquals(7, snapshot.preferences.version)
        assertTrue(snapshot.preferences.pushGiftProcessed)
        assertFalse(snapshot.preferences.marketingEmails)
        val sent = rig.http.requests.single()
        assertEquals("/donor/me/profile", sent.path)
        assertEquals("Bearer access-0", sent.headers["authorization"])
        assertEquals("native", sent.headers["x-dad-client"])
        assertNull(sent.headers["origin"])
    }

    @Test fun anUnauthorizedReadRefreshesOnceAndRetriesWithTheNewBearer() = runTest {
        var profileCalls = 0
        val rig = Rig(scope = backgroundScope) { r ->
            when (r.path) {
                "/donor/refresh" -> 200 to nativeSessionJson("access-new", "refresh-new")
                else -> if (++profileCalls == 1) 401 to "{}" else 200 to profileJson()
            }
        }
        rig.settings.load()
        assertEquals(1, rig.http.count("/donor/refresh"))
        assertEquals(listOf("Bearer access-0", "Bearer access-new"), rig.http.requests.filter { it.path == "/donor/me/profile" }.map { it.headers["authorization"] })
        assertEquals("access-new", rig.store.value?.access)
    }

    @Test fun aSecondUnauthorizedEndsTheSessionLocally() = runTest {
        val rig = Rig(scope = backgroundScope) { r -> if (r.path == "/donor/refresh") 200 to nativeSessionJson("access-new") else 401 to "{}" }
        assertEquals(AccountFailure.SignedOut, failure { rig.settings.load() })
        assertNull(rig.store.value)
        assertFalse(rig.session.isSignedIn())
        assertEquals(1, rig.http.count("/donor/refresh"))
    }

    @Test fun aRevokedRefreshEndsTheSession() = runTest {
        val rig = Rig(creds = Credentials("old", "r", 0), scope = backgroundScope) { 401 to "{}" }
        assertEquals(AccountFailure.SignedOut, failure { rig.settings.load() })
        assertNull(rig.store.value)
    }

    @Test fun concurrentLoadsAcrossExpiryShareOneRefresh() = runTest {
        val rig = Rig(creds = Credentials("old", "r", 0), scope = backgroundScope) { r ->
            if (r.path == "/donor/refresh") 200 to nativeSessionJson("fresh") else 200 to profileJson()
        }
        val calls = List(8) { async { rig.settings.load() } }
        calls.forEach { it.await() }
        assertEquals(1, rig.http.count("/donor/refresh"))
        assertTrue(rig.http.requests.filter { it.path == "/donor/me/profile" }.all { it.headers["authorization"] == "Bearer fresh" })
    }

    @Test fun saveSendsTheVersionAndEveryToggleAndReturnsTheNewSnapshot() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to profileJson(version = 4, "network_visible" to false) }
        val before = Preferences(version = 3, referralEmails = true, marketingEmails = false, pushReferralConverted = true, pushStreakAtRisk = true,
            pushStreakMilestone = true, pushStreakLapsed = true, pushBadgeEarned = true, pushEventNearby = false, pushEventReminder = true,
            pushEventPromoted = false, pushGiftProcessed = true, rankingsOptedOut = false, networkVisible = false, volunteer = true)
        val after = rig.settings.savePreferences(before)
        val sent = rig.http.requests.single()
        assertEquals("/donor/me/preferences", sent.path)
        assertEquals("POST", sent.method)
        assertTrue(sent.body.contains("\"version\":3"))
        assertTrue(sent.body.contains("\"network_visible\":false"))
        assertTrue(sent.body.contains("\"push_gift_processed\":true"))
        assertEquals(4, after.preferences.version)
        assertFalse(after.preferences.networkVisible)
    }

    @Test fun saveMapsStaleThrottledAndUnauthorized() = runTest {
        val p = Preferences(3, true, false, true, true, true, true, true, false, true, false, true, false, true, true)
        assertEquals(AccountFailure.Unavailable, failure { Rig(scope = backgroundScope) { 409 to "{}" }.settings.savePreferences(p) })
        assertEquals(AccountFailure.RateLimited, failure { Rig(scope = backgroundScope) { 429 to "{}" }.settings.savePreferences(p) })
        assertEquals(AccountFailure.InvalidInput, failure { Rig(scope = backgroundScope) { 422 to "{}" }.settings.savePreferences(p) })
    }

    @Test fun deleteCallsTheServerFirstThenWipesLocallyWithoutALogoutCall() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to """{"deleted":true}""" }
        rig.settings.deleteAccount()
        assertEquals(listOf("/donor/me/delete"), rig.http.requests.map { it.path })
        assertEquals("POST", rig.http.requests.single().method)
        assertNull(rig.store.value)
        assertEquals(1, rig.wiped)
        assertFalse(rig.session.isSignedIn())
    }

    @Test fun aBlockedDeletionLeavesTheSessionAndLocalDataUntouched() = runTest {
        val rig = Rig(scope = backgroundScope) { 409 to """{"error":"active_recurring"}""" }
        assertEquals(AccountFailure.DeletionBlocked, failure { rig.settings.deleteAccount() })
        assertNotNull(rig.store.value)
        assertEquals(0, rig.wiped)
        assertTrue(rig.session.isSignedIn())
    }

    @Test fun deleteFailuresNeverWipeLocalData() = runTest {
        for ((status, expected) in listOf(429 to AccountFailure.RateLimited, 422 to AccountFailure.InvalidInput, 500 to AccountFailure.Unavailable)) {
            val rig = Rig(scope = backgroundScope) { status to "{}" }
            assertEquals(expected, failure { rig.settings.deleteAccount() })
            assertNotNull(rig.store.value)
            assertEquals(0, rig.wiped)
        }
    }

    @Test fun deleteWithoutAConfirmedAcknowledgementIsInvalidAndWipesNothing() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to """{"deleted":false}""" }
        assertEquals(AccountFailure.InvalidResponse, failure { rig.settings.deleteAccount() })
        assertNotNull(rig.store.value)
        assertEquals(0, rig.wiped)
    }

    @Test fun deleteRetriesOnceThroughRefreshOn401() = runTest {
        var deletes = 0
        val rig = Rig(scope = backgroundScope) { r ->
            when (r.path) {
                "/donor/refresh" -> 200 to nativeSessionJson("access-new")
                else -> if (++deletes == 1) 401 to "{}" else 200 to """{"deleted":true}"""
            }
        }
        rig.settings.deleteAccount()
        assertEquals(2, rig.http.count("/donor/me/delete"))
        assertEquals(1, rig.wiped)
    }

    @Test fun signOutClearsLocallyFirstAndToleratesAnOfflineRevocation() = runTest {
        val rig = Rig(scope = backgroundScope) { 503 to "{}" }
        rig.settings.signOut()
        assertNull(rig.store.value)
        assertEquals(1, rig.http.count("/donor/logout"))
        assertFalse(rig.session.isSignedIn())
    }

    @Test fun signOutRevokesTheNewestAccessToken() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to """{"signed_out":true}""" }
        rig.settings.signOut()
        assertEquals("Bearer access-0", rig.http.requests.single().headers["authorization"])
    }

    @Test fun aSignOutWhileAReadIsInFlightDiscardsTheResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = object : AccountAPI {
            override suspend fun profile(access: String): AccountProfile { gate.await(); return AccountProfile("a@b.c", null) }
            override suspend fun refresh(token: String) = validCredentials()
            override suspend fun claim(token: String) = validCredentials()
            override suspend fun logout(access: String) = Unit
        }
        val session = AccountSession(api, RecordingStore(validCredentials()), backgroundScope)
        val read = async { runCatching { session.profile() } }
        kotlinx.coroutines.yield()
        session.discardLocal()
        gate.complete(Unit)
        assertEquals(AccountFailure.SignedOut, read.await().exceptionOrNull())
    }
}
