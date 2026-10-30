package org.dad.foundation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class GivingTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // --- amount rule (G-1/G-2): $1…$30/day, ×30 to monthly cents ---
    @Test fun dailyAmountTimes30IsMonthlyCents() {
        val one = GiftAmount.ofDailyDollars(1)!!
        assertEquals(3000, one.monthlyMinor) // $1/day -> $30/mo -> 3000¢
        assertEquals("$1/day", one.dailyLabel)
        assertEquals("$30/mo", one.monthlyLabel)
        val five = GiftAmount.ofDailyDollars(5)!!
        assertEquals(15000, five.monthlyMinor)
        assertEquals("$150/mo", five.monthlyLabel)
    }

    @Test fun amountClampsToThirtyDollarCeiling() {
        val over = GiftAmount.ofDailyDollars(45)!!
        assertEquals(30, over.dailyDollars) // clamps, never wedges
        assertEquals(90000, over.monthlyMinor)
    }

    @Test fun nonPositiveDailyAmountIsRejected() {
        assertNull(GiftAmount.ofDailyDollars(0))
        assertNull(GiftAmount.ofDailyDollars(-3))
    }

    @Test fun monthlyMinorReconstructsOnlyExactSteps() {
        assertEquals(1, GiftAmount.ofMonthlyMinor(3000)!!.dailyDollars)
        assertEquals(30, GiftAmount.ofMonthlyMinor(90000)!!.dailyDollars)
        assertNull(GiftAmount.ofMonthlyMinor(2999))  // not a multiple of the step
        assertNull(GiftAmount.ofMonthlyMinor(100))   // below the floor
        assertNull(GiftAmount.ofMonthlyMinor(93000)) // above the ceiling
    }

    @Test fun formatUsdMinorDropsWholeDollarDecimals() {
        assertEquals("$30", formatUsdMinor(3000))
        assertEquals("$12.34", formatUsdMinor(1234))
        assertEquals("$150", formatUsdMinor(15000))
        // Thousands are grouped for readability (WCAG), matching formatMinorUsd.
        assertEquals("$1,240", formatUsdMinor(124_000))
        assertEquals("$12,345.67", formatUsdMinor(1_234_567))
    }

    // --- cancel copy (G-8/G-9) ---
    @Test fun cancelCopyNamesThePeriodEndAndStreakEnd() {
        val utc = TimeZone.getTimeZone("UTC")
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }
        val periodEnd = parser.parse("2026-12-15")!!
        val copy = CancelCopy.confirmation(periodEnd, utc, Locale.US)
        assertTrue(copy.contains("Dec 15, 2026"))
        assertTrue(copy.contains("your streak ends"))
    }

    // --- model state machine ---
    private class FakeGivingService(
        var state: RecurringState = RecurringState.None,
        var failLoad: Boolean = false,
    ) : GivingService {
        var changedTo: Int? = null
        var cancelledId: String? = null
        var cancelCalls = 0
        override suspend fun recurring(): RecurringState {
            if (failLoad) throw AccountFailure.Unavailable
            return state
        }
        override suspend fun changeAmount(subscriptionId: String, amountMinor: Int): RecurringState {
            changedTo = amountMinor
            return RecurringState.Active(subscriptionId, amountMinor, Date(0))
        }
        override suspend fun cancel(subscriptionId: String, periodEnd: Date): RecurringState {
            cancelledId = subscriptionId
            cancelCalls++
            return RecurringState.Ending(subscriptionId, 3000, periodEnd, Date(periodEnd.time + 1))
        }
        override suspend fun beginPayment(): PaymentPath = PaymentPath.WebCheckoutHandoff("https://pay.example/setup")
    }

    @Test fun loadSyncsSelectionToActiveAmount() = runTest {
        val service = FakeGivingService(RecurringState.Active("sub_1", 15000, Date(0)))
        val model = GivingModel(service)
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.recurring is RecurringState.Active)
        assertEquals(5, model.selection.dailyDollars) // 15000¢ -> $5/day
        assertEquals(GivingPhase.Idle, model.phase)
    }

    @Test fun loadFailureSurfacesFailedPhase() = runTest {
        val model = GivingModel(FakeGivingService(failLoad = true))
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is GivingPhase.Failed)
    }

    @Test fun stepperClampsAtBothEnds() {
        val model = GivingModel(FakeGivingService())
        model.decrement() // already at $1 floor
        assertEquals(1, model.selection.dailyDollars)
        repeat(40) { model.increment() }
        assertEquals(30, model.selection.dailyDollars) // $30 ceiling
    }

    @Test fun applySelectionWithoutSubscriptionFails() = runTest {
        val model = GivingModel(FakeGivingService(RecurringState.None))
        model.applySelection()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is GivingPhase.Failed)
    }

    @Test fun applySelectionChangesActiveAmount() = runTest {
        val service = FakeGivingService(RecurringState.Active("sub_1", 3000, Date(0)))
        val model = GivingModel(service)
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        model.setDaily(10)
        model.applySelection()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(30000, service.changedTo) // $10/day -> 30000¢
        assertEquals(GivingPhase.Idle, model.phase)
    }

    @Test fun cancelTransitionsActiveToEnding() = runTest {
        val service = FakeGivingService(RecurringState.Active("sub_1", 3000, Date(0)))
        val model = GivingModel(service)
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        model.cancel()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("sub_1", service.cancelledId)
        assertTrue(model.recurring.isEnding)
    }

    @Test fun cancelWithoutActiveGiftFails() = runTest {
        val model = GivingModel(FakeGivingService(RecurringState.None))
        model.cancel()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is GivingPhase.Failed)
    }

    @Test fun doubleTappedCancelCancelsOnce() = runTest {
        val service = FakeGivingService(RecurringState.Active("sub_1", 3000, Date(0)))
        val model = GivingModel(service)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.cancel()
        model.cancel()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, service.cancelCalls)
        assertTrue(model.recurring is RecurringState.Ending)
    }
}
