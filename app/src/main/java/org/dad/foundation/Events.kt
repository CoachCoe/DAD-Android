package org.dad.foundation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

// Donor events & volunteering (PRD E-1..E-10, §6.11). Pure value types and the display /
// eligibility rules live here so event availability, the check-in window, invite-only gating and
// reservation transitions are unit-testable without a network, a camera, or UI — the Kotlin
// counterpart of the iOS Phase E events domain. The server is always authoritative for
// reservation and check-in; these are the client-side pre-checks that drive what the UI offers.

// MARK: - Reservation status (E-3, E-4)

/** Where the signed-in donor stands on one event, mirroring the contract's reservation_status. */
enum class ReservationStatus {
    NONE, RESERVED, WAITLISTED, CHECKED_IN;

    companion object {
        /** Decode defensively — an unknown future value becomes NONE rather than failing the list. */
        fun from(raw: String): ReservationStatus = when (raw.lowercase()) {
            "reserved" -> RESERVED
            "waitlisted" -> WAITLISTED
            "checked_in" -> CHECKED_IN
            else -> NONE
        }
    }
}

// MARK: - Availability (E-2, E-3, E-4)

/**
 * What the detail screen's primary action should offer, derived from capacity, the donor's
 * standing, and the booking window. A pure function of the event, so the reserve/waitlist/full/
 * not-yet-open decision is testable.
 */
sealed interface EventAvailability {
    data class NotYetOpen(val opensAt: Date) : EventAvailability
    /** The event has started and the donor holds nothing: the server refuses a new reservation (409). */
    data object Started : EventAvailability
    data class Reservable(val spotsLeft: Int) : EventAvailability
    data object Waitlistable : EventAvailability
    data object Reserved : EventAvailability
    data object Waitlisted : EventAvailability
    data object CheckedIn : EventAvailability
    data object Cancelled : EventAvailability
}

// MARK: - Event (list/detail model)

/**
 * One event as the list and detail screens render it. Independent of the generated client so the
 * domain + tests never import transport types; the service seam maps across.
 */
data class DonorEvent(
    val id: String,
    val slug: String,
    val title: String,
    val type: String,
    val venue: String,
    val city: String,
    val startsAt: Date,
    val endsAt: Date,
    val capacity: Int,
    val spotsLeft: Int,
    val status: ReservationStatus,
    /** Present only for invite-only events the donor can see (E-5); null = a public event. */
    val invitationNote: String? = null,
    val reportIssueUrl: String? = null,
    val cancelled: Boolean = false,
    val bookingOpensAt: Date? = null,
    val earlyBooking: Boolean = false,
    /** IANA zone of the venue (E-3): times are shown in this zone, not the device's. */
    val timeZone: String = "UTC",
    /** When the donor checked in (E-8); null until they do. A checked-in donor still holds a RESERVED seat. */
    val checkedInAt: Date? = null,
    val details: String = "",
    val address: String = "",
) {
    /** Invite-only (E-5): the contract's INVITE_ONLY type, or an invitation note. */
    val isInviteOnly: Boolean get() = invitationNote != null || type.equals("invite_only", ignoreCase = true)

    /** The contract's `capacity` is 0 for an uncapped event (and `spots_left` is then null). */
    val isUncapped: Boolean get() = capacity == 0

    val isCheckedIn: Boolean get() = status == ReservationStatus.CHECKED_IN || (status == ReservationStatus.RESERVED && checkedInAt != null)

    /** "Volunteer" / "Community" / "Invite-only"; a kind this build does not know is shown capitalised, never dropped. */
    val typeLabel: String
        get() = when (type.lowercase()) {
            "volunteer" -> "Volunteer"
            "community" -> "Community"
            "invite_only" -> "Invite-only"
            "" -> "Event"
            else -> type.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        }

    /** A reservation or waitlist place can be given up until the event starts (E-9); a cancelled event has none. */
    fun canCancel(now: Date): Boolean =
        !cancelled && !isCheckedIn && (status == ReservationStatus.RESERVED || status == ReservationStatus.WAITLISTED) && now.before(startsAt)

    val checkInWindow: CheckInWindow get() = CheckInWindow(startsAt, endsAt)

    /**
     * Check-in is offered only for a held (not waitlisted) seat, only between start and end (E-8), and only
     * until the donor has checked in. The server stays authoritative.
     */
    fun canCheckIn(now: Date): Boolean =
        !cancelled && status == ReservationStatus.RESERVED && checkedInAt == null && checkInWindow.isOpen(now)

    /** The primary-action decision (E-3/E-4/E-9). [now] is injected so tests are deterministic. */
    fun availability(now: Date): EventAvailability {
        if (cancelled) return EventAvailability.Cancelled
        return when (status) {
            ReservationStatus.CHECKED_IN -> EventAvailability.CheckedIn
            ReservationStatus.RESERVED -> if (checkedInAt != null) EventAvailability.CheckedIn else EventAvailability.Reserved
            ReservationStatus.WAITLISTED -> EventAvailability.Waitlisted
            ReservationStatus.NONE -> {
                val opens = bookingOpensAt
                if (!now.before(startsAt)) EventAvailability.Started
                // Early booking (B-3): a Team Captain may reserve before bookings open to everyone.
                else if (opens != null && now.before(opens) && !earlyBooking) EventAvailability.NotYetOpen(opens)
                else if (isUncapped) EventAvailability.Reservable(Int.MAX_VALUE)
                else if (spotsLeft > 0) EventAvailability.Reservable(spotsLeft)
                else EventAvailability.Waitlistable
            }
        }
    }

    /** "3 spots left" / "Full" / "Cancelled" line for the row and detail header. */
    val spotsLabel: String
        get() = when {
            cancelled -> "Cancelled"
            isUncapped -> "Open to everyone"
            spotsLeft <= 0 -> "Full"
            spotsLeft == 1 -> "1 spot left"
            else -> "$spotsLeft spots left"
        }
}

