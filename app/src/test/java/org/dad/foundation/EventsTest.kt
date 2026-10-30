package org.dad.foundation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class EventsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val now = Date(1_793_000_000_000)
    private fun event(
        spotsLeft: Int = 5, status: ReservationStatus = ReservationStatus.NONE, cancelled: Boolean = false,
        invitationNote: String? = null, bookingOpensAt: Date? = null,
    ) = DonorEvent("e", "slug", "Title", "volunteer", "Venue", "Austin",
        startsAt = Date(now.time + 86_400_000), endsAt = Date(now.time + 93_600_000),
        capacity = 50, spotsLeft = spotsLeft, status = status, invitationNote = invitationNote,
        cancelled = cancelled, bookingOpensAt = bookingOpensAt)

    // --- Availability (E-2/E-3/E-4/E-9) ---
    @Test fun reservableWhenSpotsRemain() {
        assertEquals(EventAvailability.Reservable(5), event(spotsLeft = 5).availability(now))
    }
    @Test fun waitlistableWhenFull() {
        assertEquals(EventAvailability.Waitlistable, event(spotsLeft = 0).availability(now))
    }
    @Test fun notYetOpenBeforeBookingWindow() {
        val opens = Date(now.time + 3_600_000)
        assertEquals(EventAvailability.NotYetOpen(opens), event(bookingOpensAt = opens).availability(now))
    }
    @Test fun reservedCancelledCheckedInAreTerminalOrAction() {
        assertEquals(EventAvailability.Reserved, event(status = ReservationStatus.RESERVED).availability(now))
        assertEquals(EventAvailability.Waitlisted, event(status = ReservationStatus.WAITLISTED).availability(now))
        assertEquals(EventAvailability.CheckedIn, event(status = ReservationStatus.CHECKED_IN).availability(now))
        assertEquals(EventAvailability.Cancelled, event(cancelled = true).availability(now))
    }
    @Test fun spotsLabel() {
        assertEquals("5 spots left", event(spotsLeft = 5).spotsLabel)
        assertEquals("1 spot left", event(spotsLeft = 1).spotsLabel)
        assertEquals("Full", event(spotsLeft = 0).spotsLabel)
        assertEquals("Cancelled", event(cancelled = true).spotsLabel)
    }

    // --- Invite-only (E-5) + calendar source (E-6) ---
    @Test fun inviteOnlyGatingAndCalendarSource() {
        val publicEvent = event()
        val inviteOnly = event(invitationNote = "Founding donors only")
        assertFalse(publicEvent.isInviteOnly)
        assertTrue(inviteOnly.isInviteOnly)
        // An invite-only event uses the AUTHENTICATED .ics (v2.2 donorEventCalendar), never public.
        assertEquals(CalendarSource.PUBLIC, CalendarSource.forEvent(publicEvent))
        assertEquals(CalendarSource.AUTHENTICATED, CalendarSource.forEvent(inviteOnly))
    }

    // --- Check-in window boundaries (E-8) ---
    @Test fun checkInWindowOpensEarlyAndClosesAtEnd() {
        val starts = Date(now.time)
        val ends = Date(now.time + 7_200_000)
        val window = CheckInWindow(starts, ends)
        // 61 min before start = too early; 59 min before = open.
        assertTrue(window.state(Date(starts.time - 61 * 60_000)) is CheckInWindow.State.TooEarly)
        assertTrue(window.isOpen(Date(starts.time - 59 * 60_000)))
        assertTrue(window.isOpen(starts))
        assertTrue(window.isOpen(ends))
        // After end = too late.
        assertTrue(window.state(Date(ends.time + 1)) is CheckInWindow.State.TooLate)
    }

    // --- Reservation outcome (E-4) ---
    @Test fun reservationOutcomeCopy() {
        assertEquals(ReservationOutcome.WAITLISTED, ReservationOutcome.from("waitlisted"))
        assertEquals(ReservationOutcome.RESERVED, ReservationOutcome.from("reserved"))
        assertEquals("You're on the waitlist", ReservationOutcome.WAITLISTED.confirmationTitle)
        assertEquals("You're going", ReservationOutcome.RESERVED.confirmationTitle)
    }

    @Test fun reservationStatusDecodesDefensively() {
        assertEquals(ReservationStatus.NONE, ReservationStatus.from("SOMETHING_NEW"))
        assertEquals(ReservationStatus.CHECKED_IN, ReservationStatus.from("checked_in"))
    }

    // --- EventsModel state machine ---
    private class FakeService(
        var failLoad: Boolean = false,
        var reserveOutcome: ReservationOutcome = ReservationOutcome.RESERVED,
    ) : EventsService {
        val base = DonorEvent("e1", "s", "T", "volunteer", "V", "Austin", Date(1), Date(2), 50, 5, ReservationStatus.NONE)
        override suspend fun events(): List<DonorEvent> {
            if (failLoad) throw AccountFailure.SignedOut
            return listOf(base)
        }
        override suspend fun event(id: String) = base.copy(status = ReservationStatus.RESERVED, spotsLeft = 4)
        override suspend fun reserve(eventId: String) = reserveOutcome
        override suspend fun cancel(eventId: String) {}
        override suspend fun checkIn(code: String) = CheckInResultValue("e1", checkedIn = true, alreadyCheckedIn = false)
    }

    @Test fun loadReadySortsEvents() = runTest {
        val model = EventsModel(FakeService())
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(EventsPhase.Ready, model.phase)
        assertEquals(1, model.events.size)
    }

    @Test fun loadFailureSurfacesFailed() = runTest {
        val model = EventsModel(FakeService(failLoad = true))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is EventsPhase.Failed)
    }

    @Test fun reserveRecordsOutcomeAndRefreshesEventInPlace() = runTest {
        val model = EventsModel(FakeService(reserveOutcome = ReservationOutcome.WAITLISTED))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.reserve("e1"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ReservationOutcome.WAITLISTED, model.lastOutcome)
        assertEquals(ReservationStatus.RESERVED, model.events.first().status) // refreshed in place
    }

    // --- CheckInModel window gate ---
    @Test fun checkInRejectedOutsideWindow() = runTest {
        val model = CheckInModel(FakeService())
        val starts = Date(now.time + 86_400_000)
        val window = CheckInWindow(starts, Date(starts.time + 7_200_000))
        model.submit("code", window, now) // now is a day before -> too early
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.state is CheckInModel.State.OutsideWindow)
    }

    @Test fun checkInSucceedsInsideWindow() = runTest {
        val model = CheckInModel(FakeService())
        val starts = Date(now.time)
        val window = CheckInWindow(starts, Date(starts.time + 7_200_000))
        model.submit("code", window, starts) // at start -> open
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.state is CheckInModel.State.Success)
    }
}
