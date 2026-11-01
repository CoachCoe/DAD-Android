package org.dad.foundation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.Date
import java.util.UUID

/**
 * The events routes, read as raw JSON on purpose. The generated models cannot express what the server may
 * send here: `type`, `reservation_status` and `ReservationResult.status` are enums the contract says clients
 * must tolerate a new value of, and `spots_left`, `reservation_status` and `checked_in_at` are `anyOf [x, null]`.
 * Gson silently turns an unlisted enum value into `null`, so the typed model would fail the whole list on one
 * new value. Reading JSON and mapping it explicitly lets an unknown value degrade to something harmless.
 */
internal interface EventsApi {
    @GET("donor/me/events") suspend fun events(): Response<JsonElement>
    @GET("donor/me/events/invite-only") suspend fun inviteOnly(): Response<JsonElement>
    @GET("donor/me/events/{id}") suspend fun detail(@Path("id") id: String): Response<JsonElement>
    @POST("donor/me/events/{id}/reserve") suspend fun reserve(@Path("id") id: String): Response<JsonElement>
    @POST("donor/me/events/{id}/cancel") suspend fun cancel(@Path("id") id: String): Response<JsonElement>
    @POST("donor/me/events/check-in") suspend fun checkIn(@Body body: JsonObject): Response<JsonElement>
    @GET("donor/me/events/{id}/calendar.ics") suspend fun calendar(@Path("id") id: String): Response<ResponseBody>
}

/**
 * Real events service (PRD E-1..E-10): `donorEvents` + `inviteOnlyEvents` (merged), `donorEventDetail`,
 * `reserveEvent`, `cancelEvent`, `checkInEvent` and `donorEventCalendar`, each authorized through
 * [ProductClient] (single refresh + one retry on 401).
 *
 * Status mapping: 401 -> signed out; 403 -> [AccountFailure.Forbidden] (not eligible); 404 ->
 * [AccountFailure.NotFound] (gone, or hidden by the invite-only gate); 409 -> [AccountFailure.Conflict] (full,
 * started, booking not open yet, or the reservation changed); 422 -> [AccountFailure.InvalidInput] (a check-in
 * the server refuses: outside the window, no held seat, unknown code); 429 (with or without a body: the
 * ingress limiter sends none) -> [AccountFailure.RateLimited]; anything else, or a body that does not decode
 * -> unavailable / invalid response. Error bodies are never surfaced. Cancellation propagates untouched.
 *
 * The contract defines no Idempotency-Key for these commands. Reserve, cancel and check-in are naturally safe
 * to repeat (a second reserve is a 409, a second cancel changes nothing, a second check-in answers
 * `already_checked_in`), and the model refuses a second command for an event while one is in flight, so no
 * key is invented here.
 */
class WireEvents(private val product: ProductClient) : EventsService {

    override suspend fun events(): List<DonorEvent> {
        val own = product.perform { it.create(EventsApi::class.java).events().eventsBody() }
        val gated = product.perform { it.create(EventsApi::class.java).inviteOnly().eventsBody() }
        // The same event may appear in both reads; keep one, then soonest-first (E-2).
        val seen = HashSet<String>()
        return (own + gated).filter { seen.add(it.id) }.take(ROW_LIMIT).sortedBy { it.startsAt }
    }

    override suspend fun event(id: String): DonorEvent {
        requireId(id)
        return product.perform { mapEvent(it.create(EventsApi::class.java).detail(id).eventBody().asObjectOrInvalid()) }
    }

    override suspend fun reserve(eventId: String): ReservationOutcome? {
        requireId(eventId)
        return product.perform { retrofit ->
            val status = retrofit.create(EventsApi::class.java).reserve(eventId).eventBody().asObjectOrInvalid().str("status")
            // CANCELLED is not a place a reserve can leave the donor in; an unknown value is re-read, not guessed.
            ReservationOutcome.parse(status)
        }
    }

