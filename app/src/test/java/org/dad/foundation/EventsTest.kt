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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class EventsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val now = Date(1_793_000_000_000)
    private val code = "a".repeat(22) + "." + "b".repeat(43)
    private fun event(
        id: String = "e", spotsLeft: Int = 5, status: ReservationStatus = ReservationStatus.NONE, cancelled: Boolean = false,
        invitationNote: String? = null, bookingOpensAt: Date? = null, capacity: Int = 50, type: String = "volunteer",
        startsAt: Date = Date(now.time + 86_400_000), endsAt: Date = Date(now.time + 93_600_000),
        earlyBooking: Boolean = false, checkedInAt: Date? = null, timeZone: String = "America/Chicago",
    ) = DonorEvent(id, "slug", "Title", type, "Venue", "Austin",
        startsAt = startsAt, endsAt = endsAt, capacity = capacity, spotsLeft = spotsLeft, status = status,
        invitationNote = invitationNote, cancelled = cancelled, bookingOpensAt = bookingOpensAt, earlyBooking = earlyBooking,
        timeZone = timeZone, checkedInAt = checkedInAt)

    // --- Availability (E-2/E-3/E-4/E-9) ---
    @Test fun reservableWhenSpotsRemain() {
        assertEquals(EventAvailability.Reservable(5), event(spotsLeft = 5).availability(now))
    }
    @Test fun waitlistableWhenFull() {
        assertEquals(EventAvailability.Waitlistable, event(spotsLeft = 0).availability(now))
    }
    @Test fun anUncappedEventIsAlwaysReservableAndSaysSo() {
        val uncapped = event(capacity = 0, spotsLeft = 0)
        assertTrue(uncapped.availability(now) is EventAvailability.Reservable)
        assertEquals("Open to everyone", uncapped.spotsLabel)
    }
    @Test fun notYetOpenBeforeBookingWindow() {
        val opens = Date(now.time + 3_600_000)
        assertEquals(EventAvailability.NotYetOpen(opens), event(bookingOpensAt = opens).availability(now))
    }
    @Test fun earlyBookingDonorsMayReserveBeforeBookingOpens() {
        val opens = Date(now.time + 3_600_000)
        assertEquals(EventAvailability.Reservable(5), event(bookingOpensAt = opens, earlyBooking = true).availability(now))
    }
    @Test fun anEventThatHasStartedCannotBeBookedButAHeldSeatStillShows() {
        val started = event(startsAt = Date(now.time - 60_000))
        assertEquals(EventAvailability.Started, started.availability(now))
        assertEquals(EventAvailability.Reserved, started.copy(status = ReservationStatus.RESERVED).availability(now))
    }
    @Test fun reservedCancelledCheckedInAreTerminalOrAction() {
        assertEquals(EventAvailability.Reserved, event(status = ReservationStatus.RESERVED).availability(now))
        assertEquals(EventAvailability.Waitlisted, event(status = ReservationStatus.WAITLISTED).availability(now))
        assertEquals(EventAvailability.CheckedIn, event(status = ReservationStatus.CHECKED_IN).availability(now))
        assertEquals(EventAvailability.Cancelled, event(cancelled = true).availability(now))
    }
    @Test fun aReservedSeatWithACheckInTimeIsCheckedIn() {
        val checkedIn = event(status = ReservationStatus.RESERVED, checkedInAt = now)
        assertEquals(EventAvailability.CheckedIn, checkedIn.availability(now))
        assertTrue(checkedIn.isCheckedIn)
        assertFalse(checkedIn.canCancel(now))
    }
    @Test fun spotsLabel() {
        assertEquals("5 spots left", event(spotsLeft = 5).spotsLabel)
        assertEquals("1 spot left", event(spotsLeft = 1).spotsLabel)
        assertEquals("Full", event(spotsLeft = 0).spotsLabel)
        assertEquals("Cancelled", event(cancelled = true).spotsLabel)
    }
    @Test fun cancelIsOfferedUntilStartAndNeverForCancelledOrUnheld() {
        assertTrue(event(status = ReservationStatus.RESERVED).canCancel(now))
        assertTrue(event(status = ReservationStatus.WAITLISTED).canCancel(now))
        assertFalse(event().canCancel(now))
        assertFalse(event(status = ReservationStatus.RESERVED, cancelled = true).canCancel(now))
        assertFalse(event(status = ReservationStatus.RESERVED, startsAt = Date(now.time - 1)).canCancel(now))
    }

    // --- Invite-only (E-5) and types ---
    @Test fun inviteOnlyByNoteOrType() {
        assertFalse(event().isInviteOnly)
        assertTrue(event(invitationNote = "Founding donors only").isInviteOnly)
        assertTrue(event(type = "invite_only").isInviteOnly)
    }
    @Test fun typeLabelsKnownAndUnknownKinds() {
        assertEquals("Volunteer", event(type = "volunteer").typeLabel)
        assertEquals("Community", event(type = "community").typeLabel)
        assertEquals("Invite-only", event(type = "invite_only").typeLabel)
        assertEquals("Block party", event(type = "block_party").typeLabel)
        assertEquals("Event", event(type = "").typeLabel)
    }

    // --- Check-in window (E-8): between start and end, no early grace ---
    @Test fun checkInWindowOpensAtStartAndClosesAtEnd() {
        val starts = Date(now.time)
        val ends = Date(now.time + 7_200_000)
        val window = CheckInWindow(starts, ends)
        assertTrue(window.state(Date(starts.time - 1)) is CheckInWindow.State.TooEarly)
        assertTrue(window.state(Date(starts.time - 59 * 60_000)) is CheckInWindow.State.TooEarly)
        assertTrue(window.isOpen(starts))
        assertTrue(window.isOpen(ends))
        assertTrue(window.state(Date(ends.time + 1)) is CheckInWindow.State.TooLate)
    }
    @Test fun checkInIsOfferedOnlyForAHeldSeatInsideTheWindowAndOnlyOnce() {
        val live = event(status = ReservationStatus.RESERVED, startsAt = Date(now.time - 60_000), endsAt = Date(now.time + 3_600_000))
        assertTrue(live.canCheckIn(now))
        assertFalse(live.copy(status = ReservationStatus.WAITLISTED).canCheckIn(now))
        assertFalse(live.copy(status = ReservationStatus.NONE).canCheckIn(now))
        assertFalse(live.copy(cancelled = true).canCheckIn(now))
        assertFalse(live.copy(checkedInAt = now).canCheckIn(now))
        assertFalse(live.copy(startsAt = Date(now.time + 1_000)).canCheckIn(now))
        assertFalse(live.copy(endsAt = Date(now.time - 1)).canCheckIn(now))
    }
    @Test fun checkInCodeShapeIsExactlyTheContractsToken() {
        assertTrue(CheckInCode.isWellFormed(code))
        assertTrue(CheckInCode.isWellFormed("A_-0123456789abcdefghi.${"Z-_".repeat(13)}abcd"))
        assertFalse(CheckInCode.isWellFormed(""))
        assertFalse(CheckInCode.isWellFormed(code.dropLast(1)))
        assertFalse(CheckInCode.isWellFormed(code + "x"))
        assertFalse(CheckInCode.isWellFormed(code.replace(".", "-")))
        assertFalse(CheckInCode.isWellFormed(" $code"))
        assertFalse(CheckInCode.isWellFormed(code.replaceFirst("a", "+")))
        assertFalse(CheckInCode.isWellFormed("a".repeat(21) + "." + "b".repeat(44)))
    }

    // --- Reservation outcome (E-4) ---
    @Test fun reservationOutcomeCopy() {
        assertEquals(ReservationOutcome.WAITLISTED, ReservationOutcome.from("waitlisted"))
        assertEquals(ReservationOutcome.RESERVED, ReservationOutcome.from("reserved"))
        assertEquals("You're on the waitlist", ReservationOutcome.WAITLISTED.confirmationTitle)
        assertEquals("You're going", ReservationOutcome.RESERVED.confirmationTitle)
    }
    @Test fun anUnknownResultStatusIsNotGuessed() {
        assertEquals(ReservationOutcome.RESERVED, ReservationOutcome.parse("RESERVED"))
        assertEquals(ReservationOutcome.WAITLISTED, ReservationOutcome.parse("waitlisted"))
        assertNull(ReservationOutcome.parse("CANCELLED"))
        assertNull(ReservationOutcome.parse("SOMETHING_NEW"))
    }
    @Test fun reservationStatusDecodesDefensively() {
        assertEquals(ReservationStatus.NONE, ReservationStatus.from("SOMETHING_NEW"))
        assertEquals(ReservationStatus.CHECKED_IN, ReservationStatus.from("checked_in"))
    }

    // --- Time display (E-3): the venue's zone, with the zone shown ---
    @Test fun timesShowInTheVenueZoneNotTheDevices() {
        // 2026-11-07T15:00:00Z is 9:00 AM in Chicago (CST) and 10:00 AM in New York (EST).
        val start = Date.from(java.time.Instant.parse("2026-11-07T15:00:00Z"))
        val end = Date.from(java.time.Instant.parse("2026-11-07T17:00:00Z"))
        val chicago = event(startsAt = start, endsAt = end, timeZone = "America/Chicago")
        val newYork = chicago.copy(timeZone = "America/New_York")
        assertEquals("Nov 7, 2026, 9:00 AM CST", EventTime.start(chicago, Locale.US))
        assertEquals("Nov 7, 2026, 10:00 AM EST", EventTime.start(newYork, Locale.US))
        assertEquals("Nov 7, 2026, 9:00 AM \u2013 11:00 AM CST", EventTime.range(chicago, Locale.US))
    }
    @Test fun anEventOverMidnightCarriesTheEndDate() {
        val start = Date.from(java.time.Instant.parse("2026-11-07T23:00:00Z"))
        val end = Date.from(java.time.Instant.parse("2026-11-08T07:00:00Z"))
        val text = EventTime.range(event(startsAt = start, endsAt = end, timeZone = "America/Chicago"), Locale.US)
        assertEquals("Nov 7, 2026, 5:00 PM \u2013 Nov 8, 2026, 1:00 AM CST", text)
        val crossing = EventTime.range(event(startsAt = start, endsAt = Date.from(java.time.Instant.parse("2026-11-08T13:00:00Z")), timeZone = "America/Chicago"), Locale.US)
        assertEquals("Nov 7, 2026, 5:00 PM \u2013 Nov 8, 2026, 7:00 AM CST", crossing)
    }
    @Test fun daylightSavingShowsTheDaylightZoneName() {
        val summer = event(startsAt = Date.from(java.time.Instant.parse("2026-07-04T16:00:00Z")), timeZone = "America/Chicago")
        assertEquals("Jul 4, 2026, 11:00 AM CDT", EventTime.start(summer, Locale.US))
    }
    @Test fun anUnknownZoneFallsBackToUtcAndSaysSo() {
        val odd = event(startsAt = Date.from(java.time.Instant.parse("2026-11-07T15:00:00Z")), timeZone = "Mars/Olympus")
        assertEquals("Nov 7, 2026, 3:00 PM UTC", EventTime.start(odd, Locale.US))
    }

    // --- EventsModel state machine ---
    private class FakeService(
        var failLoad: Throwable? = null,
        var reserveOutcome: ReservationOutcome? = ReservationOutcome.RESERVED,
        var reserveFailure: Throwable? = null,
        var checkInFailure: Throwable? = null,
        var checkInEventId: String = "e1",
        var calendarFailure: Throwable? = null,
        var detailFailure: Throwable? = null,
    ) : EventsService {
        var reserveCalls = 0
        var checkInCalls = 0
        var listCalls = 0
        var current = DonorEvent("e1", "s", "T", "volunteer", "V", "Austin", Date(1), Date(2), 50, 5, ReservationStatus.NONE)
        override suspend fun events(): List<DonorEvent> {
            listCalls++
            failLoad?.let { throw it }
            return listOf(current)
        }
        override suspend fun event(id: String): DonorEvent {
            detailFailure?.let { throw it }
            return current.copy(status = ReservationStatus.RESERVED, spotsLeft = 4)
        }
        override suspend fun reserve(eventId: String): ReservationOutcome? { reserveCalls++; reserveFailure?.let { throw it }; return reserveOutcome }
        override suspend fun cancel(eventId: String) { reserveFailure?.let { throw it } }
        override suspend fun checkIn(code: String): CheckInResultValue {
            checkInCalls++
            checkInFailure?.let { throw it }
            return CheckInResultValue(checkInEventId, checkedIn = true, alreadyCheckedIn = false)
        }
        override suspend fun calendar(eventId: String): ByteArray { calendarFailure?.let { throw it }; return "BEGIN:VCALENDAR".toByteArray() }
    }

    private fun modelOf(service: FakeService) = EventsModel(service) { now }.also { it.load(); dispatcher.scheduler.advanceUntilIdle() }

    @Test fun loadReadySortsEvents() = runTest {
        val model = modelOf(FakeService())
        assertEquals(EventsPhase.Ready, model.phase)
        assertEquals(1, model.events.size)
    }

    @Test fun loadsSoonestFirst() = runTest {
        val service = object : EventsService by FakeService() {
            override suspend fun events() = listOf(event("later", startsAt = Date(now.time + 2_000)), event("sooner", startsAt = Date(now.time + 1_000)))
        }
        val model = EventsModel(service) { now }
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("sooner", "later"), model.events.map { it.id })
    }

    @Test fun loadFailureSurfacesFailedAndRetryRecovers() = runTest {
        val service = FakeService(failLoad = AccountFailure.Unavailable)
        val model = modelOf(service)
        assertTrue(model.phase is EventsPhase.Failed)
        service.failLoad = null
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(EventsPhase.Ready, model.phase)
    }

    @Test fun signedOutOnLoadFlagsTheModel() = runTest {
        val model = modelOf(FakeService(failLoad = AccountFailure.SignedOut))
        assertTrue(model.signedOut)
    }

    @Test fun reserveRecordsOutcomeAndRefreshesEventInPlace() = runTest {
        val model = modelOf(FakeService(reserveOutcome = ReservationOutcome.WAITLISTED))
        model.reserve("e1"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ReservationOutcome.WAITLISTED, model.lastOutcome)
        assertEquals(ReservationStatus.RESERVED, model.events.first().status) // refreshed in place
    }

    @Test fun anUnknownReserveStatusShowsNoConfirmationButRereadsTheEvent() = runTest {
        val model = modelOf(FakeService(reserveOutcome = null))
        model.reserve("e1"); dispatcher.scheduler.advanceUntilIdle()
        assertNull(model.lastOutcome)
        assertEquals(ReservationStatus.RESERVED, model.events.first().status)
    }

    @Test fun doubleTappedReserveBooksOnce() = runTest {
        val service = FakeService()
        val model = modelOf(service)
        model.reserve("e1")
        model.reserve("e1")
        assertTrue("e1" in model.busy)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, service.reserveCalls)
        assertNull(model.actionError)
        assertTrue(model.busy.isEmpty())
    }

    @Test fun refusalsHaveTheirOwnWords() = runTest {
        val cases = mapOf(
            AccountFailure.Forbidden to EventsCopy.NOT_ELIGIBLE,
            AccountFailure.NotFound to EventsCopy.GONE,
            AccountFailure.Conflict to EventsCopy.CHANGED,
            AccountFailure.RateLimited to EventsCopy.TOO_MANY,
            AccountFailure.Unavailable to EventsCopy.GENERIC,
            AccountFailure.InvalidResponse to EventsCopy.GENERIC,
        )
        for ((failure, words) in cases) {
            val model = modelOf(FakeService(reserveFailure = failure))
            model.reserve("e1"); dispatcher.scheduler.advanceUntilIdle()
            assertEquals("$failure", words, model.actionError)
            assertTrue("a failed command leaves the screen usable", model.phase == EventsPhase.Ready && model.events.size == 1)
        }
    }

    @Test fun aConflictOrAGoneEventReloadsTheList() = runTest {
        val conflict = FakeService(reserveFailure = AccountFailure.Conflict)
        val model = modelOf(conflict)
        model.reserve("e1"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, conflict.listCalls)
        val forbidden = FakeService(reserveFailure = AccountFailure.Forbidden)
        val other = modelOf(forbidden)
        other.reserve("e1"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, forbidden.listCalls)
    }

    @Test fun anEventThatBecomesUnavailableLeavesTheList() = runTest {
        val service = FakeService(detailFailure = AccountFailure.NotFound)
        val model = modelOf(service)
        model.open("e1"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.events.isEmpty())
    }

    @Test fun signedOutOnACommandFlagsTheModel() = runTest {
        val model = modelOf(FakeService(reserveFailure = AccountFailure.SignedOut))
        model.reserve("e1"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.signedOut)
    }

    @Test fun aCancelledCommandKeepsThePriorState() = runTest {
        val service = object : EventsService by FakeService() {
            override suspend fun cancel(eventId: String) { throw kotlinx.coroutines.CancellationException("left") }
        }
        val model = EventsModel(service) { now }
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        val before = model.events
        model.cancel("e1"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(before, model.events)
        assertNull(model.actionError)
        assertTrue(model.busy.isEmpty())
    }

    // --- Check-in (E-8) ---
    private val live = event(
        id = "e1", status = ReservationStatus.RESERVED, startsAt = Date(now.time - 60_000), endsAt = Date(now.time + 3_600_000),
    )

    @Test fun checkInSucceedsInsideTheWindowAndRereadsTheEvent() = runTest {
        val service = FakeService()
        val model = modelOf(service)
        model.checkIn(live, code); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(CheckInState.Done(alreadyCheckedIn = false), model.checkIn)
        assertEquals(1, service.checkInCalls)
    }

    @Test fun checkInOutsideTheWindowSendsNothingAndSaysWhy() = runTest {
        val service = FakeService()
        val model = modelOf(service)
        model.checkIn(live.copy(startsAt = Date(now.time + 60_000)), code)
        assertEquals(CheckInState.Failed("Check-in opens when the event starts."), model.checkIn)
        model.checkIn(live.copy(endsAt = Date(now.time - 1)), code)
        assertEquals(CheckInState.Failed("Check-in for this event has closed."), model.checkIn)
        assertEquals(0, service.checkInCalls)
    }

    @Test fun aMalformedCodeIsNeverSent() = runTest {
        val service = FakeService()
        val model = modelOf(service)
        model.checkIn(live, "not-a-code")
        assertEquals(CheckInState.Failed(EventsCopy.BAD_SHAPE), model.checkIn)
        assertEquals(0, service.checkInCalls)
    }

    @Test fun aWaitlistedDonorCannotCheckIn() = runTest {
        val service = FakeService()
        val model = modelOf(service)
        model.checkIn(live.copy(status = ReservationStatus.WAITLISTED), code)
        assertEquals(CheckInState.Failed(EventsCopy.NOT_HELD), model.checkIn)
        assertEquals(0, service.checkInCalls)
    }

    @Test fun anAlreadyCheckedInDonorIsToldSo() = runTest {
        val model = modelOf(FakeService())
        model.checkIn(live.copy(checkedInAt = now), code)
        assertEquals(CheckInState.Failed("You’re already checked in."), model.checkIn)
    }

    @Test fun theServersRefusalOfACodeExplainsWhy() = runTest {
        val model = modelOf(FakeService(checkInFailure = AccountFailure.InvalidInput))
        model.checkIn(live, code); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(CheckInState.Failed(EventsCopy.CODE_REFUSED), model.checkIn)
    }

    @Test fun checkInRateLimitedAndForbiddenAndSignedOut() = runTest {
        val limited = modelOf(FakeService(checkInFailure = AccountFailure.RateLimited))
        limited.checkIn(live, code); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(CheckInState.Failed(EventsCopy.TOO_MANY), limited.checkIn)
        val forbidden = modelOf(FakeService(checkInFailure = AccountFailure.Forbidden))
        forbidden.checkIn(live, code); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(CheckInState.Failed(EventsCopy.NOT_ELIGIBLE), forbidden.checkIn)
        val out = modelOf(FakeService(checkInFailure = AccountFailure.SignedOut))
        out.checkIn(live, code); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(out.signedOut)
    }

    @Test fun aCodeForAnotherEventChecksNothingInHere() = runTest {
        val model = modelOf(FakeService(checkInEventId = "someone-elses"))
        model.checkIn(live, code); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(CheckInState.Failed(EventsCopy.WRONG_EVENT), model.checkIn)
    }

    @Test fun aDoubleTappedCheckInSendsOnce() = runTest {
        val service = FakeService()
        val model = modelOf(service)
        model.checkIn(live, code)
        model.checkIn(live, code)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, service.checkInCalls)
    }

    @Test fun aTypedCodeIsTrimmedButNotRepaired() = runTest {
        val service = FakeService()
        val model = modelOf(service)
        model.checkIn(live, "  $code \n"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, service.checkInCalls)
    }

    // --- Calendar (E-6) ---
    @Test fun addToCalendarFetchesThenHandsTheFileOverOnce() = runTest {
        val model = modelOf(FakeService())
        model.addToCalendar(live)
        assertEquals(CalendarState.Fetching("e1"), model.calendar)
        dispatcher.scheduler.advanceUntilIdle()
        val ready = model.calendar as CalendarState.Ready
        assertEquals("e1", ready.eventId)
        assertEquals("BEGIN:VCALENDAR", String(ready.bytes))
        model.calendarShared()
        assertEquals(CalendarState.Idle, model.calendar)
    }

    @Test fun aFailedCalendarFetchSaysSoAndKeepsTheScreen() = runTest {
        val model = modelOf(FakeService(calendarFailure = AccountFailure.NotFound))
        model.addToCalendar(live); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(CalendarState.Failed(EventsCopy.GONE), model.calendar)
        assertEquals(EventsPhase.Ready, model.phase)
    }
}
