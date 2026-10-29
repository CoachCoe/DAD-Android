package org.dad.foundation

import java.util.Date

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
) {
    /** Invite-only (E-5): carries an invitation note / guest-list marker. */
    val isInviteOnly: Boolean get() = invitationNote != null

    /** The primary-action decision (E-3/E-4/E-9). [now] is injected so tests are deterministic. */
    fun availability(now: Date): EventAvailability {
        if (cancelled) return EventAvailability.Cancelled
        return when (status) {
            ReservationStatus.CHECKED_IN -> EventAvailability.CheckedIn
            ReservationStatus.RESERVED -> EventAvailability.Reserved
            ReservationStatus.WAITLISTED -> EventAvailability.Waitlisted
            ReservationStatus.NONE -> {
                val opens = bookingOpensAt
                if (opens != null && now.before(opens)) EventAvailability.NotYetOpen(opens)
                else if (spotsLeft > 0) EventAvailability.Reservable(spotsLeft)
                else EventAvailability.Waitlistable
            }
        }
    }

    /** "3 spots left" / "Full" / "Cancelled" line for the row and detail header. */
    val spotsLabel: String
        get() = when {
            cancelled -> "Cancelled"
            spotsLeft <= 0 -> "Full"
            spotsLeft == 1 -> "1 spot left"
            else -> "$spotsLeft spots left"
        }
}

// MARK: - Check-in window (E-8)

/**
 * The client-side check-in gate. The server is authoritative, but the scanner should only offer
 * check-in inside the window and give a clear reason when it is too early / too late, rather than
 * firing a request bound to 422. Doors open [EARLY_GRACE_MS] before start and close at endsAt.
 */
data class CheckInWindow(val startsAt: Date, val endsAt: Date) {
    val opensAt: Date get() = Date(startsAt.time - EARLY_GRACE_MS)

    sealed interface State {
        data class TooEarly(val opensAt: Date) : State
        data object Open : State
        data object TooLate : State
    }

    fun state(now: Date): State = when {
        now.before(opensAt) -> State.TooEarly(opensAt)
        now.after(endsAt) -> State.TooLate
        else -> State.Open
    }

    fun isOpen(now: Date): Boolean = state(now) is State.Open

    companion object {
        /** Server rule: doors open 60 minutes early. */
        const val EARLY_GRACE_MS: Long = 60L * 60L * 1000L
    }
}

// MARK: - Reservation outcome (E-4)

/** The result of a reserve attempt — a confirmed seat vs a waitlist placement (E-4). */
enum class ReservationOutcome {
    RESERVED, WAITLISTED;

    companion object {
        fun from(raw: String): ReservationOutcome =
            if (raw.lowercase() == "waitlisted") WAITLISTED else RESERVED
    }

    val confirmationTitle: String get() = if (this == WAITLISTED) "You're on the waitlist" else "You're going"
    val confirmationDetail: String
        get() = if (this == WAITLISTED) "We'll notify you if a spot opens up."
        else "Your spot is confirmed. Add it to your calendar so you don't miss it."
}

// MARK: - Calendar source (E-6)

/**
 * Which .ics endpoint "add to calendar" should use. An invite-only event must use the
 * AUTHENTICATED calendar (v2.2 donorEventCalendar) so the private invite isn't fetched anonymously;
 * a public event uses the public eventCalendar. Pure decision so the UI never picks the wrong one.
 */
enum class CalendarSource { PUBLIC, AUTHENTICATED;
    companion object {
        fun forEvent(event: DonorEvent): CalendarSource =
            if (event.isInviteOnly) AUTHENTICATED else PUBLIC
    }
}
