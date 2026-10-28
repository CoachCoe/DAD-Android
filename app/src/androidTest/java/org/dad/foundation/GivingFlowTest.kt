package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Giving journey (Phase 3). Launched via the `preview_giving` extra so it exercises the
 * signed-in GiftScreen directly without disturbing the existing donor-landing preview tests.
 * The cancel confirmation is inline (no OS dialog), so every node is queryable in-tree.
 */
class GivingFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("preview_giving", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch(intent)
    }

    @Test fun giftScreenRendersAndSteps() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("gift-screen")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("gift-screen").assertIsDisplayed()
            // Preview starts at the $1/day floor.
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("$1/day")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("gift-daily").assertIsDisplayed()
            compose.onNodeWithTag("gift-increase").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("$2/day")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("$2/day").assertIsDisplayed()
        }
    }

    @Test fun cancelConfirmationAppearsInline() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("gift-cancel")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("gift-cancel").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("cancel-confirm")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("cancel-confirm").assertIsDisplayed()
            compose.onNodeWithText("Keep giving").assertIsDisplayed()
            compose.onNodeWithText("End my gift").assertIsDisplayed()
        }
    }
}
