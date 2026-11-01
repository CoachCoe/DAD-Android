package org.dad.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Date

/** The real events conformer + real AccountSession + real Retrofit stack over a scripted transport. */
class WireEventsTest {
    private class Rig(scope: kotlinx.coroutines.CoroutineScope, handler: (Recorded) -> Pair<Int, String>) {
        val http = ScriptedHttp(handler)
        val store = RecordingStore(validCredentials())
        val client = http.client()
        val session = AccountSession(WireAPI(TEST_ORIGIN, client), store, scope)
        val wire = WireEvents(ProductClient(TEST_ORIGIN, session, client))
    }

    private suspend fun failure(block: suspend () -> Any?): Throwable? = runCatching { block() }.exceptionOrNull()

    private val id1 = "a1111111-1111-4111-8111-111111111111"
    private val id2 = "a2222222-2222-4222-8222-222222222222"
    private val code = "a".repeat(22) + "." + "b".repeat(43)

    private fun view(
        id: String = id1, type: String = "\"VOLUNTEER\"", status: String = "null", startsAt: String = "2026-11-07T15:00:00Z",
        endsAt: String = "2026-11-07T17:00:00Z", zone: String = "\"America/Chicago\"", capacity: Int = 20, spots: String = "5",
        checkedInAt: String = "null", note: String = "null", cancelled: Boolean = false, opens: String = "null", early: Boolean = false,
        title: String = "Park Cleanup",
    ) = """{"id":"$id","slug":"park","title":"$title","type":$type,"description":"Bring gloves","venue":"Zilker","address":"2100 Barton Springs",""" +
        """"city":"Austin","starts_at":"$startsAt","ends_at":"$endsAt","time_zone":$zone,"capacity":$capacity,"spots_left":$spots,""" +
        """"reservation_status":$status,"checked_in_at":$checkedInAt,"invitation_note":$note,"report_issue_url":"mailto:help@dad.example",""" +
        """"cancelled":$cancelled,"booking_opens_at":$opens,"early_booking":$early}"""

    private fun list(vararg events: String) = """{"events":[${events.joinToString(",")}]}"""
    private fun at(iso: String) = Date.from(Instant.parse(iso))

    // --- list (E-1, E-2, E-5) ---

    @Test fun listMergesPublicAndInviteOnlyDedupesAndSortsSoonestFirst() = runTest {
        val rig = Rig(backgroundScope) { request ->
            when (request.path) {
                "/donor/me/events" -> 200 to list(view(id1, startsAt = "2026-11-09T15:00:00Z"), view(id2, startsAt = "2026-11-08T15:00:00Z"))
                "/donor/me/events/invite-only" -> 200 to list(view(id2, startsAt = "2026-11-08T15:00:00Z"), view("a3333333-3333-4333-8333-333333333333", startsAt = "2026-11-07T15:00:00Z", type = "\"INVITE_ONLY\"", note = "\"Invitation earned\""))
                else -> 404 to "{}"
            }
        }
        val events = rig.wire.events()
        assertEquals(listOf("a3333333-3333-4333-8333-333333333333", id2, id1), events.map { it.id })
        assertTrue(events.first().isInviteOnly)
        assertEquals("Invitation earned", events.first().invitationNote)
        val sent = rig.http.requests
        assertEquals(listOf("/donor/me/events", "/donor/me/events/invite-only"), sent.map { it.path })
        assertEquals("Bearer access-0", sent.first().headers["authorization"])
        assertEquals("native", sent.first().headers["x-dad-client"])
    }

