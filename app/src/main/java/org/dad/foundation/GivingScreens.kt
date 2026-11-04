package org.dad.foundation

import android.content.ActivityNotFoundException
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

// Giving screens (PRD G-1..G-11, D-5): the recurring gift, its amount, the payment method and the cancel
// path, bound to [GivingModel]. The app never handles card data: starting a gift and updating the card both
// open a Stripe-hosted page in a Custom Tab, and when the donor comes back the model re-reads the server
// until it reflects the outcome. Every confirmation is inline (no OS dialog), so it is deterministic to test.

/**
 * Opens the hosted page for the model's pending [GivingModel.handoff] in a Custom Tab, and tells the model
 * when the donor comes back to the app. Under the DEBUG stub backend ([stubComplete] non-null) the page is
 * an in-app stand-in instead, because the stub origin cannot load. Returns true while the stand-in covers
 * the screen.
 */
@Composable
internal fun hostedPage(model: GivingModel, stubComplete: ((HostedHandoff.Purpose) -> Unit)?): Boolean {
    val context = LocalContext.current
    var openedId by rememberSaveable { mutableLongStateOf(-1L) }
    val handoff = model.handoff
    if (handoff != null && stubComplete == null) {
        LaunchedEffect(handoff.id) {
            if (openedId != handoff.id) {
                openedId = handoff.id
                try {
                    CustomTabsIntent.Builder().build().launchUrl(context, handoff.url.toUri())
                } catch (_: ActivityNotFoundException) {
                    model.handoffUnavailable()
                }
            }
        }
    }
    // The Custom Tab covers this activity, so the donor coming back (close, back, or the return page's own
    // close button) is this activity resuming. The return page proves nothing: the model polls the server.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val pending = model.handoff
        if (pending != null && stubComplete == null && openedId == pending.id) model.handoffFinished()
    }
    if (handoff != null && stubComplete != null) {
        StubHostedPage(handoff.purpose, onComplete = { stubComplete(handoff.purpose); model.handoffFinished() }, onClose = { model.handoffFinished() })
        return true
    }
    return false
}

/** Stands in for the Stripe-hosted page under the DEBUG stub backend. */
@Composable
private fun StubHostedPage(purpose: HostedHandoff.Purpose, onComplete: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("hosted-page-stub"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Secure payment page", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Text(if (purpose == HostedHandoff.Purpose.START_GIFT) "Pretend checkout for a monthly gift." else "Pretend card update.")
        DadPrimaryButton("Complete payment", Modifier.testTag("stub-complete")) { onComplete() }
        DadSecondaryButton("Close without paying", modifier = Modifier.testTag("stub-close")) { onClose() }
    }
}

/** The one-line gift state ("No monthly gift yet.", "$150/month · renews …", "…no further renewals",
 *  "Your gift isn’t available right now."). Shared with Home's monthly-gift row so both read identically. */
internal fun summaryLine(model: GivingModel): String {
    if (!model.hasLoaded) return if (model.phase is GivingPhase.Failed) "Your gift isn’t available right now." else "Loading your gift…"
    return when (val state = model.recurring) {
        RecurringState.None -> "No monthly gift yet."
        RecurringState.Preparing -> "Getting your first gift ready."
        is RecurringState.Active ->
            "${formatUsdMinor(state.amountMinor)}/month · renews ${shortDate(model.offer?.effectiveAt ?: state.periodEnd)}" +
                if (state.status.needsPaymentAttention) " · payment needs attention" else ""
        is RecurringState.Ending -> "${formatUsdMinor(state.amountMinor)}/month · ends ${shortDate(state.stopsAt)}, no further renewals"
    }
}

/**
 * Set / change your recurring gift (G-1/G-2/G-7), start one (D-5), update the card, or cancel (G-8/G-9).
 * Whole-dollar stepper $1…$30/day with the monthly ×30 echo and the streak bar; the cancel confirmation is
 * rendered INLINE (never an OS dialog or bottom sheet).
 */
