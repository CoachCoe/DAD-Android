package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Events journey (Phase 6). Launched via the `preview_events` extra so it exercises the signed-in
 * events screen directly without disturbing the other preview tests. Every surface renders inline
 * (no dialogs). Covers: list + invite-only marker (E-5), reserve -> inline confirmation (E-4),
 * and the inline cancel confirmation. The camera check-in (E-8) is a device seam, unit-tested.
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

    @Test fun eventsHomeRendersListAndInviteOnlyMarker() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("events-home")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("events-home").assertIsDisplayed()
            // A reservable public event offers Reserve; the full one offers Join waitlist (E-4).
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("reserve-e1")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reserve-e1").assertIsDisplayed()
            compose.onNodeWithTag("waitlist-e2").assertIsDisplayed()
            // The invite-only event carries a visible marker (E-5).
            compose.onNodeWithTag("invite-only-e3").assertIsDisplayed()
        }
    }

    @Test fun eachEventShowsItsStartDateAndTime() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("event-start-e1")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("event-start-e1").assertIsDisplayed()
        }
    }

    @Test fun reserveShowsInlineConfirmation() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("reserve-e1")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reserve-e1").performClick()
            // Confirmation renders inline — no action sheet.
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("reserve-confirm")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reserve-confirm").assertIsDisplayed()
        }
    }

    @Test fun cancelUsesInlineConfirmation() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("reserve-e1")).fetchSemanticsNodes().isNotEmpty() }
            // Reserve first so the row offers Cancel.
            compose.onNodeWithTag("reserve-e1").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("reserve-confirm")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Done").performClick()
            // Now the reserved event offers Cancel; tapping shows the INLINE confirm block.
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("cancel-e1")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("cancel-e1").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("cancel-confirm-e1")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("cancel-confirm-e1").assertIsDisplayed()
            compose.onNodeWithText("Keep it").assertIsDisplayed()
        }
    }
}