    @Test fun anEventMapsEveryFieldIncludingTheNewTimeZoneAndCheckInTime() = runTest {
        val rig = Rig(backgroundScope) {
            200 to list(view(status = "\"RESERVED\"", checkedInAt = "\"2026-11-07T15:05:00Z\"", opens = "\"2026-10-01T00:00:00Z\"", early = true))
        }
        val event = rig.wire.events().first()
        assertEquals(id1, event.id)
        assertEquals("volunteer", event.type)
        assertEquals("America/Chicago", event.timeZone)
        assertEquals(at("2026-11-07T15:00:00Z"), event.startsAt)
        assertEquals(at("2026-11-07T15:05:00Z"), event.checkedInAt)
        assertEquals(at("2026-10-01T00:00:00Z"), event.bookingOpensAt)
        assertEquals(ReservationStatus.RESERVED, event.status)
        assertTrue(event.isCheckedIn)
        assertTrue(event.earlyBooking)
        assertEquals("Zilker", event.venue)
        assertEquals("2100 Barton Springs", event.address)
        assertEquals("Bring gloves", event.details)
        assertEquals("mailto:help@dad.example", event.reportIssueUrl)
        assertEquals(5, event.spotsLeft)
    }

    @Test fun anUncappedEventHasNullSpotsLeft() = runTest {
        val event = Rig(backgroundScope) { 200 to list(view(capacity = 0, spots = "null")) }.wire.events().first()
        assertTrue(event.isUncapped)
        assertEquals("Open to everyone", event.spotsLabel)
    }

    @Test fun unknownEnumValuesDoNotFailTheList() = runTest {
        val rig = Rig(backgroundScope) {
            200 to list(
                view(id1, type = "\"BLOCK_PARTY\"", status = "\"CHECKED_OUT\""),
                view(id2, type = "\"VOLUNTEER\"", status = "\"WAITLISTED\""),
            )
        }
        val events = rig.wire.events()
        assertEquals(2, events.size)
        val unknown = events.first { it.id == id1 }
        assertEquals("block_party", unknown.type)
        assertEquals("Block party", unknown.typeLabel)
        assertEquals(ReservationStatus.NONE, unknown.status)
        assertEquals(ReservationStatus.WAITLISTED, events.first { it.id == id2 }.status)
    }

    @Test fun fractionalSecondTimestampsAreAccepted() = runTest {
        val rig = Rig(backgroundScope) {
            200 to list(view(startsAt = "2026-11-07T15:00:00.250Z", endsAt = "2026-11-07T17:00:00.123456+00:00", opens = "\"2026-10-01T00:00:00.5Z\""))
        }
        val event = rig.wire.events().first()
        assertEquals(at("2026-11-07T15:00:00.250Z"), event.startsAt)
        assertEquals(at("2026-11-07T17:00:00.123456Z"), event.endsAt)
    }

    @Test fun anUnparseableTimestampIsAnInvalidResponse() = runTest {
        val rig = Rig(backgroundScope) { 200 to list(view(startsAt = "next tuesday")) }
        assertEquals(AccountFailure.InvalidResponse, failure { rig.wire.events() })
    }

    @Test fun aMissingRequiredFieldIsAnInvalidResponse() = runTest {
        val rig = Rig(backgroundScope) { 200 to """{"events":[{"slug":"x","title":"No id"}]}""" }
        assertEquals(AccountFailure.InvalidResponse, failure { rig.wire.events() })
    }

