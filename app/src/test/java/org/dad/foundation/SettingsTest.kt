package org.dad.foundation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(p.with("marketing_emails", true).marketingEmails.let { !it })
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
    private class FakeService(var failLoad: Boolean = false) : SettingsService {
        var deleted = false
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
            prefs = preferences.copy(version = preferences.version + 1)
            return SettingsSnapshot(ProfileSummary("d@x.com", "DONOR", "78701", "Austin", null), prefs)
        }
        override suspend fun deleteAccount() { deleted = true }
        override suspend fun signOut() { signedOut = true }
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

    @Test fun signOutWipes() = runTest {
        val svc = FakeService()
        val model = SettingsModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.signOut(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(svc.signedOut)
        assertTrue(model.signedOut)
    }
}
