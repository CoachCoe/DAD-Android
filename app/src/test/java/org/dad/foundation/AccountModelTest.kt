package org.dad.foundation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Routing decisions of the composition root: restore, sign-in, revoked session, unconfigured build. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Api(var profileFailure: Exception? = null) : AccountAPI {
        override suspend fun profile(access: String): AccountProfile { profileFailure?.let { throw it }; return AccountProfile("a@b.c", "Alex") }
        override suspend fun refresh(token: String): Credentials = throw AccountFailure.SignedOut
        override suspend fun claim(token: String) = validCredentials("claimed")
        override suspend fun logout(access: String) = Unit
    }
    private fun model(api: Api = Api(), store: RecordingStore = RecordingStore()) =
        AccountModel({ scope, _ -> AppServices(AccountSession(api, store, scope), UnconfiguredSignIn, null) }, dispatcher)

    @Test fun aStoredSessionRestoresIntoTheDonorShell() = runTest {
        val m = model(store = RecordingStore(validCredentials()))
        m.start()
        assertTrue(m.booted)
        assertTrue(m.hasSession)
        assertEquals(AccountState.Ready(AccountProfile("a@b.c", "Alex")), m.state)
    }

    @Test fun noStoredSessionLandsOnSignInWithoutAnyProfileCall() = runTest {
        val m = model(api = Api(profileFailure = IllegalStateException("must not be called")))
        m.start()
        assertTrue(m.booted)
        assertFalse(m.hasSession)
        assertEquals(AccountState.SignedOut, m.state)
    }

    @Test fun aRevokedSessionReturnsToSignInWithAnExplanation() = runTest {
        val m = model(api = Api(AccountFailure.SignedOut), store = RecordingStore(validCredentials()))
        m.start()
        assertFalse(m.hasSession)
        assertEquals(AccountModel.SESSION_ENDED, m.notice)
    }

    @Test fun aTransientFailureOnAnExistingSessionStaysInTheShellWithRetry() = runTest {
        val api = Api(AccountFailure.Unavailable)
        val m = model(api = api, store = RecordingStore(validCredentials()))
        m.start()
        assertTrue(m.hasSession)
        assertEquals(AccountState.Unavailable, m.state)
        api.profileFailure = null
        m.load()
        assertTrue(m.state is AccountState.Ready)
    }

    @Test fun signingInEntersTheShellAndSignOutLeavesIt() = runTest {
        val store = RecordingStore()
        val m = model(store = store)
        m.start()
        val before = m.generation
        m.establish()({ validCredentials("fresh") })
        m.onSignedIn()
        assertTrue(m.hasSession)
        assertEquals("fresh", store.value?.access)
        assertTrue(m.state is AccountState.Ready)
        m.onSignedOut()
        assertFalse(m.hasSession)
        assertEquals(AccountState.SignedOut, m.state)
        assertTrue(m.generation > before)
    }

    @Test fun anUnconfiguredBuildShowsANoticeAndNeverServesSyntheticData() = runTest {
        val m = AccountModel({ _, _ -> throw IllegalStateException("DAD_API_ORIGIN must be set for a release build") }, dispatcher)
        m.start()
        assertTrue(m.booted)
        assertFalse(m.hasSession)
        assertEquals(PhoneSignInModel.NOT_CONFIGURED, m.notice)
        assertEquals(AccountState.Unavailable, m.state)
        assertSame(UnconfiguredSignIn, m.signIn)
        assertNull(m.settings)
        assertEquals(AccountFailure.InvalidConfiguration, runCatching { m.establish()({ validCredentials() }) }.exceptionOrNull())
    }

    @Test fun signupAndRecoveryLinksClaimNativelyAndOpenTheirReturnDestination() = runTest {
        val policy = javaClass.getResourceAsStream("/links.json")!!.bufferedReader().use { it.readText() }
        val router = LinkRouter("https://public.test", policy)
        for (target in listOf(AccountDestination.DASHBOARD, AccountDestination.PAYMENT_METHOD)) {
            val store = RecordingStore()
            val m = model(store = store)
            m.start(LaunchOptions(stubBackend = true))
            val link = "https://public.test/account/claim#token=" + "A".repeat(43) + "&next=" + target.path
            assertNull(m.receive(link, router))
            assertTrue(m.hasSession)
            assertEquals("claimed", store.value?.access)
            assertEquals(target, m.destination)
        }
    }

    @Test fun aFailedClaimLinkWithNoSessionExplainsItself() = runTest {
        val api = object : AccountAPI {
            override suspend fun profile(access: String) = AccountProfile("a@b.c", null)
            override suspend fun refresh(token: String): Credentials = throw AccountFailure.SignedOut
            override suspend fun claim(token: String): Credentials = throw AccountFailure.SignedOut
            override suspend fun logout(access: String) = Unit
        }
        val m = AccountModel({ scope, _ -> AppServices(AccountSession(api, RecordingStore(), scope), UnconfiguredSignIn, null) }, dispatcher)
        m.start()
        m.load(claim = "a".repeat(32))
        assertFalse(m.hasSession)
        assertEquals(AccountModel.LINK_FAILED, m.notice)
    }
}