// MARK: - Check-in window (E-8)

/**
 * The client-side check-in gate. The server is authoritative, but the screen should only offer check-in
 * inside the window and say why when it is not open, rather than firing a request bound to 422. The contract
 * accepts a check-in only between the event's start and end, so there is no early grace.
 */
data class CheckInWindow(val startsAt: Date, val endsAt: Date) {
    sealed interface State {
        data class TooEarly(val opensAt: Date) : State
        data object Open : State
        data object TooLate : State
    }

    fun state(now: Date): State = when {
        now.before(startsAt) -> State.TooEarly(startsAt)
        now.after(endsAt) -> State.TooLate
        else -> State.Open
    }

    fun isOpen(now: Date): Boolean = state(now) is State.Open
}

/** The check-in token's shape (E-8): 66 characters, `<22 base64url>.<43 base64url>`. Checked before anything is sent. */
object CheckInCode {
    private val shape = Regex("^[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}$")
    const val LENGTH = 66
    fun isWellFormed(code: String): Boolean = shape.matches(code)
}

// MARK: - Reservation outcome (E-4)

/** The result of a reserve attempt — a confirmed seat vs a waitlist placement (E-4). */
enum class ReservationOutcome {
    RESERVED, WAITLISTED;

    companion object {
        fun from(raw: String): ReservationOutcome =
            if (raw.lowercase() == "waitlisted") WAITLISTED else RESERVED
        /** The contract's result status, or null for one this build does not know (the caller re-reads instead of guessing). */
        fun parse(raw: String): ReservationOutcome? = when (raw.uppercase()) {
            "RESERVED" -> RESERVED
            "WAITLISTED" -> WAITLISTED
            else -> null
        }
    }

    val confirmationTitle: String get() = if (this == WAITLISTED) "You're on the waitlist" else "You're going"
    val confirmationDetail: String
        get() = if (this == WAITLISTED) "We'll notify you if a spot opens up."
        else "Your spot is confirmed. Add it to your calendar so you don't miss it."
}

// MARK: - Time display (E-3)

/**
 * Event times in the venue's own zone (the contract's `time_zone`) with the donor's locale (E-3). The zone
 * abbreviation is always shown so a time is never ambiguous. Pure, so it is unit-tested.
 */
object EventTime {
    /** The venue zone, or UTC for a value the platform does not know (the zone name is still shown). */
    fun zone(id: String): ZoneId = try { ZoneId.of(id) } catch (_: Exception) { ZoneId.of("UTC") }

    fun start(event: DonorEvent, locale: Locale = Locale.getDefault()): String = at(event, event.startsAt, locale)

    /** Any instant (e.g. when booking opens) in the event's zone, with the zone shown. */
    fun at(event: DonorEvent, date: Date, locale: Locale = Locale.getDefault()): String {
        val zone = zone(event.timeZone)
        return "${dayTime(date, zone, locale)} ${zoneName(date, zone, locale)}"
    }

    /** "Sat, Nov 7, 10:00 AM \u2013 12:00 PM CST" (the end carries its date only when it is another day there). */
    fun range(event: DonorEvent, locale: Locale = Locale.getDefault()): String {
        val zone = zone(event.timeZone)
        val sameDay = event.startsAt.toInstant().atZone(zone).toLocalDate() == event.endsAt.toInstant().atZone(zone).toLocalDate()
        val end = if (sameDay) timeOnly(event.endsAt, zone, locale) else dayTime(event.endsAt, zone, locale)
        return "${dayTime(event.startsAt, zone, locale)} \u2013 $end ${zoneName(event.endsAt, zone, locale)}"
    }

    private fun dayTime(date: Date, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone)
            .format(Instant.ofEpochMilli(date.time)).tidy()

    private fun timeOnly(date: Date, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(date.time)).tidy()

    /** Newer ICU data separates the time from AM/PM with a narrow no-break space; show a plain one. */
    private fun String.tidy(): String = replace(Regex("[\\s\\u202f\\u00a0]+"), " ")

    private fun zoneName(date: Date, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofPattern("zzz", locale).withZone(zone).format(Instant.ofEpochMilli(date.time))
}