@Composable
fun GiftScreen(model: GivingModel, stubComplete: ((HostedHandoff.Purpose) -> Unit)? = null, onClose: (() -> Unit)? = null, onSignedOut: () -> Unit = {}) {
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut() }
    if (hostedPage(model, stubComplete)) return
    var confirmingCancel by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("gift-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            giftHeading(model.hasLoaded, model.recurring),
            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading(),
        )
        if (!model.hasLoaded) LoadingOrFailed(model) else Loaded(model, confirmingCancel) { confirmingCancel = it }
    }
}

@Composable
private fun LoadingOrFailed(model: GivingModel) {
    val phase = model.phase
    if (phase is GivingPhase.Failed) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(phase.message, modifier = Modifier.testTag("gift-error"))
            DadPrimaryButton("Try again", Modifier.testTag("gift-retry")) { model.load() }
        }
    } else {
        Column(Modifier.testTag("gift-loading"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text("Loading your gift…")
        }
    }
}

@Composable
private fun Loaded(model: GivingModel, confirmingCancel: Boolean, onConfirmingCancel: (Boolean) -> Unit) {
    Status(model)
    val state = model.recurring
    if (state is RecurringState.Active || state == RecurringState.None) Stepper(model, startsGift = state == RecurringState.None)
    if (state.subscriptionIdOrNull != null) PaymentSection(model)
    Feedback(model)
    if (state is RecurringState.Active) CancelSection(model, state, confirmingCancel, onConfirmingCancel)
}

