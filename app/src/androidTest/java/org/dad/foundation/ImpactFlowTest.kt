package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Impact home journey (Phase 4). Launched via the `preview_impact` extra so it exercises the
 * signed-in impact screen directly without disturbing the existing donor-landing preview tests.
 * The rankings opt-out toggle renders inline (no dialog), so every node is queryable in-tree.
 */
class ImpactFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("preview_impact", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch(intent)
    }

    @Test fun impactHomeRendersTotalsAndStreak() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("impact-home")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("impact-home").assertIsDisplayed()
            // Lifetime total and streak are the known preview values.
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("impact-given")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("impact-given").assertIsDisplayed()
            compose.onNodeWithTag("streak-count").assertIsDisplayed()
            compose.onNodeWithTag("today-title").assertIsDisplayed()
        }
    }

    @Test fun rankingOptOutHidesPositionInline() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("impact-home")).fetchSemanticsNodes().isNotEmpty() }
            // Starts opted-in: a national position is shown.
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("ranking-national")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ranking-national").assertIsDisplayed()
            // Opt out — inline, deterministic (no dialog).
            compose.onNodeWithTag("ranking-optout").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("ranking-optedout")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ranking-optedout").assertIsDisplayed()
        }
    }
}
