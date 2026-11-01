package org.dad.foundation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

// Events screens (PRD E-1..E-10): the list, an event's detail with reserve / waitlist / cancel, check-in by
// typed or scanned code, add-to-calendar and report-an-issue, bound to [EventsModel]. Every confirmation is
// inline (no OS dialog) so it is deterministic to test. Scanning is a live CameraX + ML Kit viewfinder (see
// [ScanQrSection]); the camera permission is asked only when the donor taps "Scan QR code", and typing the code
// always works.

/** The events section: the list, with the open event's detail on top of it. */
@Composable
fun EventsScreen(model: EventsModel, onClose: (() -> Unit)? = null, onSignedOut: () -> Unit = {}) {
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> model.events.firstOrNull { it.id == id } }
    // An event that vanished (cancelled away, or hidden by the invite-only gate) closes its own detail.
    LaunchedEffect(selected == null, model.phase) {
        if (selectedId != null && selected == null && model.phase == EventsPhase.Ready) selectedId = null
    }
    if (selected != null) {
        BackHandler { selectedId = null; model.resetCheckIn() }
        EventDetail(model, selected, onBack = { selectedId = null; model.resetCheckIn() })
    } else {
        if (onClose != null) BackHandler(onBack = onClose)
        EventList(model, onClose, onOpen = { id -> selectedId = id; model.resetCheckIn(); model.clearOutcome(); model.clearActionError(); model.open(id) })
    }
}

