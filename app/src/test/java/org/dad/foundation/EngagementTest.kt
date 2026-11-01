package org.dad.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class EngagementTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // --- Milestones (S-3) ---
    @Test fun nextMilestoneWalksTheLadderThenPlus365() {
        assertEquals(7, Milestones.next(0))
        assertEquals(30, Milestones.next(7))
        assertEquals(100, Milestones.next(31))
        assertEquals(1460, Milestones.next(1095))
        // Beyond the ladder: every +365.
        assertEquals(1825, Milestones.next(1460))
        assertEquals(1825, Milestones.next(1500))
        assertEquals(2190, Milestones.next(1825))
    }

    @Test fun lastReachedMilestone() {
        assertEquals(0, Milestones.lastReached(6))
        assertEquals(7, Milestones.lastReached(7))
        assertEquals(30, Milestones.lastReached(99))
        assertEquals(1460, Milestones.lastReached(1500))
        assertEquals(1825, Milestones.lastReached(1830))
    }

    // --- Streak (S-1, S-2, S-3) ---
    @Test fun streakBarFillsTrailing30() {
        assertEquals(12, Streak(12, atRisk = false).barFilled)
        assertEquals(30, Streak(45, atRisk = false).barFilled) // capped at 30
        assertEquals(0, Streak(0, atRisk = false).barFilled)
    }

    @Test fun streakLabelsAndMilestoneProgress() {
        val s = Streak(12, atRisk = true)
        assertEquals("12-day streak", s.countLabel)
        assertEquals(30, s.nextMilestone)
        assertEquals(18, s.daysToNext)
        assertEquals("18 days to your 30-day milestone", s.progressLabel)
        assertEquals("1-day streak", Streak(1, atRisk = false).countLabel)
    }

    // --- Local-midnight rollover by ZIP time zone (NFR §10) ---
    @Test fun rolloverIsLocalMidnightNotUtc() {
        val austin = ZoneId.of("America/Chicago") // UTC-5/-6
        // 2026-03-02T05:30Z is 2026-03-01 23:30 local (CST) — still the SAME local day as a
        // computation at 2026-03-01T18:00Z (12:00 local). No rollover yet.
        val computedAt = Instant.parse("2026-03-01T18:00:00Z")
        val sameLocalDay = Instant.parse("2026-03-02T05:30:00Z")
        assertFalse(StreakRollover.hasRolledOver(computedAt, sameLocalDay, austin))
        // 2026-03-02T06:30Z is 2026-03-02 00:30 local — a NEW local day. Rollover.
        val newLocalDay = Instant.parse("2026-03-02T06:30:00Z")
        assertTrue(StreakRollover.hasRolledOver(computedAt, newLocalDay, austin))
    }

    @Test fun rolloverDiffersByZone() {
        // Same two instants, different zones give different answers — proving it is zone-driven.
        val computedAt = Instant.parse("2026-06-01T23:00:00Z")
        val now = Instant.parse("2026-06-02T02:00:00Z")
        // UTC: 23:00 -> 02:00 next day = rolled over.
        assertTrue(StreakRollover.hasRolledOver(computedAt, now, ZoneId.of("UTC")))
        // Honolulu (UTC-10): 13:00 -> 16:00 same local day = not rolled over.
        assertFalse(StreakRollover.hasRolledOver(computedAt, now, ZoneId.of("Pacific/Honolulu")))
    }

    // --- Impact totals (I-1): unknown is "--", never $0 ---
    @Test fun impactUnknownRendersDashNotZero() {
        assertEquals("--", ImpactTotals(givenMinor = null, initiativesFunded = 0, eventsVolunteered = 0).givenLabel)
        assertEquals("$1,240", ImpactTotals(givenMinor = 124_000, initiativesFunded = 3, eventsVolunteered = 2).givenLabel)
        assertEquals("$12.34", ImpactTotals(givenMinor = 1234, initiativesFunded = 0, eventsVolunteered = 0).givenLabel)
    }

    // --- Rankings (K-2): opt-out hides position ---
    @Test fun rankingOptedOutShowsNothing() {
        val out = RankingStanding.optedOut()
        assertTrue(out.isOptedOut)
        assertNull(out.nationalLabel)
        assertNull(out.cityLabel)
    }

    @Test fun unrankedIsNotTheSameAsOptedOut() {
        // No figures yet (e.g. a new donor) must not be reported as an opt-out.
        val unranked = RankingStanding(nationalPercentile = null, city = null, cityRank = null)
        assertFalse(unranked.isOptedOut)
        assertNull(unranked.nationalLabel)
        assertNull(unranked.cityLabel)
    }

    @Test fun optedOutHidesFiguresEvenIfTheServerSentThem() {
        val standing = RankingStanding(2, "Austin", 4, optedOut = true)
        assertTrue(standing.isOptedOut)
        assertNull(standing.nationalLabel)
        assertNull(standing.cityLabel)
    }

    @Test fun dashboardFlagDrivesOptOutNotMissingFigures() {
        val unranked = RankingStanding.fromDashboard(null, null, null, rankingsOptedOut = false)
        assertFalse(unranked.isOptedOut) // opted in but not yet ranked
        val out = RankingStanding.fromDashboard(2, "Austin", 4, rankingsOptedOut = true)
        assertTrue(out.isOptedOut) // hides figures even if the server sent them
        assertNull(out.nationalLabel)
        assertNull(out.cityLabel)
        val ranked = RankingStanding.fromDashboard(2, "Austin", 4, rankingsOptedOut = false)
        assertEquals("#4 in Austin", ranked.cityLabel)
    }

    @Test fun rankingLabelsWhenOptedIn() {
        val standing = RankingStanding(nationalPercentile = 2, city = "Austin", cityRank = 4)
        assertFalse(standing.isOptedOut)
        // The server value is already the top bucket: 2 reads "Top 2%", not "Top 98%".
        assertEquals("Top 2% nationally", standing.nationalLabel)
        assertEquals("Top 1% nationally", RankingStanding(1, null, null).nationalLabel)
        assertEquals("Top 50% nationally", RankingStanding(50, null, null).nationalLabel)
        assertEquals("Top 98% nationally", RankingStanding(98, null, null).nationalLabel)
        assertEquals("#4 in Austin", standing.cityLabel)
    }

    @Test fun topPercentileNeverReadsZero() {
        assertEquals("Top 1% nationally", RankingStanding(0, null, null).nationalLabel)
        assertEquals("Top 1% nationally", RankingStanding(-3, null, null).nationalLabel)
    }

    // --- Today initiative (A-2): at most 4 headline metrics ---
    @Test fun headlineMetricsCappedAtFour() {
        val today = TodayInitiative("slug", "Title", "Summary", (1..6).map { ImpactMetric("m$it", "$it") })
        assertEquals(4, today.headlineMetrics.size)
    }

    // --- Spend-by-category (A-3): totals ---
    @Test fun spendTotalsFormat() {
        val spend = InitiativeSpend(totalMinor = 500_000, categories = listOf(SpendCategory("Food", 300_000), SpendCategory("Housing", 200_000)))
        assertEquals("$5,000", spend.totalLabel)
        assertEquals("$3,000", spend.categories.first().amountLabel)
    }

    @Test fun topPercentileNeverReadsAboveOneHundred() {
        assertEquals("Top 100% nationally", RankingStanding(120, null, null).nationalLabel)
    }

    // --- Model state machine ---
    private class FakeEngagementService(
        var optedIn: Boolean = true,
        var failLoad: Throwable? = null,
        var failToggle: Boolean = false,
        var failName: Throwable? = null,
        var needsName: Boolean = false,
    ) : EngagementService {
        var loads = 0
        var cancelOnToggle = false
        private fun ranking() = if (optedIn) RankingStanding(2, "Austin", 4) else RankingStanding.optedOut()
        override suspend fun snapshot(): EngagementSnapshot {
            loads++
            failLoad?.let { throw it }
            return EngagementSnapshot(
                impact = ImpactTotals(124_000, 3, 2),
                badges = BadgeGrid(listOf(Badge("F", "Founding Donor", "rule", "perk", earned = true, earnedAt = null))),
                ranking = ranking(),
                network = NetworkView(5, 348_000, null, emptyList(), 2),
                referral = if (needsName) null else ReferralInfo("ALEX", 2, 1, emptyList()),
                needsPublicName = needsName,
            )
        }
        override suspend fun setRankingOptIn(optIn: Boolean): RankingStanding {
            if (cancelOnToggle) throw CancellationException("cancelled")
            if (failToggle) throw AccountFailure.Unavailable
            optedIn = optIn
            return ranking()
        }
        override suspend fun choosePublicName(name: String) {
            failName?.let { throw it }
            needsName = false
        }
        override suspend fun initiativeSpend(id: String) = InitiativeSpend(1_000, listOf(SpendCategory("Food", 1_000)))
    }

    @Test fun loadReadySetsSnapshot() = runTest {
        val model = EngagementModel(FakeEngagementService())
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(EngagementPhase.Ready, model.phase)
        assertEquals("$1,240", model.snapshot?.impact?.givenLabel)
        assertFalse(model.snapshot!!.ranking!!.isOptedOut)
    }

    @Test fun loadFailureSurfacesFailedPhase() = runTest {
        val model = EngagementModel(FakeEngagementService(failLoad = AccountFailure.Unavailable))
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is EngagementPhase.Failed)
        assertFalse(model.signedOut)
    }

    @Test fun signedOutLoadRaisesTheSignedOutFlag() = runTest {
        val model = EngagementModel(FakeEngagementService(failLoad = AccountFailure.SignedOut))
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.signedOut)
    }

    @Test fun aFailedRefreshKeepsTheContentAndShowsANotice() = runTest {
        val service = FakeEngagementService()
        val model = EngagementModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        service.failLoad = AccountFailure.Unavailable
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(EngagementPhase.Ready, model.phase)
        assertTrue(model.snapshot != null)
        assertTrue(model.refreshNotice != null)
    }

    @Test fun aCancelledRefreshKeepsThePriorState() = runTest {
        val service = FakeEngagementService()
        val model = EngagementModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        service.failLoad = CancellationException("cancelled")
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(EngagementPhase.Ready, model.phase)
        assertTrue(model.snapshot != null)
        assertNull(model.refreshNotice)
    }

    @Test fun optOutReplacesRankingInPlace() = runTest {
        val model = EngagementModel(FakeEngagementService(optedIn = true))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.setRankingOptIn(false)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.snapshot!!.ranking!!.isOptedOut)
        assertEquals(EngagementPhase.Ready, model.phase)
        assertEquals("$1,240", model.snapshot?.impact?.givenLabel)
        assertNull(model.pendingOptIn)
    }

    @Test fun theToggleFlipsAtOnceAndOptingBackInRestoresTheStanding() = runTest {
        val model = EngagementModel(FakeEngagementService(optedIn = true))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.setRankingOptIn(false)
        assertTrue(model.displayedRanking!!.isOptedOut) // flipped before the save answers
        dispatcher.scheduler.advanceUntilIdle()
        model.setRankingOptIn(true)
        assertFalse(model.displayedRanking!!.isOptedOut)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Top 2% nationally", model.displayedRanking!!.nationalLabel)
    }

    @Test fun failedToggleRevertsAndShowsAnInlineError() = runTest {
        val model = EngagementModel(FakeEngagementService(failToggle = true))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.setRankingOptIn(false)
        assertTrue(model.displayedRanking!!.isOptedOut)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(EngagementPhase.Ready, model.phase)
        assertFalse(model.displayedRanking!!.isOptedOut)
        assertTrue(model.actionError != null)
    }

    @Test fun aCancelledToggleKeepsTheStandingWithoutAnError() = runTest {
        val service = FakeEngagementService().apply { cancelOnToggle = true }
        val model = EngagementModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.setRankingOptIn(false); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.displayedRanking!!.isOptedOut)
        assertNull(model.actionError)
        assertNull(model.pendingOptIn)
    }

    @Test fun choosingAPublicNameReloadsSoTheInviteAppears() = runTest {
        val service = FakeEngagementService(needsName = true)
        val model = EngagementModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.snapshot!!.needsPublicName)
        model.choosePublicName("  Alex C.  "); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.snapshot!!.needsPublicName)
        assertEquals("ALEX", model.snapshot!!.referral?.code)
        assertEquals(2, service.loads)
    }

    @Test fun blankOrOverlongNamesAreRefusedLocally() = runTest {
        val service = FakeEngagementService(needsName = true)
        val model = EngagementModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.choosePublicName("   ")
        assertTrue(model.nameError != null)
        model.choosePublicName("x".repeat(81))
        assertTrue(model.nameError != null)
        assertEquals(1, service.loads)
    }

    @Test fun rejectedNamesExplainThemselvesAndKeepTheScreen() = runTest {
        val service = FakeEngagementService(needsName = true, failName = AccountFailure.InvalidInput)
        val model = EngagementModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.choosePublicName("Alex"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("That name can’t be used. Try another.", model.nameError)
        assertTrue(model.snapshot!!.needsPublicName)
        assertFalse(model.isSavingName)
        service.failName = AccountFailure.Forbidden
        model.choosePublicName("Alex"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Invite links aren’t available for this account.", model.nameError)
    }

    @Test fun spendModelReadsPublishedUnpublishedAndFailed() = runTest {
        var failure: Throwable? = null
        val service = object : EngagementService {
            override suspend fun snapshot() = throw UnsupportedOperationException()
            override suspend fun setRankingOptIn(optIn: Boolean) = throw UnsupportedOperationException()
            override suspend fun choosePublicName(name: String) = throw UnsupportedOperationException()
            override suspend fun initiativeSpend(id: String): InitiativeSpend {
                failure?.let { throw it }
                return InitiativeSpend(500_000, listOf(SpendCategory("Food", 500_000)))
            }
        }
        val ready = InitiativeSpendModel(service, "id"); ready.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("$5,000", (ready.phase as InitiativeSpendModel.Phase.Ready).spend.totalLabel)
        failure = AccountFailure.NotFound
        val none = InitiativeSpendModel(service, "id"); none.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(InitiativeSpendModel.Phase.Unpublished, none.phase)
        failure = AccountFailure.Unavailable
        val failed = InitiativeSpendModel(service, "id"); failed.load(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(failed.phase is InitiativeSpendModel.Phase.Failed)
        failure = AccountFailure.SignedOut
        val out = InitiativeSpendModel(service, "id"); out.load(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(out.signedOut)
    }
}
