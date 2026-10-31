package org.dad.foundation

import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The events journey end to end through the REAL [WireEvents] conformer, [AccountSession] and Retrofit stack,
 * over the DEBUG scripted transport (`stub_backend` with a stored session). Scenario extras:
 * `stub_events_invited` (the invite-only event is returned), `stub_events_fail_first` (the first list read is a
 * 503), `stub_events_not_eligible` (every reserve is a 403). See [StubEvents] for the scripted events.
 */
class EventsWiredFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(vararg extras: String): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("stub_backend", true).putExtra("stub_signed_in", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras.forEach { intent.putExtra(it, true) }
        DebugBackend.resetRequestLog()
        return ActivityScenario.launch(intent)
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) { exists(tag) }
    private fun waitGone(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) { !exists(tag) }
    private fun tap(tag: String) {
        waitFor(tag)
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun textOf(tag: String, text: String) = compose.waitUntil(20_000) {
        runCatching { compose.onNodeWithTag(tag).assertTextContains(text, substring = true) }.isSuccess
    }

    private fun openEvents() {
        waitFor("open-events")
        compose.waitUntil(20_000) { !exists("dashboard-loading") && !exists("initiative-loading") }
        compose.waitForIdle()
        tap("open-events")
        waitFor("events-home")
    }

    private fun openEvent(id: String) {
        waitFor("open-event-$id")
        tap("open-event-$id")
        waitFor("event-detail")
        waitFor("event-title")
    }

    private fun backToList() {
        tap("event-back")
        waitFor("events-home")
    }

    @Test fun browseListThenDetailThenReserveThenCancel() {
        launch().use {
            openEvents()
            waitFor("open-event-${StubEvents.PARK}")
            // The time shows in the venue's zone, with the zone named (E-3).
            compose.waitUntil(20_000) {
                runCatching { compose.onNodeWithTag("event-start-${StubEvents.PARK}").assertTextContains("CST", substring = true) }.isSuccess ||
                    runCatching { compose.onNodeWithTag("event-start-${StubEvents.PARK}").assertTextContains("CDT", substring = true) }.isSuccess
            }
            openEvent(StubEvents.PARK)
            textOf("event-time", "C")
            textOf("event-spots", "5 spots left")
            tap("reserve-${StubEvents.PARK}")
            waitFor("reserve-confirm")
            waitFor("going-${StubEvents.PARK}")
            textOf("event-spots", "4 spots left")
            assertTrue(DebugBackend.requestLog.contains("POST /donor/me/events/${StubEvents.PARK}/reserve"))
            // Cancel keeps a prior state until confirmed, then releases the seat.
            tap("cancel-${StubEvents.PARK}")
            waitFor("cancel-confirm-${StubEvents.PARK}")
            tap("cancel-keep")
            waitGone("cancel-confirm-${StubEvents.PARK}")
            waitFor("going-${StubEvents.PARK}")
            tap("cancel-${StubEvents.PARK}")
            tap("cancel-confirm-go")
            waitFor("reserve-${StubEvents.PARK}")
            textOf("event-spots", "5 spots left")
            assertTrue(DebugBackend.requestLog.contains("POST /donor/me/events/${StubEvents.PARK}/cancel"))
        }
    }

    @Test fun aFullEventJoinsTheWaitlist() {
        launch().use {
            openEvents()
            openEvent(StubEvents.FOOD)
            textOf("event-spots", "Full")
            tap("waitlist-${StubEvents.FOOD}")
            waitFor("waitlisted-${StubEvents.FOOD}")
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("on the waitlist", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            backToList()
            textOf("event-status-${StubEvents.FOOD}", "On the waitlist")
        }
    }

    @Test fun theInviteOnlyEventIsHiddenUnlessTheServerReturnsIt() {
        launch().use {
            openEvents()
            waitFor("open-event-${StubEvents.PARK}")
            assertTrue(!exists("open-event-${StubEvents.DINNER}"))
        }
        launch("stub_events_invited").use {
            openEvents()
            waitFor("open-event-${StubEvents.DINNER}")
            waitFor("invite-only-${StubEvents.DINNER}")
            openEvent(StubEvents.DINNER)
            textOf("invitation-note", "Invitation earned")
        }
    }

    @Test fun checkInInsideTheWindowWithTheRightCode() {
        launch().use {
            openEvents()
            openEvent(StubEvents.LIVE)
            waitFor("checkin-code")
            // A wrong (well formed) code is refused with the reason, and nothing is checked in.
            compose.onNodeWithTag("checkin-code").performScrollTo().performTextInput(StubEvents.WRONG_TOKEN)
            tap("checkin-submit")
            waitFor("checkin-error")
            textOf("checkin-error", "didn’t work")
            // A malformed code never reaches the server.
            val before = DebugBackend.requestLog.count { it == "POST /donor/me/events/check-in" }
            compose.onNodeWithTag("checkin-code").performScrollTo().performTextReplacement("short")
            tap("checkin-submit")
            textOf("checkin-error", "66 characters")
            assertTrue(before == DebugBackend.requestLog.count { it == "POST /donor/me/events/check-in" })
            // The real code checks in.
            compose.onNodeWithTag("checkin-code").performScrollTo().performTextReplacement(StubEvents.TOKEN)
            tap("checkin-submit")
            waitFor("checkedin-${StubEvents.LIVE}")
            waitGone("checkin-code")
            // Checked-in survives a reload of the list (checked_in_at comes back from the server).
            backToList()
            tap("events-back")
            waitFor("open-events")
            tap("open-events")
            textOf("event-status-${StubEvents.LIVE}", "Checked in")
        }
    }

    @Test fun checkInOutsideTheWindowIsNotOffered() {
        launch().use {
            openEvents()
            openEvent(StubEvents.LATER)
            waitFor("checkin-closed")
            textOf("checkin-closed", "opens when the event starts")
            assertTrue(!exists("checkin-code"))
            // New York venue: the zone is the venue's.
            textOf("event-time", "E")
        }
    }

    @Test fun addToCalendarOffersTheShareChooser() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER), null, false)
        try {
            launch().use {
                openEvents()
                openEvent(StubEvents.LATER)
                tap("add-calendar")
                compose.waitUntil(20_000) { DebugBackend.requestLog.contains("GET /donor/me/events/${StubEvents.LATER}/calendar.ics") }
                val chooser: android.app.Activity? = monitor.waitForActivityWithTimeout(15_000)
                assertNotNull("the share sheet should open", chooser)
                assertTrue(DebugBackend.requestLog.toString(), DebugBackend.requestLog.contains("GET /donor/me/events/${StubEvents.LATER}/calendar.ics"))
                chooser?.finish()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun aDonorWhoIsNotEligibleIsToldSo() {
        launch("stub_events_not_eligible").use {
            openEvents()
            openEvent(StubEvents.PARK)
            tap("reserve-${StubEvents.PARK}")
            waitFor("events-action-error")
            textOf("events-action-error", "not eligible")
            waitFor("reserve-${StubEvents.PARK}")
        }
    }

    @Test fun aFailedLoadOffersARetry() {
        launch("stub_events_fail_first").use {
            openEvents()
            waitFor("events-error")
            tap("events-retry")
            waitFor("open-event-${StubEvents.PARK}")
            waitGone("events-error")
        }
    }
}
