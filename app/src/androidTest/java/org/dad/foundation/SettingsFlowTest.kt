package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Settings journey (Phase 7). Launched via the `preview_settings` extra. Covers: the screen
 * renders profile + toggles, the delete-account confirm gate stays disabled until the exact word
 * is typed (store-mandated, §6.12), and sign-out leaves the shell. All inline (no OS dialogs).
 * The settings column is long and scrollable, so controls are scrolled into view before use.
 */
class SettingsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("preview_settings", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch(intent)
    }

    @Test fun settingsRendersProfileAndToggles() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("settings-home")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("settings-email").assertIsDisplayed()
            // A push toggle and the ranking switch are further down the scroll — scroll to them.
            compose.onNodeWithTag("pref-push_streak_at_risk").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("ranking-toggle").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun deleteGateStaysDisabledUntilConfirmWordTyped() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("settings-home")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("delete-start").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("delete-confirm")).fetchSemanticsNodes().isNotEmpty() }
            // Disabled with no / wrong text.
            compose.onNodeWithTag("delete-confirm").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("delete-input").performScrollTo().performTextInput("DELETE")
            // Enabled once the exact word is present.
            compose.onNodeWithTag("delete-confirm").performScrollTo().assertIsEnabled()
        }
    }

    @Test fun signOutLeavesTheShell() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("settings-signout")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("settings-signout").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("settings-signed-out")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("settings-signed-out").assertIsDisplayed()
        }
    }
}