    override suspend fun cancel(eventId: String) {
        requireId(eventId)
        product.perform { retrofit ->
            // Anything but CANCELLED means the server did not do what was asked: do not report success.
            val status = retrofit.create(EventsApi::class.java).cancel(eventId).eventBody().asObjectOrInvalid().str("status")
            if (!status.equals("CANCELLED", ignoreCase = true)) throw AccountFailure.InvalidResponse
        }
    }

    override suspend fun checkIn(code: String): CheckInResultValue {
        // The shape is fixed by the contract; anything else is a 422 the server would only repeat.
        if (!CheckInCode.isWellFormed(code)) throw AccountFailure.InvalidInput
        val body = JsonObject().apply { addProperty("token", code) }
        return product.perform { retrofit ->
            val json = retrofit.create(EventsApi::class.java).checkIn(body).eventBody().asObjectOrInvalid()
            CheckInResultValue(json.str("event_id"), json.bool("checked_in"), json.bool("already_checked_in"))
        }
    }

    override suspend fun calendar(eventId: String): ByteArray {
        requireId(eventId)
        return product.perform { retrofit ->
            val response = retrofit.create(EventsApi::class.java).calendar(eventId)
            if (!response.isSuccessful) { response.errorBody()?.close(); throw failureForStatus(response.code()) }
            val bytes = response.body()?.use { it.source().let { source -> if (source.request(CALENDAR_BYTE_LIMIT + 1L)) null else source.readByteArray() } }
            // A body over the cap, or one that is not an iCalendar file, is not an event file.
            if (bytes == null || bytes.isEmpty() || !String(bytes, Charsets.UTF_8).trimStart().startsWith("BEGIN:VCALENDAR")) throw AccountFailure.InvalidResponse
            bytes
        }
    }

    // MARK: helpers

    /** A malformed id is a 404 server-side; refuse it before it becomes a path segment. */
    private fun requireId(id: String) {
        if (id.length != 36 || runCatching { UUID.fromString(id) }.isFailure) throw AccountFailure.InvalidInput
    }

    private fun Response<JsonElement>.eventBody(): JsonElement = successBody()

    private fun Response<JsonElement>.eventsBody(): List<DonorEvent> =
        eventBody().asObjectOrInvalid().array("events").map { mapEvent(it.asObjectOrInvalid()) }

    internal companion object {
        /** Defensive caps: a longer list is truncated rather than trusted; a larger calendar file is refused. */
        const val ROW_LIMIT = 200
        const val CALENDAR_BYTE_LIMIT = 64 * 1024

        fun mapEvent(json: JsonObject): DonorEvent {
            val capacity = json.long("capacity").toIntOrInvalid()
            val status = when (json.optStr("reservation_status")?.uppercase()) {
                "RESERVED" -> ReservationStatus.RESERVED
                "WAITLISTED" -> ReservationStatus.WAITLISTED
                // No place, or a status a newer server added: nothing the screen can act on.
                else -> ReservationStatus.NONE
            }
            return DonorEvent(
                id = json.str("id"), slug = json.str("slug"), title = json.str("title"),
                type = json.optStr("type")?.lowercase().orEmpty(),
                venue = json.optStr("venue").orEmpty(), city = json.optStr("city").orEmpty(),
                startsAt = json.time("starts_at").toDate(), endsAt = json.time("ends_at").toDate(),
                capacity = capacity, spotsLeft = json.optLong("spots_left")?.clampToInt() ?: 0,
                status = status,
                invitationNote = json.optStr("invitation_note"),
                reportIssueUrl = json.optStr("report_issue_url"),
                cancelled = json.bool("cancelled"),
                bookingOpensAt = json.optTime("booking_opens_at")?.toDate(),
                earlyBooking = json.bool("early_booking"),
                timeZone = json.optStr("time_zone") ?: "UTC",
                checkedInAt = json.optTime("checked_in_at")?.toDate(),
                details = json.optStr("description").orEmpty(), address = json.optStr("address").orEmpty(),
            )
        }
    }
}

private fun java.time.OffsetDateTime.toDate(): Date = Date.from(toInstant())