    @Test fun malformedAndEmptyBodiesAreInvalidResponses() = runTest {
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to "{not json" }.wire.events() })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to "" }.wire.events() })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to """["x"]""" }.wire.events() })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to """{"events":"none"}""" }.wire.events() })
    }

    @Test fun anOversizedBodyIsRefused() = runTest {
        val huge = "x".repeat(1_100_000)
        val rig = Rig(backgroundScope) { 200 to """{"events":[],"pad":"$huge"}""" }
        assertEquals(AccountFailure.InvalidResponse, failure { rig.wire.events() })
    }

    @Test fun aLongListIsCappedNotTrusted() = runTest {
        val many = (0 until 250).map { i -> view("b0000000-0000-4000-8000-%012d".format(i), startsAt = "2026-11-07T15:00:00Z") }
        val rig = Rig(backgroundScope) { request -> if (request.path.endsWith("invite-only")) 200 to list() else 200 to list(*many.toTypedArray()) }
        assertEquals(WireEvents.ROW_LIMIT, rig.wire.events().size)
    }

    // --- detail (E-3) ---

    @Test fun detailReadsOneEventByIdAndRejectsAMalformedId() = runTest {
        val rig = Rig(backgroundScope) { 200 to view(status = "\"WAITLISTED\"") }
        assertEquals(ReservationStatus.WAITLISTED, rig.wire.event(id1).status)
        assertEquals("/donor/me/events/$id1", rig.http.requests.single().path)
        assertEquals(AccountFailure.InvalidInput, failure { rig.wire.event("../../profile") })
        assertEquals(1, rig.http.requests.size)
    }

    // --- reserve / waitlist (E-4) ---

    @Test fun reserveAndWaitlistOutcomes() = runTest {
        val reserved = Rig(backgroundScope) { 200 to """{"status":"RESERVED"}""" }
        assertEquals(ReservationOutcome.RESERVED, reserved.wire.reserve(id1))
        val req = reserved.http.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/donor/me/events/$id1/reserve", req.path)
        assertFalse("the contract defines no Idempotency-Key here", req.headers.containsKey("idempotency-key"))
        assertEquals(ReservationOutcome.WAITLISTED, Rig(backgroundScope) { 200 to """{"status":"WAITLISTED"}""" }.wire.reserve(id1))
    }

    @Test fun anUnknownOrCancelledReserveStatusIsNullNotAGuess() = runTest {
        assertNull(Rig(backgroundScope) { 200 to """{"status":"WITHDRAWN"}""" }.wire.reserve(id1))
        assertNull(Rig(backgroundScope) { 200 to """{"status":"CANCELLED"}""" }.wire.reserve(id1))
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to "{}" }.wire.reserve(id1) })
    }

    // --- cancel (E-9) ---

    @Test fun cancelNeedsACancelledResult() = runTest {
        val ok = Rig(backgroundScope) { 200 to """{"status":"CANCELLED"}""" }
        ok.wire.cancel(id1)
        assertEquals("/donor/me/events/$id1/cancel", ok.http.requests.single().path)
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to """{"status":"RESERVED"}""" }.wire.cancel(id1) })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to """{"status":"SOMETHING"}""" }.wire.cancel(id1) })
    }

    // --- check-in (E-8) ---

    @Test fun checkInSendsTheTokenUnchangedAndMapsTheResult() = runTest {
        val rig = Rig(backgroundScope) { 200 to """{"event_id":"$id1","checked_in":true,"already_checked_in":true}""" }
        val result = rig.wire.checkIn(code)
        assertEquals(CheckInResultValue(id1, checkedIn = true, alreadyCheckedIn = true), result)
        val req = rig.http.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/donor/me/events/check-in", req.path)
        assertEquals("""{"token":"$code"}""", req.body)
    }

    @Test fun aMalformedTokenIsRefusedBeforeAnyRequest() = runTest {
        val rig = Rig(backgroundScope) { 200 to "{}" }
        for (bad in listOf("", "x", code.dropLast(1), "$code\u0000", code.replace(".", ","), " $code")) {
            assertEquals(AccountFailure.InvalidInput, failure { rig.wire.checkIn(bad) })
        }
        assertTrue(rig.http.requests.isEmpty())
    }

    @Test fun aCheckInResultMissingFieldsIsInvalid() = runTest {
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to """{"event_id":"$id1"}""" }.wire.checkIn(code) })
    }

    // --- calendar (E-6) ---

    @Test fun calendarReturnsTheAuthenticatedIcsBytes() = runTest {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"
        val rig = Rig(backgroundScope) { 200 to ics }
        assertEquals(ics, String(rig.wire.calendar(id1)))
        val req = rig.http.requests.single()
        assertEquals("/donor/me/events/$id1/calendar.ics", req.path)
        assertEquals("Bearer access-0", req.headers["authorization"])
    }

    @Test fun aCalendarThatIsNotACalendarOrIsTooLargeOrEmptyIsRefused() = runTest {
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to "<html>login</html>" }.wire.calendar(id1) })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to "" }.wire.calendar(id1) })
        val big = "BEGIN:VCALENDAR\r\n" + "X-PAD:y\r\n".repeat(10_000) + "END:VCALENDAR\r\n"
        assertTrue(big.length > WireEvents.CALENDAR_BYTE_LIMIT)
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to big }.wire.calendar(id1) })
        assertEquals(AccountFailure.InvalidInput, failure { Rig(backgroundScope) { 200 to "x" }.wire.calendar("nope") })
    }

    // --- statuses ---

    private fun statusCases(call: suspend (WireEvents) -> Any?, vararg expected: Pair<Int, AccountFailure>) = expected.forEach { (status, failureExpected) ->
        kotlinx.coroutines.test.runTest {
            val rig = Rig(backgroundScope) { status to "" }
            assertEquals("HTTP $status", failureExpected, failure { call(rig.wire) })
        }
    }

    @Test fun listStatusesMap() = statusCases(
        { it.events() },
        429 to AccountFailure.RateLimited, 503 to AccountFailure.Unavailable, 500 to AccountFailure.Unavailable,
        403 to AccountFailure.Forbidden, 404 to AccountFailure.NotFound,
    )

    @Test fun detailStatusesMap() = statusCases(
        { it.event(id1) },
        404 to AccountFailure.NotFound, 429 to AccountFailure.RateLimited, 503 to AccountFailure.Unavailable,
    )

    @Test fun reserveStatusesMap() = statusCases(
        { it.reserve(id1) },
        403 to AccountFailure.Forbidden, 404 to AccountFailure.NotFound, 409 to AccountFailure.Conflict,
        429 to AccountFailure.RateLimited, 503 to AccountFailure.Unavailable,
    )

    @Test fun cancelStatusesMap() = statusCases(
        { it.cancel(id1) },
        403 to AccountFailure.Forbidden, 404 to AccountFailure.NotFound, 409 to AccountFailure.Conflict, 429 to AccountFailure.RateLimited,
    )

    @Test fun checkInStatusesMap() = statusCases(
        { it.checkIn(code) },
        403 to AccountFailure.Forbidden, 422 to AccountFailure.InvalidInput, 429 to AccountFailure.RateLimited,
        415 to AccountFailure.Unavailable, 503 to AccountFailure.Unavailable,
    )

    @Test fun calendarStatusesMap() = statusCases(
        { it.calendar(id1) },
        404 to AccountFailure.NotFound, 429 to AccountFailure.RateLimited, 503 to AccountFailure.Unavailable,
    )

    @Test fun aBodylessAndABodiedProblemMapTheSame() = runTest {
        val bodyless = Rig(backgroundScope) { 429 to "" }
        val bodied = Rig(backgroundScope) { 429 to """{"detail":"slow down","code":"RATE"}""" }
        assertEquals(AccountFailure.RateLimited, failure { bodyless.wire.reserve(id1) })
        assertEquals(AccountFailure.RateLimited, failure { bodied.wire.reserve(id1) })
    }

    // --- sessions ---

    @Test fun aFirstUnauthorizedRefreshesOnceAndRetries() = runTest {
        var reads = 0
        val rig = Rig(backgroundScope) { request ->
            when (request.path) {
                "/donor/refresh" -> 200 to nativeSessionJson("access-1", "refresh-1")
                else -> if (request.headers["authorization"] == "Bearer access-1") 200 to """{"status":"RESERVED"}""" else { reads++; 401 to "" }
            }
        }
        assertEquals(ReservationOutcome.RESERVED, rig.wire.reserve(id1))
        assertEquals(1, reads)
        assertEquals(1, rig.http.count("/donor/refresh"))
    }

    @Test fun aSecondUnauthorizedSignsOutLocally() = runTest {
        val rig = Rig(backgroundScope) { request -> if (request.path == "/donor/refresh") 200 to nativeSessionJson("access-1", "refresh-1") else 401 to "" }
        assertEquals(AccountFailure.SignedOut, failure { rig.wire.events() })
        assertNull(rig.store.value)
    }

    @Test fun cancellationPropagatesUntouched() = runTest {
        val rig = Rig(backgroundScope) { throw CancellationException("left the screen") }
        val thrown = failure { rig.wire.events() }
        assertTrue("$thrown", thrown is CancellationException || thrown is java.io.IOException)
    }
}
