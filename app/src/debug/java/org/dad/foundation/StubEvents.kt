package org.dad.foundation

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The DEBUG stub backend's events routes: a few stateful events behind the REAL [WireEvents] conformer, shaped like
 * the contract's responses (including the 2.7.0 `time_zone` and `checked_in_at`). Times are relative to now, so the
 * check-in window is genuinely open for one event and genuinely closed for another. Debug source set only.
 *
 * - PARK: public, 5 spots, not yet reserved (reserve, cancel).
 * - FOOD: public and full (a reserve joins the waitlist).
 * - LIVE: under way and already reserved by the donor, so check-in is offered. [TOKEN] checks in; any other
 *   well-formed code is a 422.
 * - LATER: reserved but starts in two days, so check-in is closed.
 * - DINNER: invite-only; returned (and readable) only under [StubEventsScenario.INVITED].
 *
 * [StubEventsScenario.FAIL_FIRST] answers the first list read with a 503; [StubEventsScenario.NOT_ELIGIBLE] answers
 * every reserve with a 403.
 */
internal class StubEvents(private val scenario: StubEventsScenario) {
    private class Row(
        val id: String, val slug: String, val title: String, val type: String, val startsAt: Instant, val endsAt: Instant,
        val capacity: Int, var spotsLeft: Int, var status: String?, val zone: String = "America/Chicago",
        val note: String? = null, var checkedInAt: Instant? = null, val details: String = "",
    )

    private val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private val rows = listOf(
        Row(PARK, "park-cleanup", "Park Cleanup", "VOLUNTEER", now.plus(3, ChronoUnit.DAYS), now.plus(3, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS),
            20, 5, null, details = "Bring gloves and water. We provide bags."),
        Row(FOOD, "food-bank", "Food Bank Night", "COMMUNITY", now.plus(4, ChronoUnit.DAYS), now.plus(4, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS), 10, 0, null),
        Row(LIVE, "live-now", "Neighborhood Meal Prep", "VOLUNTEER", now.minus(10, ChronoUnit.MINUTES), now.plus(2, ChronoUnit.HOURS), 12, 3, "RESERVED"),
        Row(LATER, "later-on", "Garden Day", "VOLUNTEER", now.plus(2, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS).plus(4, ChronoUnit.HOURS), 8, 2, "RESERVED", zone = "America/New_York"),
        Row(DINNER, "founders-dinner", "Founders Dinner", "INVITE_ONLY", now.plus(5, ChronoUnit.DAYS), now.plus(5, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS), 20, 6, null,
            note = "Invitation earned at Founding Donor"),
    )
    private var failFirst = scenario == StubEventsScenario.FAIL_FIRST

    private fun visible(row: Row) = row.id != DINNER || scenario == StubEventsScenario.INVITED
    private fun find(id: String) = rows.firstOrNull { it.id == id && visible(it) }

    private fun json(row: Row): String {
        val spots = if (row.capacity == 0) "null" else row.spotsLeft.toString()
        val status = row.status?.let { "\"$it\"" } ?: "null"
        // One event carries fractional seconds, as the server may send them.
        val startsAt = if (row.id == PARK) row.startsAt.plusMillis(250).toString() else row.startsAt.toString()
        return """{"id":"${row.id}","slug":"${row.slug}","title":"${row.title}","type":"${row.type}","description":"${row.details}","venue":"Community Hall",""" +
            """"address":"100 Main St","city":"Austin","starts_at":"$startsAt","ends_at":"${row.endsAt}","time_zone":"${row.zone}",""" +
            """"capacity":${row.capacity},"spots_left":$spots,"reservation_status":$status,"checked_in_at":${row.checkedInAt?.let { "\"$it\"" } ?: "null"},""" +
            """"invitation_note":${row.note?.let { "\"$it\"" } ?: "null"},"report_issue_url":"mailto:help@dad.example","cancelled":false,""" +
            """"booking_opens_at":null,"early_booking":false}"""
    }

    fun answer(method: String, path: String, body: String): Pair<Int, String> {
        val rest = path.removePrefix("/donor/me/events")
        return when {
            method == "GET" && rest.isEmpty() ->
                if (failFirst) { failFirst = false; 503 to """{"detail":"unavailable"}""" }
                else 200 to """{"events":[${rows.filter { it.id != DINNER }.joinToString(",") { json(it) }}]}"""
            method == "GET" && rest == "/invite-only" ->
                200 to """{"events":[${rows.filter { it.id == DINNER && visible(it) }.joinToString(",") { json(it) }}]}"""
            method == "POST" && rest == "/check-in" -> checkIn(body)
            method == "GET" && rest.endsWith("/calendar.ics") -> {
                val row = find(rest.removePrefix("/").removeSuffix("/calendar.ics"))
                if (row == null) 404 to "{}"
                else 200 to "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:${row.id}\r\nSUMMARY:${row.title}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
            }
            method == "GET" -> find(rest.removePrefix("/"))?.let { 200 to json(it) } ?: (404 to "{}")
            method == "POST" && rest.endsWith("/reserve") -> reserve(rest.removePrefix("/").removeSuffix("/reserve"))
            method == "POST" && rest.endsWith("/cancel") -> cancel(rest.removePrefix("/").removeSuffix("/cancel"))
            else -> 404 to """{"detail":"not found"}"""
        }
    }

    private fun reserve(id: String): Pair<Int, String> {
        if (scenario == StubEventsScenario.NOT_ELIGIBLE) return 403 to "{}"
        val row = find(id) ?: return 404 to "{}"
        if (row.status != null || !row.startsAt.isAfter(now)) return 409 to "{}"
        return if (row.spotsLeft > 0) { row.spotsLeft--; row.status = "RESERVED"; 200 to """{"status":"RESERVED"}""" }
        else { row.status = "WAITLISTED"; 200 to """{"status":"WAITLISTED"}""" }
    }

    private fun cancel(id: String): Pair<Int, String> {
        val row = find(id) ?: return 404 to "{}"
        if (row.status == "RESERVED") row.spotsLeft++
        row.status = null
        return 200 to """{"status":"CANCELLED"}"""
    }

    private fun checkIn(body: String): Pair<Int, String> {
        val token = Regex("\"token\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        val live = rows.first { it.id == LIVE }
        if (token != TOKEN || live.status != "RESERVED") return 422 to "{}"
        val already = live.checkedInAt != null
        if (!already) live.checkedInAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        return 200 to """{"event_id":"$LIVE","checked_in":true,"already_checked_in":$already}"""
    }

    companion object {
        const val PARK = "a1111111-1111-4111-8111-111111111111"
        const val FOOD = "a2222222-2222-4222-8222-222222222222"
        const val LIVE = "a3333333-3333-4333-8333-333333333333"
        const val LATER = "a4444444-4444-4444-8444-444444444444"
        const val DINNER = "a5555555-5555-4555-8555-555555555555"
        /** The one code the stub accepts: 22 + "." + 43 base64url characters, like the contract's token. */
        val TOKEN = "a".repeat(22) + "." + "b".repeat(43)
        /** Well formed, but not a code for anything. */
        val WRONG_TOKEN = "c".repeat(22) + "." + "d".repeat(43)
    }
}
