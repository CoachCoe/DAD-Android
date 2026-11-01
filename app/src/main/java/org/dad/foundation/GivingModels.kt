package org.dad.foundation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// Giving view model + the service seam. The model owns an explicit state machine driven through
// [GivingService], so start / change-amount / cancel / payment-method are unit-tested without a
// network, an Android framework or UI, exactly like the auth and attribution layers. The Kotlin
// counterpart of the iOS GivingModel.

/**
 * The payment path resolved for a request. The app never handles card data: the first charge is
 * backend-driven after the hosted page finishes, so this only decides which affordance the screen shows.
 */
sealed interface PaymentPath {
    /** Open the Stripe-hosted page (checkout or card setup) in a Custom Tab. */
    data class WebCheckoutHandoff(val url: String) : PaymentPath

    /** A payment update that finished before a new one could begin; nothing to open. */
    data class AlreadyFinished(val state: PaymentMethodState) : PaymentPath

    /** In-app Google Pay setup (gated behind nonprofit approval; not available in this build). */
    data object GooglePay : PaymentPath
}

/**
 * The seam the giving model calls. A conformer maps to the live API ([WireGiving]) or to a preview or test
 * double. Everything is value types so the model stays testable.
 */
interface GivingService {
    /** Current recurring gift (or [RecurringState.None]). */
    suspend fun recurring(): RecurringState

    /** What the next renewal charges and any queued amount change; null when the server cannot say. */
    suspend fun amountOffer(): AmountOffer?

    /** Change the monthly amount of an existing subscription; returns the resulting state. */
    suspend fun changeAmount(subscriptionId: String, amountMinor: Int): RecurringState

    /** Cancel at period end; returns the resulting (Ending) state. */
    suspend fun cancel(subscriptionId: String, periodEnd: Date): RecurringState

    /** Where the hosted payment-method update stands. */
    suspend fun paymentMethod(): PaymentMethodState

    /** Begin a payment-method update; returns which path to present. */
    suspend fun beginPayment(): PaymentPath

    /** Start a new monthly gift of [amountMinor] through the hosted checkout; returns which path to present. */
    suspend fun startGift(amountMinor: Int): PaymentPath
}

/**
 * What the giving screens need from the composition root. [stubComplete] exists only under the DEBUG stub
 * backend, where it stands in for the hosted page finishing (the Stripe webhook); it is always null in a
 * release build, which therefore always opens the real hosted page.
 */
class GivingKit(
    val service: GivingService,
    val stubComplete: ((HostedHandoff.Purpose) -> Unit)? = null,
    val pollDelayMs: Long = 2_000,
)

/** A hosted page the app has been asked to show. The app only opens it; it never sees card data. */
data class HostedHandoff(val url: String, val purpose: Purpose, val id: Long) {
    enum class Purpose { START_GIFT, UPDATE_PAYMENT }
}

/** Explicit phase so the UI never has to infer "is a request in flight". */
sealed interface GivingPhase {
    data object Idle : GivingPhase
    data object Working : GivingPhase
    data class Failed(val message: String) : GivingPhase
}

/**
 * Drives the gift screen and the home gift card. Holds a selected daily amount and the known recurring
 * state, and exposes the operations the screen can perform with explicit phases. Re-entrant calls while a
 * request is in flight are ignored (double-tap guard); a cancelled request keeps the prior state rather
 * than erroring.
 */
