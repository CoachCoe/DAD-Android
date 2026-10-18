package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class AccountRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun unavailableStateCanRecover() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra("preview_unavailable", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Try again")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Try again").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Welcome, Alex")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Welcome, Alex").assertIsDisplayed()
        }
    }
}
