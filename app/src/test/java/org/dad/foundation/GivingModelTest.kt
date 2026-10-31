package org.dad.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class GivingModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val sub = "11111111-2222-4333-8444-555555555555"
    private val periodEnd = Date(1_800_000_000_000)
    private val active = RecurringState.Active(sub, 15000, periodEnd)

    /** A scriptable service: each operation answers from a mutable field, and every call is counted. */
    private class Fake(var state: RecurringState = RecurringState.None) : GivingService {
        var recurringError: Exception? = null
        var offer: AmountOffer? = null
        var payment = PaymentMethodState(PaymentMethodState.Kind.NONE)
        var paymentError: Exception? = null
        var changeError: Exception? = null
        var cancelError: Exception? = null
        var startError: Exception? = null
        var beginError: Exception? = null
        var startPath: PaymentPath = PaymentPath.WebCheckoutHandoff("https://checkout.stripe.com/c/pay/cs_1")
        var beginPath: PaymentPath = PaymentPath.WebCheckoutHandoff("https://billing.stripe.com/p/session/bps_1")
        var gate: CompletableDeferred<Unit>? = null
        var recurringReads = 0
        var changeCalls = 0
        var cancelCalls = 0
        var startCalls = 0
        var beginCalls = 0
        var startedAmount: Int? = null
        /** Called on every recurring read, so a test can have the "webhook" land partway through polling. */
        var onRecurring: (Int) -> Unit = {}

        override suspend fun recurring(): RecurringState {
            recurringReads++
            onRecurring(recurringReads)
            recurringError?.let { throw it }
            return state
        }
        override suspend fun amountOffer(): AmountOffer? = offer
        override suspend fun changeAmount(subscriptionId: String, amountMinor: Int): RecurringState {
            changeCalls++
            gate?.await()
            changeError?.let { throw it }
            return RecurringState.Active(subscriptionId, amountMinor, (state as RecurringState.Active).periodEnd).also { state = it }
        }
        override suspend fun cancel(subscriptionId: String, periodEnd: Date): RecurringState {
            cancelCalls++
            gate?.await()
            cancelError?.let { throw it }
            return RecurringState.Ending(subscriptionId, state.amountMinorOrNull ?: 0, periodEnd, periodEnd).also { state = it }
        }
        override suspend fun paymentMethod(): PaymentMethodState {
            paymentError?.let { throw it }
            return payment
        }
        override suspend fun beginPayment(): PaymentPath {
            beginCalls++
            gate?.await()
            beginError?.let { throw it }
            return beginPath
        }
        override suspend fun startGift(amountMinor: Int): PaymentPath {
            startCalls++
            startedAmount = amountMinor
            gate?.await()
            startError?.let { throw it }
            return startPath
        }
    }

    private fun TestScope.model(service: GivingService, attempts: Int = 5) = GivingModel(service, pollAttempts = attempts, pollDelayMs = 2_000).also { advanceUntilIdle() }

    private fun TestScope.loaded(service: Fake, attempts: Int = 5): GivingModel = model(service, attempts).also { it.load(); advanceUntilIdle() }

    // --- load ---

    @Test fun loadReadsTheGiftTheOfferAndThePaymentAndSyncsTheSelection() = runTest {
        val service = Fake(active).apply {
            offer = AmountOffer(sub, 30000, periodEnd, AmountOffer.Pending(60000, 30000, periodEnd, confirmed = true))
            payment = PaymentMethodState(PaymentMethodState.Kind.APPLIED, periodEnd)
        }
        val model = loaded(service)
        assertTrue(model.hasLoaded)
        assertEquals(GivingPhase.Idle, model.phase)
        assertEquals(60000, model.currentAmountMinor)         // a queued change wins over the subscription's own figure
        assertEquals(20, model.selection.dailyDollars)
        assertEquals(PaymentMethodState.Kind.APPLIED, model.payment?.kind)
    }

    @Test fun aFailedFirstReadFailsTheScreenAndALaterOneRecovers() = runTest {
        val service = Fake(active).apply { recurringError = AccountFailure.Unavailable }
        val model = loaded(service)
        assertFalse(model.hasLoaded)
        assertTrue(model.phase is GivingPhase.Failed)
        service.recurringError = null
        model.load(); advanceUntilIdle()
        assertTrue(model.hasLoaded)
        assertEquals(GivingPhase.Idle, model.phase)
        assertEquals(active, model.recurring)
    }

    @Test fun supportingReadFailuresDoNotFailTheScreen() = runTest {
        val service = Fake(active).apply { paymentError = AccountFailure.Unavailable }
        val model = loaded(service)
        assertTrue(model.hasLoaded)
        assertEquals(GivingPhase.Idle, model.phase)
        assertNull(model.payment)
    }

    @Test fun aRejectedSessionSurfacesAsSignedOut() = runTest {
        val model = loaded(Fake().apply { recurringError = AccountFailure.SignedOut })
        assertTrue(model.signedOut)
    }

    // --- start a gift ---

    @Test fun startingAGiftSendsTheSelectedAmountAndPresentsTheHostedPage() = runTest {
        val service = Fake()
        val model = loaded(service)
        model.setDaily(7)
        model.startGift(); advanceUntilIdle()
        assertEquals(21000, service.startedAmount)
        val handoff = model.handoff!!
        assertEquals("https://checkout.stripe.com/c/pay/cs_1", handoff.url)
        assertEquals(HostedHandoff.Purpose.START_GIFT, handoff.purpose)
        assertEquals(GivingPhase.Idle, model.phase)
    }

    @Test fun afterTheHostedPageClosesTheModelPollsUntilTheSubscriptionIsPresent() = runTest {
        val service = Fake()
        val model = loaded(service)
        model.startGift(); advanceUntilIdle()
        val reads = service.recurringReads
        // The webhook lands on the third poll.
        service.onRecurring = { n -> if (n == reads + 3) service.state = active }
        model.handoffFinished()
        assertTrue(model.isConfirming)
        assertNull("the page is no longer pending", model.handoff)
        advanceUntilIdle()
        assertFalse(model.isConfirming)
        assertEquals(reads + 3, service.recurringReads)
        assertEquals(active, model.recurring)
        assertEquals("Your gift is set up.", model.notice)
        assertEquals(5, model.selection.dailyDollars)
    }

    @Test fun theReturnPageProvesNothingSoAnUnpaidReturnEndsWithAnHonestNotice() = runTest {
        val service = Fake()
        val model = loaded(service)
        model.startGift(); advanceUntilIdle()
        val reads = service.recurringReads
        model.handoffFinished(); advanceUntilIdle()
        assertEquals("polling is bounded at 5 reads", reads + 5, service.recurringReads)
        assertEquals(RecurringState.None, model.recurring)
        assertTrue(model.notice!!.startsWith("We haven’t seen a new gift yet"))
        assertFalse(model.isConfirming)
    }

    @Test fun pollingWaitsBetweenReadsAndStopsOnceTheOutcomeIsKnown() = runTest {
        val service = Fake()
        val model = loaded(service, attempts = 3)
        model.startGift(); advanceUntilIdle()
        val reads = service.recurringReads
        model.handoffFinished()
        dispatcher.scheduler.runCurrent()
        assertEquals("the first read is immediate", reads + 1, service.recurringReads)
        dispatcher.scheduler.advanceTimeBy(1_999); dispatcher.scheduler.runCurrent()
        assertEquals(reads + 1, service.recurringReads)
        dispatcher.scheduler.advanceTimeBy(1); dispatcher.scheduler.runCurrent()
        assertEquals(reads + 2, service.recurringReads)
        advanceUntilIdle()
        assertEquals(reads + 3, service.recurringReads)
    }

    @Test fun closingTheHostedPageTwiceConfirmsOnce() = runTest {
        val service = Fake()
        val model = loaded(service)
        model.startGift(); advanceUntilIdle()
        val reads = service.recurringReads
        model.handoffFinished()
        model.handoffFinished()
        advanceUntilIdle()
        assertEquals(reads + 5, service.recurringReads)
    }

    @Test fun aTransientReadFailureWhilePollingDoesNotEndThePoll() = runTest {
        val service = Fake()
        val model = loaded(service)
        model.startGift(); advanceUntilIdle()
        val reads = service.recurringReads
        service.onRecurring = { n ->
            if (n == reads + 1) service.recurringError = AccountFailure.Unavailable
            if (n == reads + 2) { service.recurringError = null; service.state = active }
        }
        model.handoffFinished(); advanceUntilIdle()
        assertEquals(active, model.recurring)
        assertEquals("Your gift is set up.", model.notice)
    }

    @Test fun aSessionEndingWhilePollingStopsThePoll() = runTest {
        val service = Fake()
        val model = loaded(service)
        model.startGift(); advanceUntilIdle()
        val reads = service.recurringReads
        service.recurringError = AccountFailure.SignedOut
        model.handoffFinished(); advanceUntilIdle()
        assertTrue(model.signedOut)
        assertEquals(reads + 1, service.recurringReads)
        assertFalse(model.isConfirming)
    }

    @Test fun startingWhenAGiftExistsIsRefusedWithoutARequest() = runTest {
        val service = Fake(active)
        val model = loaded(service)
        model.startGift(); advanceUntilIdle()
        assertEquals(0, service.startCalls)
        assertTrue(model.phase is GivingPhase.Failed)
        assertNull(model.handoff)
    }

    @Test fun aDoubleTappedStartStartsOnce() = runTest {
        val service = Fake()
        val model = loaded(service)
        model.startGift()
        model.startGift()
        advanceUntilIdle()
        assertEquals(1, service.startCalls)
        assertNotNull(model.handoff)
    }

    @Test fun aConflictOnStartWithAnExistingGiftRereadsAndTellsTheDonor() = runTest {
        val service = Fake().apply { startError = AccountFailure.Conflict }
        val model = loaded(service)
        service.state = active   // the gift appeared (another device) after the screen last read
        model.startGift(); advanceUntilIdle()
        assertEquals(active, model.recurring)
        assertEquals("You already have a monthly gift. We’ve refreshed it.", (model.phase as GivingPhase.Failed).message)
        assertNull(model.handoff)
    }

    @Test fun aConflictOnStartWithAnOpenCheckoutSaysSoAndKeepsTheScreenUsable() = runTest {
        val service = Fake().apply { startError = AccountFailure.Conflict }
        val model = loaded(service)
        model.startGift(); advanceUntilIdle()
        assertEquals(RecurringState.None, model.recurring)
        assertTrue((model.phase as GivingPhase.Failed).message.contains("checkout is already open"))
        service.startError = null
        model.startGift(); advanceUntilIdle()
        assertNotNull("the donor can try again once it lapses", model.handoff)
    }

    @Test fun everyStartFailureHasItsOwnWords() = runTest {
        val expected = mapOf<Exception, String>(
            AccountFailure.Forbidden to "Giving isn’t available for this account.",
            AccountFailure.PaymentsUnavailable to "Payments aren’t available right now. Please try again later.",
            AccountFailure.RateLimited to "Too many requests. Please wait a moment and try again.",
            AccountFailure.InvalidInput to "Something went wrong. Please try again.",
            AccountFailure.Unavailable to "Something went wrong. Please try again.",
            AccountFailure.InvalidResponse to "Something went wrong. Please try again.",
            AccountFailure.SignedOut to "Please sign in again.",
        )
        for ((error, message) in expected) {
            val service = Fake().apply { startError = error }
            val model = loaded(service)
            model.startGift(); advanceUntilIdle()
            assertEquals(error.toString(), message, (model.phase as GivingPhase.Failed).message)
            assertNull(model.handoff)
        }
    }

    @Test fun cancellingAStartKeepsThePriorStateWithoutAnError() = runTest {
        val service = Fake().apply { startError = CancellationException("screen left") }
        val model = loaded(service)
        model.startGift(); advanceUntilIdle()
        assertEquals(GivingPhase.Idle, model.phase)
        assertEquals(RecurringState.None, model.recurring)
        assertNull(model.handoff)
        assertFalse(model.signedOut)
    }

    @Test fun theHostedPageCannotOpenIsAFailureNotASilentNothing() = runTest {
        val model = loaded(Fake())
        model.startGift(); advanceUntilIdle()
        model.handoffUnavailable()
        assertNull(model.handoff)
        assertTrue((model.phase as GivingPhase.Failed).message.contains("couldn’t open the secure payment page"))
    }

    // --- change amount ---

    @Test fun savingANewAmountChangesItAndSaysWhatTheNextRenewalWillBe() = runTest {
        val service = Fake(active)
        val model = loaded(service)
        model.setDaily(10)
        model.applySelection(); advanceUntilIdle()
        assertEquals(1, service.changeCalls)
        assertEquals(30000, (model.recurring as RecurringState.Active).amountMinor)
        assertEquals("Saved. Your next renewal will be $300.", model.notice)
        assertEquals(GivingPhase.Idle, model.phase)
    }

    @Test fun savingTheSameAmountSendsNothing() = runTest {
        val service = Fake(active)
        val model = loaded(service)
        model.applySelection(); advanceUntilIdle()
        assertEquals(0, service.changeCalls)
        assertEquals("That’s already your gift amount.", model.notice)
    }

    @Test fun aConflictOnChangeRereadsSoTheDonorReviewsCurrentFigures() = runTest {
        val service = Fake(active).apply { changeError = AccountFailure.Conflict }
        val model = loaded(service)
        model.setDaily(10)
        service.state = RecurringState.Active(sub, 60000, periodEnd)     // billing moved under the screen
        model.applySelection(); advanceUntilIdle()
        assertEquals(60000, (model.recurring as RecurringState.Active).amountMinor)
        assertEquals(20, model.selection.dailyDollars)
        assertTrue((model.phase as GivingPhase.Failed).message.startsWith("Your gift changed while you were looking at it"))
    }

    @Test fun changingAnEndingGiftOrNoGiftIsRefusedWithoutARequest() = runTest {
        val ending = Fake(RecurringState.Ending(sub, 15000, periodEnd, periodEnd))
        val endingModel = loaded(ending)
        endingModel.applySelection(); advanceUntilIdle()
        assertEquals(0, ending.changeCalls)
        assertTrue(endingModel.phase is GivingPhase.Failed)
        val none = Fake()
        val noneModel = loaded(none)
        noneModel.applySelection(); advanceUntilIdle()
        assertEquals(0, none.changeCalls)
        assertEquals("Set up a gift before changing the amount.", (noneModel.phase as GivingPhase.Failed).message)
    }

    @Test fun aDoubleTappedSaveChangesOnce() = runTest {
        val service = Fake(active).apply { gate = CompletableDeferred() }
        val model = loaded(service)
        model.setDaily(10)
        model.applySelection()
        model.applySelection()
        advanceUntilIdle()
        service.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, service.changeCalls)
    }

    @Test fun cancellingASaveKeepsThePriorStateAndSelection() = runTest {
        val service = Fake(active).apply { changeError = CancellationException("left") }
        val model = loaded(service)
        model.setDaily(10)
        model.applySelection(); advanceUntilIdle()
        assertEquals(active, model.recurring)
        assertEquals(GivingPhase.Idle, model.phase)
        assertNull(model.notice)
    }

    // --- cancel ---

    @Test fun cancellingTheGiftMovesItToEndingAndKeepsTheAmount() = runTest {
        val service = Fake(active)
        val model = loaded(service)
        model.cancel(); advanceUntilIdle()
        val ending = model.recurring as RecurringState.Ending
        assertEquals(15000, ending.amountMinor)
        assertEquals(GivingPhase.Idle, model.phase)
    }

    @Test fun aFailedCancelKeepsTheGiftActive() = runTest {
        val service = Fake(active).apply { cancelError = AccountFailure.Unavailable }
        val model = loaded(service)
        model.cancel(); advanceUntilIdle()
        assertEquals(active, model.recurring)
        assertTrue(model.phase is GivingPhase.Failed)
        service.cancelError = null
        model.cancel(); advanceUntilIdle()
        assertTrue(model.recurring.isEnding)
    }

    @Test fun aCancelCancelledMidFlightKeepsTheGiftActive() = runTest {
        val service = Fake(active).apply { cancelError = CancellationException("left") }
        val model = loaded(service)
        model.cancel(); advanceUntilIdle()
        assertEquals(active, model.recurring)
        assertEquals(GivingPhase.Idle, model.phase)
    }

    @Test fun aConflictOnCancelRereadsWhatBillingNowSays() = runTest {
        val service = Fake(active).apply { cancelError = AccountFailure.Conflict }
        val model = loaded(service)
        service.state = RecurringState.Ending(sub, 15000, periodEnd, periodEnd)   // cancelled on another device
        model.cancel(); advanceUntilIdle()
        assertTrue(model.recurring.isEnding)
        assertTrue((model.phase as GivingPhase.Failed).message.startsWith("Your gift changed"))
    }

    @Test fun cancellingWithNoActiveGiftIsRefused() = runTest {
        val service = Fake(RecurringState.Ending(sub, 15000, periodEnd, periodEnd))
        val model = loaded(service)
        model.cancel(); advanceUntilIdle()
        assertEquals(0, service.cancelCalls)
        assertEquals("There’s no active gift to cancel.", (model.phase as GivingPhase.Failed).message)
    }

    // --- payment method ---

    @Test fun updatingThePaymentMethodOpensTheHostedPageThenConfirmsTheResult() = runTest {
        val service = Fake(active)
        val model = loaded(service)
        model.updatePayment(); advanceUntilIdle()
        assertEquals(HostedHandoff.Purpose.UPDATE_PAYMENT, model.handoff!!.purpose)
        service.payment = PaymentMethodState(PaymentMethodState.Kind.OPEN)
        // The webhook lands on the second poll: the first read still sees the update in flight.
        val reads = service.recurringReads
        service.onRecurring = { n -> if (n == reads + 2) service.payment = PaymentMethodState(PaymentMethodState.Kind.APPLIED, periodEnd) }
        model.handoffFinished(); advanceUntilIdle()
        assertEquals("Your payment method was updated.", model.notice)
        assertEquals(PaymentMethodState.Kind.APPLIED, model.payment?.kind)
    }

    @Test fun anUpdateStillInFlightAfterTheBoundedPollSaysWeAreStillWaiting() = runTest {
        val service = Fake(active).apply { payment = PaymentMethodState(PaymentMethodState.Kind.OPEN) }
        val model = loaded(service)
        model.updatePayment(); advanceUntilIdle()
        model.handoffFinished(); advanceUntilIdle()
        assertTrue(model.notice!!.startsWith("We’re still waiting for the payment provider"))
    }

    @Test fun eachFinishedPaymentOutcomeHasItsOwnNotice() = runTest {
        val expected = mapOf(
            PaymentMethodState.Kind.CANCELLED to "The payment update was cancelled. Your payment method is unchanged.",
            PaymentMethodState.Kind.EXPIRED to "The payment update expired. Start again to update your payment method.",
            PaymentMethodState.Kind.NONE to "Your payment method is unchanged.",
        )
        for ((kind, notice) in expected) {
            val service = Fake(active).apply { payment = PaymentMethodState(kind) }
            val model = loaded(service)
            model.updatePayment(); advanceUntilIdle()
            model.handoffFinished(); advanceUntilIdle()
            assertEquals(kind.name, notice, model.notice)
        }
    }

    @Test fun aPaymentUpdateThatAlreadyFinishedOpensNothing() = runTest {
        val service = Fake(active).apply { beginPath = PaymentPath.AlreadyFinished(PaymentMethodState(PaymentMethodState.Kind.APPLIED, periodEnd)) }
        val model = loaded(service)
        model.updatePayment(); advanceUntilIdle()
        assertNull(model.handoff)
        assertEquals("Your payment method was updated.", model.notice)
        assertEquals(GivingPhase.Idle, model.phase)
    }

    @Test fun updatingThePaymentMethodNeedsAGift() = runTest {
        val service = Fake()
        val model = loaded(service)
        model.updatePayment(); advanceUntilIdle()
        assertEquals(0, service.beginCalls)
        assertTrue(model.phase is GivingPhase.Failed)
    }

    @Test fun aDoubleTappedUpdateOpensOnePage() = runTest {
        val service = Fake(active)
        val model = loaded(service)
        model.updatePayment()
        model.updatePayment()
        advanceUntilIdle()
        assertEquals(1, service.beginCalls)
    }

    @Test fun googlePayIsNotOfferedYet() = runTest {
        val service = Fake(active).apply { beginPath = PaymentPath.GooglePay }
        val model = loaded(service)
        model.updatePayment(); advanceUntilIdle()
        assertEquals("Google Pay isn’t available in this build yet.", (model.phase as GivingPhase.Failed).message)
    }

    // --- misc ---

    @Test fun tolerantStatusesFlowThroughToTheState() = runTest {
        val state = RecurringState.Active(sub, 15000, periodEnd, SubscriptionStatus.OTHER)
        val model = loaded(Fake(state))
        assertEquals(state, model.recurring)
        assertEquals(5, model.selection.dailyDollars)
    }

    @Test fun anAmountThatIsNotADailyStepLeavesTheSelectionAlone() = runTest {
        val model = loaded(Fake(RecurringState.Active(sub, 12345, periodEnd)))
        assertEquals(1, model.selection.dailyDollars)
        assertEquals(12345, model.currentAmountMinor)
    }

    @Test fun aPreparingGiftBlocksStartingAnotherAndCannotBeChanged() = runTest {
        val service = Fake(RecurringState.Preparing)
        val model = loaded(service)
        model.startGift(); advanceUntilIdle()
        model.applySelection(); advanceUntilIdle()
        assertEquals(0, service.startCalls)
        assertEquals(0, service.changeCalls)
    }
}
