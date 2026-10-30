package org.dad.foundation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionWiringTest {
    private class Api : AccountAPI {
        var refreshes = 0
        val loggedOut = mutableListOf<String>()
        override suspend fun profile(access: String) = AccountProfile("a@b.c", null)
        override suspend fun refresh(token: String): Credentials { refreshes++; return Credentials("r-$refreshes", "rr-$refreshes", Long.MAX_VALUE) }
        override suspend fun claim(token: String) = Credentials("claimed", "claimed-refresh", Long.MAX_VALUE)
        override suspend fun logout(access: String) { loggedOut += access }
    }

    @Test fun establishInstallsTheCredentialsTheSessionAwaited() = runTest {
        val store = RecordingStore()
        val session = AccountSession(Api(), store, backgroundScope)
        assertFalse(session.restoredCredentials)
        val installed = session.establish { Credentials("in", "in-refresh", Long.MAX_VALUE) }
        assertEquals("in", installed.access)
        assertEquals("in", store.value?.access)
        assertTrue(session.isSignedIn())
    }

    @Test fun aSignInResolvingAfterASignOutIsDiscarded() = runTest {
        val store = RecordingStore(validCredentials())
        val session = AccountSession(Api(), store, backgroundScope)
        val gate = CompletableDeferred<Credentials>()
        val signIn = async { runCatching { session.establish { gate.await() } } }
        runCurrent()
        session.logout()
        gate.complete(Credentials("late", "late-refresh", Long.MAX_VALUE))
        assertEquals(AccountFailure.SignedOut, signIn.await().exceptionOrNull())
        assertNull(store.value)
        assertFalse(session.isSignedIn())
    }

    @Test fun aSignInResolvingAfterANewerSignInIsDiscarded() = runTest {
        val store = RecordingStore()
        val session = AccountSession(Api(), store, backgroundScope)
        val slow = CompletableDeferred<Credentials>()
        val first = async { runCatching { session.establish { slow.await() } } }
        runCurrent()
        session.establish { Credentials("newer", "newer-refresh", Long.MAX_VALUE) }
        slow.complete(Credentials("older", "older-refresh", Long.MAX_VALUE))
        assertEquals(AccountFailure.SignedOut, first.await().exceptionOrNull())
        assertEquals("newer", store.value?.access)
    }

    @Test fun aCancelledSignInInstallsNothing() = runTest {
        val store = RecordingStore()
        val session = AccountSession(Api(), store, backgroundScope)
        val gate = CompletableDeferred<Credentials>()
        val job = launch { session.establish { gate.await() } }
        runCurrent()
        job.cancelAndJoin()
        assertNull(store.value)
        assertFalse(session.isSignedIn())
    }

    @Test fun aFailedProduceLeavesTheExistingSessionAlone() = runTest {
        val store = RecordingStore(validCredentials())
        val session = AccountSession(Api(), store, backgroundScope)
        assertEquals(AccountFailure.SignedOut, runCatching { session.establish { throw AccountFailure.SignedOut } }.exceptionOrNull())
        assertEquals("access-0", store.value?.access)
    }

    @Test fun discardLocalClearsStorageWithoutAnyNetworkCall() = runTest {
        val api = Api()
        val store = RecordingStore(validCredentials())
        val session = AccountSession(api, store, backgroundScope)
        session.discardLocal()
        assertNull(store.value)
        assertFalse(session.isSignedIn())
        assertTrue(api.loggedOut.isEmpty())
    }

    @Test fun anInFlightCallIsDiscardedWhenLocalStateIsDropped() = runTest {
        val session = AccountSession(Api(), RecordingStore(validCredentials()), backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val call = async { runCatching { session.authorized { gate.await(); "ok" } } }
        runCurrent()
        session.discardLocal()
        gate.complete(Unit)
        assertEquals(AccountFailure.SignedOut, call.await().exceptionOrNull())
    }

    @Test fun authorizedRetriesExactlyOnceAfterA401() = runTest {
        val api = Api()
        val session = AccountSession(api, RecordingStore(validCredentials("stale")), backgroundScope)
        val seen = mutableListOf<String>()
        val result = session.authorized { access -> seen += access; if (access == "stale") throw AccountFailure.SignedOut else "ok" }
        assertEquals("ok", result)
        assertEquals(listOf("stale", "r-1"), seen)
        assertEquals(1, api.refreshes)
    }

    @Test fun twoUnauthorizedAnswersClearTheSession() = runTest {
        val store = RecordingStore(validCredentials("stale"))
        val session = AccountSession(Api(), store, backgroundScope)
        var calls = 0
        assertEquals(AccountFailure.SignedOut, runCatching { session.authorized<Unit> { calls++; throw AccountFailure.SignedOut } }.exceptionOrNull())
        assertEquals(2, calls)
        assertNull(store.value)
    }

    @Test fun nonAuthFailuresPropagateWithoutRefreshing() = runTest {
        val api = Api()
        val session = AccountSession(api, RecordingStore(validCredentials()), backgroundScope)
        assertEquals(AccountFailure.RateLimited, runCatching { session.authorized<Unit> { throw AccountFailure.RateLimited } }.exceptionOrNull())
        assertEquals(0, api.refreshes)
        assertTrue(session.isSignedIn())
    }

    @Test fun aCancelledAuthorizedCallRethrowsCancellation() = runTest {
        val session = AccountSession(Api(), RecordingStore(validCredentials()), backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val job = launch { session.authorized { gate.await() } }
        runCurrent()
        job.cancelAndJoin()
        yield()
        assertTrue(job.isCancelled)
        assertTrue(session.isSignedIn())
    }

    @Test fun restoredCredentialsReflectsTheStoreAtCreation() = runTest {
        assertTrue(AccountSession(Api(), RecordingStore(validCredentials()), backgroundScope).restoredCredentials)
        assertFalse(AccountSession(Api(), RecordingStore(), backgroundScope).restoredCredentials)
    }
}