class GivingModel(
    private val service: GivingService,
    initial: GiftAmount = GiftAmount.ofDailyDollars(1)!!,
    private val pollAttempts: Int = 5,
    private val pollDelayMs: Long = 2_000,
) : ViewModel() {
    var recurring: RecurringState by mutableStateOf(RecurringState.None)
        private set
    var phase: GivingPhase by mutableStateOf(GivingPhase.Idle)
        private set

    /** The daily amount the stepper currently shows. Starts at the active amount when one exists. */
    var selection: GiftAmount by mutableStateOf(initial)
        private set
    var offer: AmountOffer? by mutableStateOf(null)
        private set
    var payment: PaymentMethodState? by mutableStateOf(null)
        private set

    /** Set when a hosted page should be shown; the screen clears it through [handoffFinished]. */
    var handoff: HostedHandoff? by mutableStateOf(null)
        private set

    /** A non-error line the screen shows (saved, still confirming...). */
    var notice: String? by mutableStateOf(null)
        private set

    /** The first read completed, so the screen can tell "loading" from "loaded, then a command failed". */
    var hasLoaded: Boolean by mutableStateOf(false)
        private set

    /** After a hosted page closes the model polls until the server reflects the outcome. */
    var isConfirming: Boolean by mutableStateOf(false)
        private set

    /** The server rejected the session; the screen leaves for sign-in. */
    var signedOut: Boolean by mutableStateOf(false)
        private set

    private var handoffCounter = 0L
    private val attempts = maxOf(pollAttempts, 1)

    /** True while a command or read is running; the screen disables its controls on this. */
    val isBusy: Boolean get() = phase == GivingPhase.Working || isConfirming

    /** What the donor's next renewal will charge: a queued change wins over the subscription's own figure. */
    val currentAmountMinor: Int? get() = offer?.pending?.amountMinor ?: offer?.nextAmountMinor ?: recurring.amountMinorOrNull

    /**
     * Clamp to the $1…$30 daily rule. Out-of-range requests are ignored at the edges rather than
     * erroring, so the stepper simply stops.
     */
    fun setDaily(dollars: Int) {
        val amount = GiftAmount.ofDailyDollars(maxOf(dollars, GiftAmount.MIN_DAILY_DOLLARS)) ?: return
        selection = amount
    }

    fun increment() = setDaily(selection.dailyDollars + 1)
    fun decrement() = setDaily(selection.dailyDollars - 1)
    fun dismissNotice() { notice = null }

    /**
     * Load the current recurring gift, the amount offer and the payment-method status, and sync the
     * selection to the gift. The recurring read is the one that can fail the screen; the other two are
     * supporting detail and fall back to "unknown".
     */
    fun load() {
        if (isBusy) return
        phase = GivingPhase.Working
        notice = null
        viewModelScope.launch {
            try {
                try {
                    recurring = service.recurring()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fail(error)
                    return@launch
                }
                readSupporting()
                syncSelection()
                hasLoaded = true
                phase = GivingPhase.Idle
            } catch (cancelled: CancellationException) {
                abandon()
                throw cancelled
            }
        }
    }

    /**
     * Apply the current selection to the existing subscription (G-7). A clear failure when there is no
     * subscription to change — the set-up-first case the UI gates on.
     */
    fun applySelection() {
        if (isBusy) return
        val id = recurring.subscriptionIdOrNull ?: run {
            phase = GivingPhase.Failed("Set up a gift before changing the amount.")
            return
        }
        if (recurring.isEnding) {
            phase = GivingPhase.Failed("Your gift is ending, so its amount can’t be changed.")
            return
        }
        if (selection.monthlyMinor == currentAmountMinor) {
            phase = GivingPhase.Idle
            notice = "That’s already your gift amount."
            return
        }
        phase = GivingPhase.Working
        notice = null
        viewModelScope.launch {
            try {
                try {
                    recurring = service.changeAmount(id, selection.monthlyMinor)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fail(error, rereadOnConflict = true)
                    return@launch
                }
                readSupporting()
                notice = "Saved. Your next renewal will be ${formatUsdMinor(currentAmountMinor ?: selection.monthlyMinor)}."
                phase = GivingPhase.Idle
            } catch (cancelled: CancellationException) {
                abandon()
                throw cancelled
            }
        }
    }

    /** Cancel at period end (G-8/G-9). Requires an active subscription with a known period end. */
    fun cancel() {
        if (isBusy) return
        val active = recurring as? RecurringState.Active ?: run {
            phase = GivingPhase.Failed("There’s no active gift to cancel.")
            return
        }
        phase = GivingPhase.Working
        notice = null
        viewModelScope.launch {
            try {
                try {
                    recurring = service.cancel(active.subscriptionId, active.periodEnd)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fail(error, rereadOnConflict = true)
                    return@launch
                }
                readSupporting()
                phase = GivingPhase.Idle
            } catch (cancelled: CancellationException) {
                abandon()
                throw cancelled
            }
        }
    }

    /** Start a new gift of the selected amount through the hosted checkout (D-5). The screen shows [handoff] next. */
    fun startGift() {
        if (isBusy) return
        if (recurring != RecurringState.None) {
            phase = GivingPhase.Failed("You already have a gift.")
            return
        }
        val amount = selection.monthlyMinor
        begin(HostedHandoff.Purpose.START_GIFT, startedFromConflict = true) { service.startGift(amount) }
    }

    /** Update the card through the hosted payment-method page. */
    fun updatePayment() {
        if (isBusy) return
        if (recurring.subscriptionIdOrNull == null) {
            phase = GivingPhase.Failed("Set up a gift before updating a payment method.")
            return
        }
        begin(HostedHandoff.Purpose.UPDATE_PAYMENT, startedFromConflict = false) { service.beginPayment() }
    }

    /**
     * The hosted page was closed (finished, cancelled or dismissed): the app cannot tell which — the
     * return page proves nothing — so poll the server until it reflects the outcome, bounded, then say
     * what it found.
     */
    fun handoffFinished() {
        val finished = handoff ?: return
        if (isConfirming) return
        handoff = null
        isConfirming = true
        notice = null
        viewModelScope.launch {
            try {
                var settled = false
                for (attempt in 0 until attempts) {
                    if (attempt > 0) delay(pollDelayMs)
                    if (!readAfterHandoff()) break
                    settled = when (finished.purpose) {
                        HostedHandoff.Purpose.START_GIFT -> recurring.subscriptionIdOrNull != null
                        HostedHandoff.Purpose.UPDATE_PAYMENT -> payment?.let { !it.isInFlight } ?: false
                    }
                    if (settled) break
                }
                syncSelection()
                hasLoaded = true
                notice = outcome(finished.purpose, settled, payment)
                isConfirming = false
            } catch (cancelled: CancellationException) {
                isConfirming = false
                throw cancelled
            }
        }
    }

    /** The hosted page could not be opened (no browser to show it): nothing was started, so say so. */
    fun handoffUnavailable() {
        handoff = null
        phase = GivingPhase.Failed("We couldn’t open the secure payment page. Check that a web browser is installed, then try again.")
    }

    // MARK: private

    private fun begin(purpose: HostedHandoff.Purpose, startedFromConflict: Boolean, open: suspend () -> PaymentPath) {
        phase = GivingPhase.Working
        notice = null
        viewModelScope.launch {
            try {
                try {
                    when (val path = open()) {
                        is PaymentPath.WebCheckoutHandoff -> handoff = HostedHandoff(path.url, purpose, ++handoffCounter)
                        is PaymentPath.AlreadyFinished -> {
                            payment = path.state
                            notice = outcome(HostedHandoff.Purpose.UPDATE_PAYMENT, true, path.state)
                        }
                        PaymentPath.GooglePay -> {
                            phase = GivingPhase.Failed("Google Pay isn’t available in this build yet.")
                            return@launch
                        }
                    }
                    phase = GivingPhase.Idle
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (startedFromConflict && error is AccountFailure.Conflict) {
                        // A gift or an open checkout already exists: re-read so the screen shows the truth, and say which.
                        reread()
                        phase = GivingPhase.Failed(
                            if (recurring != RecurringState.None) "You already have a monthly gift. We’ve refreshed it."
                            else "A checkout is already open for your account. Finish it, or try again after it expires in a few minutes.",
                        )
                    } else fail(error)
                }
            } catch (cancelled: CancellationException) {
                abandon()
                throw cancelled
            }
        }
    }

    /** One recurring + payment read during confirmation. False when the poll should stop (the session ended). */
    private suspend fun readAfterHandoff(): Boolean {
        try {
            recurring = service.recurring()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is AccountFailure.SignedOut) { signedOut = true; return false }
        }
        readSupporting()
        return !signedOut
    }

    private suspend fun readSupporting() {
        if (recurring.subscriptionIdOrNull != null) {
            try { offer = service.amountOffer() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { noteSoft(error) }
        } else {
            offer = null
        }
        try { payment = service.paymentMethod() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { noteSoft(error) }
    }

    private fun noteSoft(error: Exception) { if (error is AccountFailure.SignedOut) signedOut = true }

    private suspend fun reread() {
        try { recurring = service.recurring() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
        readSupporting()
        syncSelection()
    }

    private fun syncSelection() {
        currentAmountMinor?.let { minor -> GiftAmount.ofMonthlyMinor(minor)?.let { selection = it } }
    }

    private suspend fun fail(error: Exception, rereadOnConflict: Boolean = false) {
        if (error is AccountFailure.SignedOut) signedOut = true
        // Billing moved under the screen: pull the new truth in so the donor reviews current figures.
        if (rereadOnConflict && error is AccountFailure.Conflict) reread()
        phase = GivingPhase.Failed(message(error))
    }

    /** The work was abandoned (the screen left), not failed: the prior state stands. */
    private fun abandon() { if (phase == GivingPhase.Working) phase = GivingPhase.Idle }

    private fun message(error: Exception): String = when (error) {
        is AccountFailure.SignedOut -> "Please sign in again."
        is AccountFailure.Forbidden -> "Giving isn’t available for this account."
        is AccountFailure.Conflict -> "Your gift changed while you were looking at it. We’ve refreshed it; please review and try again."
        is AccountFailure.PaymentsUnavailable -> "Payments aren’t available right now. Please try again later."
        is AccountFailure.RateLimited -> "Too many requests. Please wait a moment and try again."
        else -> "Something went wrong. Please try again."
    }

    private fun outcome(purpose: HostedHandoff.Purpose, settled: Boolean, payment: PaymentMethodState?): String = when (purpose) {
        HostedHandoff.Purpose.START_GIFT ->
            if (settled) "Your gift is set up." else "We haven’t seen a new gift yet. If you finished checkout, it can take a moment to appear."
        HostedHandoff.Purpose.UPDATE_PAYMENT -> {
            val kind = payment?.kind
            if (!settled || kind == null) "We’re still waiting for the payment provider to confirm. Check back in a moment."
            else when (kind) {
                PaymentMethodState.Kind.APPLIED -> "Your payment method was updated."
                PaymentMethodState.Kind.CANCELLED -> "The payment update was cancelled. Your payment method is unchanged."
                PaymentMethodState.Kind.EXPIRED -> "The payment update expired. Start again to update your payment method."
                else -> "Your payment method is unchanged."
            }
        }
    }

    companion object {
        fun factory(kit: GivingKit): ViewModelProvider.Factory = viewModelFactory {
            initializer { GivingModel(kit.service, pollDelayMs = kit.pollDelayMs) }
        }

        /** ViewModel factory for the `preview_giving` launch path — drives a [PreviewGivingService]. */
        fun previewFactory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { GivingModel(PreviewGivingService(), pollDelayMs = 100) }
        }
    }
}

/** "Dec 15, 2026" in the device's time zone. */
internal fun shortDate(date: Date): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)

