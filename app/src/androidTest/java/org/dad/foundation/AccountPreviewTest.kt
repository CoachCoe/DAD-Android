package org.dad.foundation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun syntheticAccountLoads() {
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Welcome, Alex")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Welcome, Alex").assertIsDisplayed()
        compose.onNodeWithText("Preview · Sample account").assertIsDisplayed()
    }
}