@Composable
private fun Status(model: GivingModel) {
    when (val state = model.recurring) {
        RecurringState.None -> HomeCard("gift-none-card") {
            Text("You don’t have a monthly gift yet.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("gift-none"))
            Text(
                "Choose an amount below. You’ll finish on a secure page hosted by our payment provider. This app never handles your card details.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        RecurringState.Preparing -> HomeCard("gift-preparing") {
            Text("We’re getting your first gift ready.", style = MaterialTheme.typography.titleMedium)
            Text("It can take a moment after checkout. Refresh to check again.", style = MaterialTheme.typography.bodySmall)
            DadSecondaryButton("Refresh", modifier = Modifier.testTag("gift-refresh"), enabled = !model.isBusy) { model.load() }
        }
        is RecurringState.Active, is RecurringState.Ending -> HomeCard("gift-status") {
            Text("${formatUsdMinor(state.amountMinorOrNull ?: 0)}/month", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("gift-monthly"))
            RenewalLines(model, state)
        }
    }
}

@Composable
private fun RenewalLines(model: GivingModel, state: RecurringState) {
    when (state) {
        is RecurringState.Active -> {
            val pending = model.offer?.pending
            val next = model.offer?.nextAmountMinor
            if (pending != null) {
                Text(
                    "Changing to ${formatUsdMinor(pending.amountMinor)}/month from ${shortDate(pending.effectiveAt)}${if (pending.confirmed) "" else ", awaiting confirmation"}.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("gift-pending-change"),
                )
            } else if (next != null && next != state.amountMinor) {
                Text(
                    "Your next renewal will be ${formatUsdMinor(next)} on ${shortDate(model.offer?.effectiveAt ?: state.periodEnd)}.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("gift-renewal"),
                )
            } else {
                Text("Renews ${shortDate(model.offer?.effectiveAt ?: state.periodEnd)}.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("gift-renewal"))
            }
            if (state.status.needsPaymentAttention) {
                Text("⚠ Your last renewal didn’t go through. Update your payment method to keep your gift going.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gift-payment-attention"))
            }
            if (state.status == SubscriptionStatus.INCOMPLETE) {
                Text("We’re waiting for your first payment to go through.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gift-incomplete"))
            }
        }
        is RecurringState.Ending ->
            Text("Your gift won’t renew — it stays active until ${shortDate(state.stopsAt)}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gift-ending"))
        else -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Stepper(model: GivingModel, startsGift: Boolean) {
    HomeCard("gift-stepper") {
        Text(model.selection.dailyLabel, style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("gift-daily"))
        Text("${model.selection.monthlyLabel} · charged monthly", style = MaterialTheme.typography.bodyLarge)
        // Wraps rather than squeezing a button to a sliver at large font scales.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The stepper pair is a connected secondary control; the single filled primary below
            // (start/save) is the screen's one high-emphasis action.
            DadSecondaryButton(
                "Decrease gift",
                modifier = Modifier.testTag("gift-decrease"),
                enabled = model.selection.dailyDollars > GiftAmount.MIN_DAILY_DOLLARS && !model.isBusy,
            ) { model.decrement() }
            DadSecondaryButton(
                "Increase gift",
                modifier = Modifier.testTag("gift-increase"),
                enabled = model.selection.dailyDollars < GiftAmount.MAX_DAILY_DOLLARS && !model.isBusy,
            ) { model.increment() }
        }
    }
    DadStreakBar(filled = model.selection.dailyDollars.coerceAtMost(30))
    if (startsGift) {
        DadPrimaryButton("Start a monthly gift", Modifier.testTag("start-gift"), enabled = !model.isBusy) { model.startGift() }
    } else {
        DadPrimaryButton("Save gift", Modifier.testTag("save-gift"), enabled = !model.isBusy) { model.applySelection() }
    }
}

@Composable
private fun PaymentSection(model: GivingModel) {
    HomeCard("payment-card") {
        Text("Payment method", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Text(paymentLine(model.payment), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("payment-status"))
        if (model.recurring is RecurringState.Active) {
            DadSecondaryButton("Update payment method", modifier = Modifier.testTag("update-payment"), enabled = !model.isBusy) { model.updatePayment() }
        }
    }
}

private fun paymentLine(payment: PaymentMethodState?): String {
    val idle = "Update your card on a secure page hosted by our payment provider."
    val state = payment ?: return idle
    return when (state.kind) {
        PaymentMethodState.Kind.NONE -> idle
        PaymentMethodState.Kind.PENDING, PaymentMethodState.Kind.OPEN -> "A card update is in progress."
        PaymentMethodState.Kind.APPLIED -> state.completedAt?.let { "Card updated ${shortDate(it)}." } ?: "Card updated."
        PaymentMethodState.Kind.EXPIRED -> "The last card update expired. You can start again."
        PaymentMethodState.Kind.CANCELLED -> "The last card update was cancelled. Your card is unchanged."
    }
}

@Composable
private fun Feedback(model: GivingModel) {
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model.phase == GivingPhase.Working) CircularProgressIndicator(Modifier.testTag("gift-working"))
        if (model.isConfirming) {
            Column(Modifier.testTag("gift-confirming"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text("Confirming with the payment provider…")
            }
        }
        model.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gift-notice")) }
        (model.phase as? GivingPhase.Failed)?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gift-failure")) }
    }
}

@Composable
private fun CancelSection(model: GivingModel, active: RecurringState.Active, confirming: Boolean, onConfirming: (Boolean) -> Unit) {
    if (confirming) {
        Column(Modifier.testTag("cancel-confirm"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cancel your gift?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
            Text(CancelCopy.confirmation(active.periodEnd))
            DadDestructiveTextButton("End my gift", modifier = Modifier.testTag("cancel-end"), enabled = !model.isBusy) { model.cancel(); onConfirming(false) }
            DadSecondaryButton("Keep giving", modifier = Modifier.testTag("cancel-keep")) { onConfirming(false) }
        }
    } else {
        DadSecondaryButton("Cancel gift", modifier = Modifier.testTag("gift-cancel"), enabled = !model.isBusy) { onConfirming(true) }
    }
}