/**
 * In-app preview/test double for [GivingService]: starts with an active $1/day gift so the gift and
 * cancel surfaces both render without a network. [complete] plays the hosted page finishing.
 */
class PreviewGivingService : GivingService {
    private var state: RecurringState =
        RecurringState.Active("sub_preview", GiftAmount.CENTS_PER_DAILY_DOLLAR, Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000))
    private var applied = false

    override suspend fun recurring(): RecurringState = state
    override suspend fun amountOffer(): AmountOffer? = null
    override suspend fun changeAmount(subscriptionId: String, amountMinor: Int): RecurringState {
        val periodEnd = (state as? RecurringState.Active)?.periodEnd ?: Date()
        return RecurringState.Active(subscriptionId, amountMinor, periodEnd).also { state = it }
    }
    override suspend fun cancel(subscriptionId: String, periodEnd: Date): RecurringState {
        val amount = state.amountMinorOrNull ?: GiftAmount.CENTS_PER_DAILY_DOLLAR
        return RecurringState.Ending(subscriptionId, amount, periodEnd, Date(periodEnd.time)).also { state = it }
    }
    override suspend fun paymentMethod(): PaymentMethodState =
        PaymentMethodState(if (applied) PaymentMethodState.Kind.APPLIED else PaymentMethodState.Kind.NONE)
    override suspend fun beginPayment(): PaymentPath = PaymentPath.WebCheckoutHandoff("https://pay.example/setup")
    override suspend fun startGift(amountMinor: Int): PaymentPath = PaymentPath.WebCheckoutHandoff("https://pay.example/checkout")
}
