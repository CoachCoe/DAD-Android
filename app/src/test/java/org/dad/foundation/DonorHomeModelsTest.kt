package org.dad.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import java.time.OffsetDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class DonorHomeModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val now = OffsetDateTime.parse("2026-10-30T12:00:00Z")
    private fun readyDash(streak: HomeStreak? = HomeStreak(18, false, null, null)) = DashboardState.Ready(HomeDashboard(
        computedAt = now, foundingDonor = false, streak = streak, rank = RankLine.NotRankedYet,
        impact = ImpactTotals(124000L, 4, 2), network = null, referrals = null))
    private fun initiative(title: String = "Meals") = HomeInitiative("id", "slug", title, "s", "Austin", "PROGRAM", "2025-01-02", emptyList())
    private fun gift(i: Int) = HomeGift("g$i", 3000, now, true, 0, 3000)

    private class FakeService : DashboardService {
        var dashboardResult: () -> DashboardState = { DashboardState.Preparing }
        var initiativeResult: () -> HomeInitiative? = { null }
        var giftsResult: (Int) -> GiftHistoryPage = { GiftHistoryPage(emptyList(), null) }
        var receiptResult: (String) -> HomeReceipt = { throw AccountFailure.Unavailable }
        var summaryResult: () -> AnnualGivingSummary = { throw AccountFailure.InvalidInput }
        var giftGate: CompletableDeferred<Unit>? = null
        val giftOffsets = mutableListOf<Int>()
        override suspend fun dashboard() = dashboardResult()
        override suspend fun todayInitiative() = initiativeResult()
        override suspend fun gifts(offset: Int): GiftHistoryPage { giftOffsets += offset; giftGate?.await(); return giftsResult(offset) }
        override suspend fun receipt(giftId: String) = receiptResult(giftId)
        override suspend fun annualSummary(year: Int) = summaryResult()
    }

    private fun idle() = dispatcher.scheduler.advanceUntilIdle()

    // --- DashboardModel ---

    @Test fun loadShowsTheDashboardAndTheInitiative() = runTest {
        val service = FakeService().apply { dashboardResult = { readyDash() }; initiativeResult = { initiative() } }
        val model = DashboardModel(service)
        assertEquals(DashboardModel.Phase.Loading, model.phase)
        model.load(); idle()
        assertEquals(DashboardModel.Phase.Ready(readyDash()), model.phase)
        assertEquals(DashboardModel.InitiativePhase.Ready(initiative()), model.initiative)
        assertNull(model.refreshNotice)
    }

    @Test fun preparingAndNothingInRotationAreStatesNotErrors() = runTest {
        val model = DashboardModel(FakeService())
        model.load(); idle()
        assertEquals(DashboardModel.Phase.Ready(DashboardState.Preparing), model.phase)
        assertEquals(DashboardModel.InitiativePhase.None, model.initiative)
    }

    @Test fun aFailedFirstLoadIsFailedThenRetryRecovers() = runTest {
        var fail = true
        val service = FakeService().apply { dashboardResult = { if (fail) throw AccountFailure.Unavailable else readyDash() }; initiativeResult = { if (fail) throw AccountFailure.Unavailable else initiative() } }
        val model = DashboardModel(service)
        model.load(); idle()
        assertTrue(model.phase is DashboardModel.Phase.Failed)
        assertEquals(DashboardModel.InitiativePhase.Failed, model.initiative)
        fail = false
        model.load(); idle()
        assertTrue(model.phase is DashboardModel.Phase.Ready)
        assertTrue(model.initiative is DashboardModel.InitiativePhase.Ready)
    }

    @Test fun aFailedRefreshKeepsTheCardsOnScreenWithANotice() = runTest {
        var fail = false
        val service = FakeService().apply {
            dashboardResult = { if (fail) throw AccountFailure.Unavailable else readyDash() }
            initiativeResult = { if (fail) throw AccountFailure.Unavailable else initiative() }
        }
        val model = DashboardModel(service)
        model.load(); idle()
        fail = true
        model.load(); idle()
        assertEquals(DashboardModel.Phase.Ready(readyDash()), model.phase)
        assertEquals(DashboardModel.InitiativePhase.Ready(initiative()), model.initiative)
        assertTrue(model.refreshNotice!!.isNotBlank())
        fail = false
        model.load(); idle()
        assertNull(model.refreshNotice)
    }

    @Test fun aCancelledRefreshKeepsPriorStateAndShowsNoNotice() = runTest {
        var cancel = false
        val service = FakeService().apply {
            dashboardResult = { if (cancel) throw CancellationException("left") else readyDash() }
            initiativeResult = { initiative() }
        }
        val model = DashboardModel(service)
        model.load(); idle()
        cancel = true
        model.load(); idle()
        assertEquals(DashboardModel.Phase.Ready(readyDash()), model.phase)
        assertNull(model.refreshNotice)
        assertFalse(model.signedOut)
    }

    @Test fun aNewerLoadSupersedesAnOlderOne() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val service = object : DashboardService by FakeService() {
            override suspend fun dashboard(): DashboardState {
                val mine = ++calls
                if (mine == 1) gate.await()
                return if (mine == 1) DashboardState.Preparing else readyDash()
            }
        }
        val model = DashboardModel(service)
        model.load(); idle()
        model.load(); idle()
        gate.complete(Unit); idle()
        assertEquals(DashboardModel.Phase.Ready(readyDash()), model.phase)
    }

    @Test fun aRefusedSessionFlagsSignedOut() = runTest {
        val service = FakeService().apply { dashboardResult = { throw AccountFailure.SignedOut }; initiativeResult = { throw AccountFailure.SignedOut } }
        val model = DashboardModel(service)
        model.load(); idle()
        assertTrue(model.signedOut)
        assertEquals("Please sign in again.", (model.phase as DashboardModel.Phase.Failed).message)
    }

    @Test fun aRateLimitedReadSaysSo() = runTest {
        val service = FakeService().apply { dashboardResult = { throw AccountFailure.RateLimited } }
        val model = DashboardModel(service)
        model.load(); idle()
        assertTrue((model.phase as DashboardModel.Phase.Failed).message.contains("Too many"))
        assertFalse(model.signedOut)
    }

    @Test fun anInitiativeFailureDoesNotHideTheDashboard() = runTest {
        val service = FakeService().apply { dashboardResult = { readyDash() }; initiativeResult = { throw AccountFailure.Unavailable } }
        val model = DashboardModel(service)
        model.load(); idle()
        assertTrue(model.phase is DashboardModel.Phase.Ready)
        assertEquals(DashboardModel.InitiativePhase.Failed, model.initiative)
    }

    // --- GiftHistoryModel ---

    private fun pageOf(range: IntRange, next: Int?) = GiftHistoryPage(range.map(::gift), next)

    @Test fun historyLoadsTheFirstPageThenPagesToTheEnd() = runTest {
        val service = FakeService().apply {
            giftsResult = { offset -> when (offset) { 0 -> pageOf(0..49, 50); 50 -> pageOf(50..99, 100); else -> pageOf(100..119, null) } }
            summaryResult = { AnnualGivingSummary(2026, 3, 360000, 8500, 351500, false) }
        }
        val model = GiftHistoryModel(service, year = 2026)
        model.load(); idle()
        assertEquals(GiftHistoryModel.Phase.Ready, model.phase)
        assertEquals(50, model.gifts.size)
        assertTrue(model.hasMore)
        assertEquals("$3,515", model.summary?.netLabel)
        model.requestMore(); idle()
        assertEquals(100, model.gifts.size)
        model.requestMore(); idle()
        assertEquals(120, model.gifts.size)
        assertFalse(model.hasMore)
        assertNull(model.nextOffset)
        model.requestMore(); idle()
        assertEquals(listOf(0, 50, 100), service.giftOffsets)
    }

    @Test fun anEmptyHistoryIsEmptyNotAnError() = runTest {
        val model = GiftHistoryModel(FakeService(), year = 2026)
        model.load(); idle()
        assertTrue(model.isEmpty)
        assertNull(model.summary)
    }

    @Test fun aNonAdvancingOffsetEndsPagingInsteadOfLooping() = runTest {
        val service = FakeService().apply { giftsResult = { offset -> pageOf((offset..offset + 2), offset) } }
        val model = GiftHistoryModel(service, year = 2026)
        model.load(); idle()
        assertFalse(model.hasMore)
        assertNull(model.nextOffset)
        val service2 = FakeService().apply { giftsResult = { offset -> if (offset == 0) pageOf(0..2, 3) else pageOf(3..5, 2) } }
        val model2 = GiftHistoryModel(service2, year = 2026)
        model2.load(); idle()
        model2.requestMore(); idle()
        assertEquals(6, model2.gifts.size)
        assertFalse(model2.hasMore)
    }

    @Test fun rowsAreCappedAt500AndTheLimitIsReported() = runTest {
        val service = FakeService().apply { giftsResult = { offset -> pageOf(offset until offset + 50, offset + 50) } }
        val model = GiftHistoryModel(service, year = 2026)
        model.load(); idle()
        repeat(12) { model.requestMore(); idle() }
        assertEquals(GiftHistoryModel.MAX_ROWS, model.gifts.size)
        assertFalse(model.hasMore)
        assertTrue(model.reachedLimit)
        assertEquals(10, service.giftOffsets.size)
    }

    @Test fun aServerOverrunIsTruncatedToTheCap() = runTest {
        val service = FakeService().apply { giftsResult = { pageOf(0 until 600, 600) } }
        val model = GiftHistoryModel(service, year = 2026)
        model.load(); idle()
        assertEquals(500, model.gifts.size)
    }

    @Test fun duplicateRowsFromTheServerAreDroppedOnAppend() = runTest {
        val service = FakeService().apply { giftsResult = { offset -> if (offset == 0) pageOf(0..2, 3) else pageOf(2..4, null) } }
        val model = GiftHistoryModel(service, year = 2026)
        model.load(); idle()
        model.requestMore(); idle()
        assertEquals(listOf("g0", "g1", "g2", "g3", "g4"), model.gifts.map { it.id })
    }

    @Test fun cancelStopsAPageInFlightAndKeepsLoadedRows() = runTest {
        val service = FakeService().apply { giftsResult = { offset -> if (offset == 0) pageOf(0..49, 50) else pageOf(50..99, null) } }
        val model = GiftHistoryModel(service, year = 2026)
        model.load(); idle()
        service.giftGate = CompletableDeferred()
        model.requestMore(); idle()
        assertTrue(model.isLoadingMore)
        model.cancel()
        service.giftGate!!.complete(Unit); idle()
        assertEquals(50, model.gifts.size)
        assertFalse(model.isLoadingMore)
        assertNull(model.moreError)
        // The screen comes back: the next page can be requested again.
        service.giftGate = null
        model.requestMore(); idle()
        assertEquals(100, model.gifts.size)
    }

    @Test fun aFailedFirstPageOffersRetryAndAFailedLaterPageKeepsRows() = runTest {
        var fail = true
        val service = FakeService().apply { giftsResult = { offset -> if (fail) throw AccountFailure.Unavailable else if (offset == 0) pageOf(0..49, 50) else throw AccountFailure.RateLimited } }
        val model = GiftHistoryModel(service, year = 2026)
        model.load(); idle()
        assertTrue(model.phase is GiftHistoryModel.Phase.Failed)
        fail = false
        model.load(); idle()
        assertEquals(GiftHistoryModel.Phase.Ready, model.phase)
        model.requestMore(); idle()
        assertEquals(50, model.gifts.size)
        assertTrue(model.moreError!!.contains("Too many"))
        assertTrue(model.hasMore)
    }

    @Test fun aSignedOutPageFlagsTheSession() = runTest {
        val service = FakeService().apply { giftsResult = { throw AccountFailure.SignedOut } }
        val model = GiftHistoryModel(service, year = 2026)
        model.load(); idle()
        assertTrue(model.signedOut)
    }

    @Test fun aMissingYearSummaryJustHidesTheCard() = runTest {
        val service = FakeService().apply { giftsResult = { pageOf(0..1, null) } }
        val model = GiftHistoryModel(service, year = 2026)
        model.load(); idle()
        assertEquals(GiftHistoryModel.Phase.Ready, model.phase)
        assertNull(model.summary)
    }

    @Test fun receiptsShowHideAndReportFailure() = runTest {
        val receipt = HomeReceipt(gift(1), listOf(HomeRefund("re", 500, "succeeded", now)), false, now)
        var fail = false
        val service = FakeService().apply { giftsResult = { pageOf(0..1, null) }; receiptResult = { if (fail) throw AccountFailure.InvalidInput else receipt } }
        val model = GiftHistoryModel(service, year = 2026)
        model.load(); idle()
        model.showReceipt("g1")
        assertEquals(GiftHistoryModel.ReceiptPhase.Loading("g1"), model.receipt)
        idle()
        assertEquals(GiftHistoryModel.ReceiptPhase.Ready(receipt), model.receipt)
        model.hideReceipt()
        assertNull(model.receipt)
        fail = true
        model.showReceipt("g1"); idle()
        assertTrue(model.receipt is GiftHistoryModel.ReceiptPhase.Failed)
    }

    @Test fun unknownMoneyFormatsViaTheSharedFormatter() {
        assertEquals("$30", gift(1).amountLabel)
        assertEquals("$5 refunded", gift(1).copy(refundedMinor = 500).refundLabel)
        assertNull(gift(1).refundLabel)
        assertEquals("$1,234.56", formatMinorUsd(123456))
        assertEquals("1 person", HomeMetric("a", 1, HomeMetric.Unit.PEOPLE, "d").valueLabel)
        assertEquals("1-day streak", HomeStreak(1, false, null, null).headline)
        assertEquals("1 day to your 7-day milestone", HomeLadder(6, 0, 7, 1, emptyList()).nextLabel)
        assertNull(HomeLadder(2000, 1460, null, null, emptyList()).nextLabel)
    }

    @Test fun headlineMetricsAreCappedAtFourAndTheNoteStatesAttribution() {
        val many = (1..6).map { HomeMetric("m$it", it.toLong(), null, "d") }
        assertEquals(4, initiative().copy(metrics = many).headlineMetrics.size)
        assertTrue(HomeInitiative.ATTRIBUTION_NOTE.contains("not designated giving"))
    }
}
