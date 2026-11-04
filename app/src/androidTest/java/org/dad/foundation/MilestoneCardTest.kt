package org.dad.foundation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The milestone card composed directly: shown with its line, dismissed, and shared. */
class MilestoneCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsTheLineAndDismissHidesTheCard() {
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            if (visible) MilestoneCard(milestoneLine(30), onDismiss = { visible = false }, onShare = null)
        }
        compose.onNodeWithTag("milestone-card").assertIsDisplayed()
        compose.onNode(hasText("30 days. That's a month of aid, on the record.")).assertIsDisplayed()
        compose.onNodeWithTag("milestone-dismiss").performClick()
        compose.onNodeWithTag("milestone-card").assertDoesNotExist()
    }

    @Test fun shareIsInvoked() {
        val shared = mutableListOf<String>()
        compose.setContent { MilestoneCard(milestoneLine(7), onDismiss = {}, onShare = { shared += "x" }) }
        compose.onNodeWithTag("milestone-share").performClick()
        assertEquals(1, shared.size)
    }
}
