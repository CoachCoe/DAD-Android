package org.dad.foundation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private class MemoryStore(var value: Credentials?) : CredentialStore {
    var saves = 0
    override fun load() = value
    override fun save(value: Credentials) { this.value = value; saves++ }
    override fun clear() { value = null }
}
private class ControlledAPI : AccountAPI {
    var refreshes = 0
    val started = CompletableDeferred<Unit>()
    val answer = CompletableDeferred<Credentials>()
    val loggedOut = mutableListOf<String>()
    override suspend fun profile(access: String): AccountProfile {
        if (access == "old") throw AccountFailure.SignedOut
        return AccountProfile("donor@example.org", "Alex")
    }
    override suspend fun refresh(token: String): Credentials { refreshes++; started.complete(Unit); return answer.await() }
    override suspend fun claim(token: String) = Credentials("claimed", "claim-refresh", Long.MAX_VALUE)
    override suspend fun logout(access: String) { loggedOut += access }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FoundationTest {
    @Test fun concurrentExpiryUsesOneRefreshAndOnePersistence() = runTest {
        val api = ControlledAPI()
        val store = MemoryStore(Credentials("old", "refresh", 0))
        val session = AccountSession(api, store, backgroundScope)
        val requests = List(20) { async { session.profile() } }
        api.started.await()
        api.answer.complete(Credentials("new", "new-refresh", Long.MAX_VALUE))
        assertEquals(20, requests.awaitAll().size)
        assertEquals(1, api.refreshes)
        assertEquals(1, store.saves)
        assertEquals("new", store.value?.access)
    }
    @Test fun unauthorizedReadRefreshesOnce() = runTest {
        val api = ControlledAPI()
        val session = AccountSession(api, MemoryStore(Credentials("old", "refresh", Long.MAX_VALUE)), backgroundScope)
        val request = async { session.profile() }
        api.started.await()
        api.answer.complete(Credentials("new", "new-refresh", Long.MAX_VALUE))
        assertEquals("Alex", request.await().publicName)
        assertEquals(1, api.refreshes)
    }
    @Test fun logoutDuringRefreshCannotRestoreCredentialsAndRevokesNewToken() = runTest {
        val api = ControlledAPI()
        val store = MemoryStore(Credentials("old", "refresh", 0))
        val session = AccountSession(api, store, backgroundScope)
        val request = async { runCatching { session.profile() } }
        api.started.await()
        val logout = async { session.logout() }
        runCurrent()
        assertNull(store.value)
        api.answer.complete(Credentials("new", "new-refresh", Long.MAX_VALUE))
        logout.await()
        assertEquals(AccountFailure.SignedOut, request.await().exceptionOrNull())
        assertNull(store.value)
        assertEquals(listOf("new"), api.loggedOut)
    }
    @Test fun cancelledReaderDoesNotCancelSharedRefresh() = runTest {
        val api = ControlledAPI()
        val store = MemoryStore(Credentials("old", "refresh", 0))
        val session = AccountSession(api, store, backgroundScope)
        val first = async { session.profile() }
        api.started.await()
        val second = async { session.profile() }
        first.cancel()
        api.answer.complete(Credentials("new", "new-refresh", Long.MAX_VALUE))
        assertEquals("Alex", second.await().publicName)
        assertEquals(1, api.refreshes)
        assertEquals("new", store.value?.access)
    }
    @Test fun revokedRefreshClearsStorage() = runTest {
        val api = ControlledAPI()
        val store = MemoryStore(Credentials("old", "refresh", 0))
        val session = AccountSession(api, store, backgroundScope)
        val request = async { runCatching { session.profile() } }
        api.started.await()
        api.answer.completeExceptionally(AccountFailure.SignedOut)
        assertEquals(AccountFailure.SignedOut, request.await().exceptionOrNull())
        assertNull(store.value)
    }
    @Test fun newClaimCannotBeOverwrittenByEarlierRefresh() = runTest {
        val api = ControlledAPI()
        val store = MemoryStore(Credentials("old", "refresh", 0))
        val session = AccountSession(api, store, backgroundScope)
        val old = async { runCatching { session.profile() } }
        api.started.await()
        session.claim("synthetic-proof")
        assertEquals("Alex", session.profile().publicName)
        api.answer.complete(Credentials("new", "new-refresh", Long.MAX_VALUE))
        assertEquals(AccountFailure.SignedOut, old.await().exceptionOrNull())
        assertEquals("claimed", store.value?.access)
    }
    @Test fun linkOriginsProofsAndPushDestinationsAreConstrained() {
        val json = javaClass.getResourceAsStream("/links.json")!!.bufferedReader().use { it.readText() }
        val router = LinkRouter("https://public.test", json)
        val token = "a".repeat(32)
        assertEquals(LinkIntent.Claim(token, AccountDestination.DASHBOARD), router.route("https://public.test/account/claim#token=$token&next=/account/dashboard"))
        listOf("https://evil.test/account/dashboard", "http://public.test/account/profile", "https://user@public.test/account/profile", "https://public.test/account%2fclaim#token=$token", "https://public.test/account/claim#token=$token&next=https://evil.test", "https://public.test/account/claim#token=$token&token=$token").forEach { assertNull(it, router.route(it)) }
        assertEquals(LinkIntent.PartnerSignIn(token), router.route("https://public.test/partner/accept#token=$token"))
        listOf("https://public.test/partner/accept#token=$token&next=/account/dashboard", "https://public.test/partner/accept?x=1#token=$token",
            "https://public.test/partner/accept#token=short").forEach { assertNull(it, router.route(it)) }
        assertEquals(AccountDestination.PAYMENT_METHOD, router.notification("/account/payment-method"))
        assertEquals(AccountDestination.GIFTS, router.notification("/account/gifts"))
        assertNull(router.notification("https://public.test/account/dashboard"))
        assertTrue(router.route("https://public.test/r/friend?via=irl") is LinkIntent.Web)
        listOf(Referral.inviteLink("https://public.test", "friend"), Referral.inviteLink("https://public.test", "friend", inPerson = true))
            .forEach { assertTrue(it, router.route(it!!) is LinkIntent.Web) }
        assertTrue(json.contains("\"referral_prefix\": \"/r/\""))
        assertEquals("https://public.test/r/friend", Referral.inviteLink("https://public.test", "friend"))
        val gate = NavigationGate()
        assertNull(gate.receive(AccountDestination.DASHBOARD, false))
        assertEquals(AccountDestination.DASHBOARD, gate.signedIn())
        assertNull(gate.signedIn())
    }
}
