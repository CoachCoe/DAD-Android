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
import kotlinx.coroutines.launch

// Giving view models + the service seam. The model owns an explicit state machine driven
// through [GivingService], so set-gift / change-amount / cancel are unit-tested without a
// network, exactly like the auth/attribution layers.

/**
 * The payment path resolved for this build. The §10 / Risk-1 decision is "support both":
 * in-app Google Pay only once DAD is an approved-nonprofit for in-app donations, otherwise
 * hand off to the Stripe web checkout via Custom Tabs and return on the claim deep link. The
 * app never charges in-app directly in R1 — the first charge is backend-driven after payment
 * method setup — so this only decides which setup affordance the payment screen presents.
 */
sealed interface PaymentPath {
    /** Open the hosted Stripe checkout / setup in a Custom Tab and return via the deep link. */
    data class WebCheckoutHandoff(val url: String) : PaymentPath

    /** Present the in-app Google Pay setup sheet (gated behind nonprofit approval). */
    data object GooglePay : PaymentPath
}

/**
 * The seam the giving model calls. A conformer maps to the generated client (WireAPI) or to a
 * preview/test double. Everything is value types so the model stays testable.
 */
interface GivingService {
    /** Current recurring gift (or [RecurringState.None]). */
    suspend fun recurring(): RecurringState

    /** Change the monthly amount of an existing subscription; returns the resulting state. */
    suspend fun changeAmount(subscriptionId: String, amountMinor: Int): RecurringState

    /** Cancel at period end; returns the resulting (Ending) state. */
    suspend fun cancel(subscriptionId: String, periodEnd: java.util.Date): RecurringState

    /** Begin payment-method setup; returns which path to present. */
    suspend fun beginPayment(): PaymentPath
}

/** Explicit phase so the UI never has to infer "is a request in flight". */
sealed interface GivingPhase {
    data object Idle : GivingPhase
    data object Working : GivingPhase
    data class Failed(val message: String) : GivingPhase
}

/**
 * Drives the "set / change your gift" screen. Holds a selected daily amount and the known
 * recurring state, and exposes the operations the screen can perform with explicit phases.
 */
class GivingModel(
    private val service: GivingService,
    initial: GiftAmount = GiftAmount.ofDailyDollars(1)!!,
) : ViewModel() {
    var recurring: RecurringState by mutableStateOf(RecurringState.None)
        private set
    var phase: GivingPhase by mutableStateOf(GivingPhase.Idle)
        private set

    /**
     * The daily amount the stepper currently shows. Starts at the active amount when one
     * exists (synced in [load]), else the $1/day floor.
     */
    var selection: GiftAmount by mutableStateOf(initial)
        private set

    /**
     * Clamp to the $1…$30 daily rule. Out-of-range requests are ignored at the edges rather
     * than erroring, so the stepper simply stops.
     */
    fun setDaily(dollars: Int) {
        val amount = GiftAmount.ofDailyDollars(maxOf(dollars, GiftAmount.MIN_DAILY_DOLLARS)) ?: return
        selection = amount
    }

    fun increment() = setDaily(selection.dailyDollars + 1)
    fun decrement() = setDaily(selection.dailyDollars - 1)

    /** Load the current recurring gift and sync the selection to it. */
    fun load() {
        phase = GivingPhase.Working
        viewModelScope.launch {
            try {
                val state = service.recurring()
                recurring = state
                state.amountMinorOrNull?.let { minor ->
                    GiftAmount.ofMonthlyMinor(minor)?.let { selection = it }
                }
                phase = GivingPhase.Idle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                phase = GivingPhase.Failed(message(error))
            }
        }
    }

    /**
     * Apply the current selection to the existing subscription (G-7). No-op with a clear
     * failure when there is no subscription to change — the set-up-first case the UI gates on.
     */
    fun applySelection() {
        if (phase == GivingPhase.Working) return
        val id = recurring.subscriptionIdOrNull ?: run {
            phase = GivingPhase.Failed("Set up a gift before changing the amount.")
            return
        }
        phase = GivingPhase.Working
        viewModelScope.launch {
            try {
                recurring = service.changeAmount(id, selection.monthlyMinor)
                phase = GivingPhase.Idle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                phase = GivingPhase.Failed(message(error))
            }
        }
    }

    /** Cancel at period end (G-8/G-9). Requires an active subscription with a known period end. */
    fun cancel() {
        if (phase == GivingPhase.Working) return
        val active = recurring as? RecurringState.Active ?: run {
            phase = GivingPhase.Failed("There's no active gift to cancel.")
            return
        }
        phase = GivingPhase.Working
        viewModelScope.launch {
            try {
                recurring = service.cancel(active.subscriptionId, active.periodEnd)
                phase = GivingPhase.Idle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                phase = GivingPhase.Failed(message(error))
            }
        }
    }

    private fun message(error: Exception): String = when (error) {
        is AccountFailure.SignedOut -> "Please sign in again."
        else -> "Something went wrong. Please try again."
    }

    companion object {
        /** ViewModel factory for the `preview_giving` launch path — drives a [PreviewGivingService]. */
        fun previewFactory(): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { GivingModel(PreviewGivingService()) }
            }
    }
}

/**
 * In-app preview/test double for [GivingService]: starts with an active $1/day gift so the
 * set-gift and cancel surfaces both render without a network. Mirrors the iOS PreviewGiving.
 */
class PreviewGivingService : GivingService {
    private var state: RecurringState =
        RecurringState.Active("sub_preview", GiftAmount.CENTS_PER_DAILY_DOLLAR, java.util.Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000))

    override suspend fun recurring(): RecurringState = state
    override suspend fun changeAmount(subscriptionId: String, amountMinor: Int): RecurringState {
        val periodEnd = (state as? RecurringState.Active)?.periodEnd ?: java.util.Date()
        return RecurringState.Active(subscriptionId, amountMinor, periodEnd).also { state = it }
    }
    override suspend fun cancel(subscriptionId: String, periodEnd: java.util.Date): RecurringState {
        val amount = state.amountMinorOrNull ?: GiftAmount.CENTS_PER_DAILY_DOLLAR
        return RecurringState.Ending(subscriptionId, amount, periodEnd, java.util.Date(periodEnd.time)).also { state = it }
    }
    override suspend fun beginPayment(): PaymentPath = PaymentPath.WebCheckoutHandoff("https://pay.example/setup")
}
