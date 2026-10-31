package org.dad.foundation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun prefs() = Preferences(
        version = 3, referralEmails = true, marketingEmails = false,
        pushReferralConverted = true, pushStreakAtRisk = true, pushStreakMilestone = true,
        pushStreakLapsed = true, pushBadgeEarned = true, pushEventNearby = false,
        pushEventReminder = true, pushEventPromoted = false, pushGiftProcessed = true,
        rankingsOptedOut = false, networkVisible = true, volunteer = true,
    )

    // --- Preferences value type ---
    @Test fun withFlipsTheRightToggle() {
        val p = prefs()
        assertTrue(p.with("marketing_emails", true).marketingEmails)
        assertFalse(p.with("push_event_reminder", false).pushEventReminder)
        // Unknown key is a no-op.
        assertEquals(p, p.with("nonexistent", true))
    }

    @Test fun pushTogglesExcludePaymentFailureAndCoverNineCategories() {
        // Payment-failure emails are non-toggleable (server-side) — must not appear here.
        val keys = prefs().pushToggles.map { it.key }
        assertEquals(9, keys.size)
        assertFalse(keys.any { it.contains("payment") })
        assertTrue(keys.contains("push_streak_at_risk"))
    }

    @Test fun showRankingInvertsOptedOut() {
        assertTrue(prefs().copy(rankingsOptedOut = false).showRanking)
        assertFalse(prefs().copy(rankingsOptedOut = true).showRanking)
    }

    // --- Delete-account gate ---
    @Test fun deleteGateRequiresExactWord() {
        assertFalse(DeleteAccountGate("delete").isConfirmed) // case-sensitive
        assertFalse(DeleteAccountGate("").isConfirmed)
        assertFalse(DeleteAccountGate("DELETE ").isConfirmed)
        assertTrue(DeleteAccountGate("DELETE").isConfirmed)
    }

    // --- Model ---
    private class FakeService(
        var failLoad: Boolean = false,
        var slowSave: Boolean = false,
        var signOutFails: Boolean = false,
        var deleteFailure: AccountFailure? = null,
    ) : SettingsService {
        var deleted = false
        var deleteCalls = 0
        var signedOut = false
        private var prefs = Preferences(
            version = 3, referralEmails = true, marketingEmails = false,
            pushReferralConverted = true, pushStreakAtRisk = true, pushStreakMilestone = true,
            pushStreakLapsed = true, pushBadgeEarned = true, pushEventNearby = false,
            pushEventReminder = true, pushEventPromoted = false, pushGiftProcessed = true,
            rankingsOptedOut = false, networkVisible = true, volunteer = true,
        )
        override suspend fun load(): SettingsSnapshot {
            if (failLoad) throw AccountFailure.SignedOut
            return SettingsSnapshot(ProfileSummary("d@x.com", "DONOR", "78701", "Austin", null), prefs)
        }
        override suspend fun savePreferences(preferences: Preferences): SettingsSnapshot {
            if (slowSave) yield()
            prefs = preferences.copy(version = preferences.version + 1)
            return SettingsSnapshot(ProfileSummary("d@x.com", "DONOR", "78701", "Austin", null), prefs)
        }
        override suspend fun deleteAccount() { deleteCalls++; deleteFailure?.let { throw it }; deleted = true }
        override suspend fun signOut() {
            if (signOutFails) throw AccountFailure.Unavailable
            signedOut = true
        }
    }

    @Test fun loadReadySetsSnapshot() = runTest {
        val model = SettingsModel(FakeService())
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SettingsPhase.Ready, model.phase)
        assertEquals("78701", model.snapshot?.profile?.zip)
    }

    @Test fun loadFailureSurfacesFailed() = runTest {
        val model = SettingsModel(FakeService(failLoad = true))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is SettingsPhase.Failed)
    }

    @Test fun toggleSavesAndBumpsVersion() = runTest {
        val model = SettingsModel(FakeService())
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, model.snapshot?.preferences?.version)
        model.toggle("marketing_emails", true); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(4, model.snapshot?.preferences?.version)
        assertTrue(model.snapshot!!.preferences.marketingEmails)
    }

    @Test fun deleteRequiresConfirmThenWipes() = runTest {
        val svc = FakeService()
        val model = SettingsModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        // Unconfirmed gate: no-op with an error, nothing deleted.
        model.deleteAccount(DeleteAccountGate("nope")); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(svc.deleted)
        assertFalse(model.signedOut)
        // Confirmed: deletes server-side then wipes local.
        model.deleteAccount(DeleteAccountGate("DELETE")); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(svc.deleted)
        assertTrue(svc.signedOut)
        assertTrue(model.signedOut)
    }

    @Test fun aBlockedDeletionIsFlaggedSoTheScreenCanOfferToCancelTheGift() = runTest {
        val svc = FakeService(deleteFailure = AccountFailure.DeletionBlocked)
        val model = SettingsModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.deleteAccount(DeleteAccountGate("DELETE")); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.deletionBlocked)
        assertTrue(model.actionError!!.contains("Cancel your monthly gift first"))
        assertFalse(model.signedOut)
        // Once the gift no longer renews the retry goes through, and the flag clears with it.
        svc.deleteFailure = null
        model.deleteAccount(DeleteAccountGate("DELETE")); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.deletionBlocked)
        assertTrue(svc.deleted)
        assertTrue(model.signedOut)
    }

    @Test fun otherDeleteFailuresAreNotFlaggedAsBlocked() = runTest {
        val model = SettingsModel(FakeService(deleteFailure = AccountFailure.Unavailable))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.deleteAccount(DeleteAccountGate("DELETE")); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.deletionBlocked)
        assertNotNull(model.actionError)
    }

    @Test fun signOutWipes() = runTest {
        val svc = FakeService()
        val model = SettingsModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.signOut(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(svc.signedOut)
        assertTrue(model.signedOut)
    }

    @Test fun rapidTogglesBothLandOnTheLatestSnapshot() = runTest {
        val model = SettingsModel(FakeService(slowSave = true))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.toggle("marketing_emails", true)
        model.toggle("push_event_nearby", true)
        dispatcher.scheduler.advanceUntilIdle()
        val saved = model.snapshot!!.preferences
        assertTrue(saved.marketingEmails)
        assertTrue(saved.pushEventNearby)
        assertEquals(5, saved.version)
    }

    @Test fun deleteSubmittedTwiceDeletesOnce() = runTest {
        val svc = FakeService()
        val model = SettingsModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.deleteAccount(DeleteAccountGate("DELETE"))
        model.deleteAccount(DeleteAccountGate("DELETE"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, svc.deleteCalls)
    }

    @Test fun deletedAccountLeavesSettingsEvenIfSignOutFails() = runTest {
        val svc = FakeService(signOutFails = true)
        val model = SettingsModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.deleteAccount(DeleteAccountGate("DELETE")); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(svc.deleted)
        assertTrue(model.signedOut)
    }

    @Test fun failedSignOutStaysAndCanRetry() = runTest {
        val svc = FakeService(signOutFails = true)
        val model = SettingsModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.signOut(); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.signedOut)
        assertTrue(model.actionError != null)
        svc.signOutFails = false
        model.signOut(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.signedOut)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDeletionWiringTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Service(var deleteFailure: Exception? = null) : SettingsService {
        val calls = mutableListOf<String>()
        override suspend fun load() = PreviewSettingsService().load()
        override suspend fun savePreferences(preferences: Preferences) = PreviewSettingsService().load()
        override suspend fun deleteAccount() { calls += "delete"; deleteFailure?.let { throw it } }
        override suspend fun signOut() { calls += "signOut" }
    }

    @Test fun aBlockedDeletionExplainsItAndStaysInSettings() = runTest {
        val service = Service(AccountFailure.DeletionBlocked)
        val model = SettingsModel(service)
        model.deleteAccount(DeleteAccountGate("DELETE"))
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.signedOut)
        assertTrue(model.actionError!!.contains("Cancel your monthly gift first"))
        assertEquals(listOf("delete"), service.calls)
        service.deleteFailure = null
        model.deleteAccount(DeleteAccountGate("DELETE"))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.signedOut)
    }

    @Test fun throttledDeletionAsksToWait() = runTest {
        val model = SettingsModel(Service(AccountFailure.RateLimited))
        model.deleteAccount(DeleteAccountGate("DELETE"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Too many attempts. Please wait a moment and try again.", model.actionError)
        assertFalse(model.signedOut)
    }

    @Test fun deletionServerFirstThenSignedOut() = runTest {
        val service = Service()
        val model = SettingsModel(service)
        model.deleteAccount(DeleteAccountGate("delete"))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(service.calls.isEmpty())
        model.deleteAccount(DeleteAccountGate("DELETE"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("delete", "signOut"), service.calls)
        assertTrue(model.signedOut)
    }
}
