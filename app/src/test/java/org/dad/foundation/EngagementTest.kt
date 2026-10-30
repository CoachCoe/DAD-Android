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
        val out = RankingStanding(nationalPercentile = null, city = null, cityRank = null)
        assertTrue(out.isOptedOut)
        assertNull(out.nationalLabel)
        assertNull(out.cityLabel)
    }

    @Test fun rankingLabelsWhenOptedIn() {
        val standing = RankingStanding(nationalPercentile = 92, city = "Austin", cityRank = 4)
        assertFalse(standing.isOptedOut)
        assertEquals("Top 8% nationally", standing.nationalLabel)
        assertEquals("#4 in Austin", standing.cityLabel)
    }

    @Test fun topPercentileNeverReadsZero() {
        assertEquals("Top 1% nationally", RankingStanding(100, null, null).nationalLabel)
        assertEquals("Top 1% nationally", RankingStanding(99, null, null).nationalLabel)
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

    // --- Model state machine ---
    private class FakeEngagementService(
        var optedIn: Boolean = true,
        var failLoad: Boolean = false,
        var failToggle: Boolean = false,
    ) : EngagementService {
        private fun ranking() = if (optedIn) RankingStanding(92, "Austin", 4) else RankingStanding(null, null, null)
        override suspend fun snapshot(): EngagementSnapshot {
            if (failLoad) throw AccountFailure.SignedOut
            return EngagementSnapshot(
                today = TodayInitiative("park", "Park Cleanup", "Today your \$1 funded a cleanup", listOf(ImpactMetric("Bags", "40"))),
                impact = ImpactTotals(124_000, 3, 2),
                streak = Streak(12, atRisk = false),
                ranking = ranking(),
                spend = InitiativeSpend(500_000, listOf(SpendCategory("Food", 500_000))),
            )
        }
        override suspend fun setRankingOptIn(optIn: Boolean): RankingStanding {
            if (failToggle) throw AccountFailure.Unavailable
            optedIn = optIn
            return ranking()
        }
    }

    @Test fun loadReadySetsSnapshot() = runTest {
        val model = EngagementModel(FakeEngagementService())
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(EngagementPhase.Ready, model.phase)
        assertEquals(12, model.snapshot?.streak?.current)
        assertFalse(model.snapshot!!.ranking.isOptedOut)
    }

    @Test fun loadFailureSurfacesFailedPhase() = runTest {
        val model = EngagementModel(FakeEngagementService(failLoad = true))
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is EngagementPhase.Failed)
    }

    @Test fun optOutReplacesRankingInPlace() = runTest {
        val model = EngagementModel(FakeEngagementService(optedIn = true))
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        model.setRankingOptIn(false)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.snapshot!!.ranking.isOptedOut)
        // Still Ready — the rest of the snapshot is intact.
        assertEquals(EngagementPhase.Ready, model.phase)
        assertEquals(12, model.snapshot?.streak?.current)
    }

    @Test fun failedToggleKeepsTheHomeAndShowsAnInlineError() = runTest {
        val model = EngagementModel(FakeEngagementService(failToggle = true))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.setRankingOptIn(false); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(EngagementPhase.Ready, model.phase)
        assertFalse(model.snapshot!!.ranking.isOptedOut)
        assertTrue(model.actionError != null)
    }
}
