package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Events screens against the in-memory preview service (`preview_events` extra), so they render without a
 * session. Every surface is inline (no dialogs). The wired journey over the real conformer is [EventsWiredFlowTest].
 */
class EventsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("preview_events", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch(intent)
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String, timeout: Long = 10_000) = compose.waitUntil(timeout) { exists(tag) }

    @Test fun eventsHomeRendersListAndInviteOnlyMarker() {
        launch().use {
            waitFor("events-home")
            compose.onNodeWithTag("events-home").assertIsDisplayed()
            waitFor("open-event-e1")
            compose.onNodeWithTag("event-start-e1").assertIsDisplayed()
            // The invite-only event carries a visible marker (E-5).
            compose.onNodeWithTag("invite-only-e3").assertIsDisplayed()
        }
    }

    @Test fun reserveShowsInlineConfirmationOnTheDetail() {
        launch().use {
            waitFor("open-event-e1")
            compose.onNodeWithTag("open-event-e1").performClick()
            waitFor("reserve-e1")
            compose.onNodeWithTag("event-time").assertIsDisplayed()
            compose.onNodeWithTag("reserve-e1").performClick()
            waitFor("reserve-confirm")
            compose.onNodeWithTag("reserve-confirm").assertIsDisplayed()
        }
    }

    @Test fun aFullEventOffersTheWaitlistAndCancelUsesInlineConfirmation() {
        launch().use {
            waitFor("open-event-e2")
            compose.onNodeWithTag("open-event-e2").performClick()
            waitFor("waitlist-e2")
            compose.onNodeWithTag("waitlist-e2").performClick()
            waitFor("waitlisted-e2")
            compose.onNodeWithTag("cancel-e2").performScrollTo().performClick()
            waitFor("cancel-confirm-e2")
            compose.onNodeWithText("Keep it").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Leave the waitlist").performScrollTo().performClick()
            // The place is released, so the event has a spot again and offers Reserve.
            waitFor("reserve-e2")
        }
    }
}