@Composable
private fun EventList(model: EventsModel, onClose: (() -> Unit)?, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start).widthIn(max = 640.dp).fillMaxWidth().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("events-home"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onClose != null) OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("events-back")) { Text("Back") }
        Text("Events & volunteering", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        when (val phase = model.phase) {
            is EventsPhase.Loading -> CircularProgressIndicator(modifier = Modifier.testTag("events-loading"))
            is EventsPhase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(phase.message, modifier = Modifier.testTag("events-error"))
                DadPrimaryButton("Try again", Modifier.testTag("events-retry")) { model.load() }
            }
            is EventsPhase.Ready -> {
                model.actionError?.let { Text(it, modifier = Modifier.testTag("events-action-error").semantics { liveRegion = LiveRegionMode.Polite }) }
                if (model.events.isEmpty()) Text("No events right now. Check back soon.", modifier = Modifier.testTag("events-empty"))
                model.events.forEach { EventRow(it, model.now(), onOpen) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventRow(event: DonorEvent, now: Date, onOpen: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().testTag("event-${event.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(event.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Text(EventTime.start(event), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("event-start-${event.id}"))
        Text(listOf(event.venue, event.city).filter { it.isNotBlank() }.joinToString(", ") + " · " + event.spotsLabel, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // State in text, not colour alone.
            if (event.isInviteOnly) Text("Invite only", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("invite-only-${event.id}"))
            statusLabel(event, now)?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("event-status-${event.id}")) }
            if (event.earlyBooking && event.availability(now) is EventAvailability.Reservable && event.bookingOpensAt?.after(now) == true) {
                Text("Early booking", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("early-booking-${event.id}"))
            }
        }
        OutlinedButton(onClick = { onOpen(event.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("open-event-${event.id}")) { Text("View details") }
    }
}

private fun statusLabel(event: DonorEvent, now: Date): String? = when (event.availability(now)) {
    EventAvailability.CheckedIn -> "Checked in"
    EventAvailability.Reserved -> "You’re going"
    EventAvailability.Waitlisted -> "On the waitlist"
    EventAvailability.Cancelled -> "Cancelled"
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventDetail(model: EventsModel, event: DonorEvent, onBack: () -> Unit) {
    val now by produceState(model.now()) { while (true) { delay(30_000); value = model.now() } }
    val busy = event.id in model.busy
    var confirmingCancel by rememberSaveable(event.id) { mutableStateOf(false) }
    CalendarShare(model)
    Column(
        modifier = Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start).widthIn(max = 640.dp).fillMaxWidth().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("event-detail"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("event-back")) { Text("All events") }
        Text(event.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading().testTag("event-title"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(event.typeLabel, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("event-type"))
            if (event.isInviteOnly) Text("Invite only", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("invite-only-${event.id}"))
        }
        event.invitationNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("invitation-note")) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("When", style = MaterialTheme.typography.labelLarge)
            Text(EventTime.range(event), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("event-time"))
            Text("Where", style = MaterialTheme.typography.labelLarge)
            Text(
                listOf(event.venue, event.address, event.city).filter { it.isNotBlank() }.joinToString("\n"),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("event-place"),
            )
            Text(event.spotsLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("event-spots"))
        }
        if (event.details.isNotBlank()) Text(event.details, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("event-details"))

        model.actionError?.let { Text(it, modifier = Modifier.testTag("events-action-error").semantics { liveRegion = LiveRegionMode.Polite }) }
        model.lastOutcome?.let { outcome ->
            Column(modifier = Modifier.testTag("reserve-confirm").semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(outcome.confirmationTitle, style = MaterialTheme.typography.titleMedium)
                Text(outcome.confirmationDetail, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { model.clearOutcome() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
            }
        }

        when (val availability = event.availability(now)) {
            is EventAvailability.Reservable ->
                DadPrimaryButton(if (busy) "Reserving…" else "Reserve", Modifier.testTag("reserve-${event.id}")) { if (!busy) model.reserve(event.id) }
            EventAvailability.Waitlistable ->
                DadPrimaryButton(if (busy) "Joining…" else "Join waitlist", Modifier.testTag("waitlist-${event.id}")) { if (!busy) model.reserve(event.id) }
            is EventAvailability.NotYetOpen ->
                Text("Booking opens ${EventTime.at(event, availability.opensAt)}.", modifier = Modifier.testTag("not-yet-open-${event.id}"))
            EventAvailability.Started -> Text("This event has started, so it can’t be booked.", modifier = Modifier.testTag("started-${event.id}"))
            EventAvailability.Cancelled -> Text("This event was cancelled.", modifier = Modifier.testTag("cancelled-${event.id}"))
            EventAvailability.Reserved -> Text("You’re going. Your spot is reserved.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("going-${event.id}"))
            EventAvailability.Waitlisted -> Text("You’re on the waitlist.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("waitlisted-${event.id}"))
            EventAvailability.CheckedIn -> Text(
                "You’re checked in" + (event.checkedInAt?.let { " (${EventTime.at(event, it)})" } ?: "") + ".",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("checkedin-${event.id}"),
            )
        }

        if (event.canCancel(now)) {
            if (confirmingCancel) {
                Column(modifier = Modifier.testTag("cancel-confirm-${event.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (event.status == ReservationStatus.WAITLISTED) "Leave the waitlist?" else "Give up your spot?", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { confirmingCancel = false; model.cancel(event.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cancel-confirm-go")) {
                        Text(if (event.status == ReservationStatus.WAITLISTED) "Leave the waitlist" else "Give up my spot")
                    }
                    OutlinedButton(onClick = { confirmingCancel = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cancel-keep")) { Text("Keep it") }
                }
            } else {
                OutlinedButton(onClick = { confirmingCancel = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cancel-${event.id}")) {
                    Text(if (event.status == ReservationStatus.WAITLISTED) "Leave waitlist" else "Cancel my spot")
                }
            }
        }

        if (event.status == ReservationStatus.RESERVED && !event.cancelled) CheckInSection(model, event, now)

        if (event.status == ReservationStatus.RESERVED && !event.cancelled) AddToCalendar(model, event)

        event.reportIssueUrl?.let { url -> ReportIssue(url) }
    }
}

// MARK: check-in (E-8)

@Composable
private fun CheckInSection(model: EventsModel, event: DonorEvent, now: Date) {
    val state = model.checkIn
    Column(Modifier.fillMaxWidth().testTag("checkin-section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Check in", style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        if (event.checkedInAt != null) {
            Text("You’re already checked in.", modifier = Modifier.testTag("checkin-already"))
            return@Column
        }
        if (!event.canCheckIn(now)) {
            val closed = if (now.before(event.startsAt)) "Check-in opens when the event starts." else "Check-in for this event has closed."
            Text(closed, modifier = Modifier.testTag("checkin-closed"))
            return@Column
        }
        var code by rememberSaveable(event.id) { mutableStateOf("") }
        val checking = state is CheckInState.Checking
        Text("Enter the code shown at the event, or scan its QR code.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = code, onValueChange = { code = it.take(CheckInCode.LENGTH + 8); model.resetCheckIn() },
            label = { Text("Check-in code") }, singleLine = true, enabled = !checking,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().testTag("checkin-code"),
        )
        DadPrimaryButton(if (checking) "Checking in…" else "Check in", Modifier.testTag("checkin-submit")) { if (!checking) model.checkIn(event, code) }
        ScanQrSection(enabled = !checking, onToken = { model.checkIn(event, it) }, onScanStarted = { model.resetCheckIn() })
        when (state) {
            is CheckInState.Done -> Text(
                if (state.alreadyCheckedIn) "You were already checked in." else "You’re checked in. Thank you for coming.",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("checkin-result").semantics { liveRegion = LiveRegionMode.Polite },
            )
            is CheckInState.Failed -> Text(state.message, modifier = Modifier.testTag("checkin-error").semantics { liveRegion = LiveRegionMode.Polite })
            else -> Unit
        }
    }
}

// MARK: calendar (E-6)

@Composable
private fun AddToCalendar(model: EventsModel, event: DonorEvent) {
    val state = model.calendar
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { model.addToCalendar(event) }, enabled = state !is CalendarState.Fetching,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add-calendar"),
        ) { Text(if (state is CalendarState.Fetching) "Getting calendar file…" else "Add to calendar") }
        if (state is CalendarState.Failed) Text(state.message, modifier = Modifier.testTag("calendar-error").semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** Offers a fetched `.ics` through the share sheet via a FileProvider URI (no calendar permission), then clears it. */
@Composable
private fun CalendarShare(model: EventsModel) {
    val context = LocalContext.current
    val state = model.calendar
    LaunchedEffect(state) {
        if (state !is CalendarState.Ready) return@LaunchedEffect
        val file = withContext(Dispatchers.IO) { sharedFile(context, "calendar", "event.ics").also { it.writeBytes(state.bytes) } }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
            putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, fileAuthority(context), file))
            putExtra(Intent.EXTRA_SUBJECT, state.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(send, "Add to calendar").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            model.calendarShared()
        } catch (_: ActivityNotFoundException) {
            model.calendarUnavailable()
        }
    }
}

// MARK: report an issue (E-10)

@Composable
private fun ReportIssue(url: String) {
    val context = LocalContext.current
    val mailto = remember(url) { url.toUri().takeIf { it.scheme.equals("mailto", ignoreCase = true) } } ?: return
    var failed by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_SENDTO, mailto))
                } catch (_: ActivityNotFoundException) {
                    failed = true
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("report-issue"),
        ) { Text("Report an issue") }
        if (failed) Text("There’s no email app on this device. You can write to ${mailto.schemeSpecificPart.substringBefore('?')}.", modifier = Modifier.testTag("report-issue-error"))
    }
}

private fun fileAuthority(context: Context) = "${context.packageName}.files"

private fun sharedFile(context: Context, folder: String, name: String): File =
    File(File(context.cacheDir, "shared/$folder").apply { mkdirs() }, name)
