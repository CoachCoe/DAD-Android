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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
        DadPrimaryButton("Complete payment", Modifier.testTag("stub-complete"), onComplete)
        OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("stub-close")) { Text("Close without paying") }
    }
}

/** The donor's monthly gift at a glance (G-7..G-9); "Manage gift" opens the full gift screen. */
@Composable
fun GiftSummaryCard(model: GivingModel, onManage: () -> Unit) {
    HomeCard("gift-summary-card") {
        Text("Your monthly gift", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Text(summaryLine(model), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gift-summary"))
        OutlinedButton(onClick = onManage, modifier = Modifier.heightIn(min = 48.dp).testTag("open-gift")) { Text("Manage gift") }
    }
}

private fun summaryLine(model: GivingModel): String {
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
        if (onClose != null) OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("gift-close")) { Text("Close") }
        Text(
            if (model.hasLoaded && model.recurring == RecurringState.None) "Your gift" else "Set your gift",
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
            OutlinedButton(onClick = { model.load() }, enabled = !model.isBusy, modifier = Modifier.heightIn(min = 48.dp).testTag("gift-refresh")) { Text("Refresh") }
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
            Button(
                onClick = { model.decrement() }, enabled = model.selection.dailyDollars > GiftAmount.MIN_DAILY_DOLLARS && !model.isBusy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("gift-decrease"),
            ) { Text("Decrease gift") }
            Button(
                onClick = { model.increment() }, enabled = model.selection.dailyDollars < GiftAmount.MAX_DAILY_DOLLARS && !model.isBusy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("gift-increase"),
            ) { Text("Increase gift") }
        }
    }
    DadStreakBar(filled = model.selection.dailyDollars.coerceAtMost(30))
    if (startsGift) {
        Button(
            onClick = { model.startGift() }, enabled = !model.isBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("start-gift"),
        ) { Text("Start a monthly gift") }
    } else {
        Button(
            onClick = { model.applySelection() }, enabled = !model.isBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save-gift"),
        ) { Text("Save gift") }
    }
}

@Composable
private fun PaymentSection(model: GivingModel) {
    HomeCard("payment-card") {
        Text("Payment method", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Text(paymentLine(model.payment), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("payment-status"))
        if (model.recurring is RecurringState.Active) {
            OutlinedButton(onClick = { model.updatePayment() }, enabled = !model.isBusy, modifier = Modifier.heightIn(min = 48.dp).testTag("update-payment")) {
                Text("Update payment method")
            }
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
            OutlinedButton(onClick = { model.cancel(); onConfirming(false) }, enabled = !model.isBusy, modifier = Modifier.heightIn(min = 48.dp).testTag("cancel-end")) { Text("End my gift") }
            Button(onClick = { onConfirming(false) }, modifier = Modifier.heightIn(min = 48.dp).testTag("cancel-keep")) { Text("Keep giving") }
        }
    } else {
        OutlinedButton(onClick = { onConfirming(true) }, enabled = !model.isBusy, modifier = Modifier.heightIn(min = 48.dp).testTag("gift-cancel")) { Text("Cancel gift") }
    }
}
