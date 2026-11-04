package org.dad.foundation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The chip row composed alone, the only guard that the chips are a radio group and that Other gates the stepper. */
class GiftAmountChipsTest {
    @get:Rule val compose = createComposeRule()

    private fun show(initial: Int) {
        compose.setContent {
            var dollars by androidx.compose.runtime.remember { mutableIntStateOf(initial) }
            GiftAmountChips(dollars, enabled = true, onSelect = { dollars = it.coerceIn(GiftAmount.MIN_DAILY_DOLLARS, GiftAmount.MAX_DAILY_DOLLARS) })
        }
    }

    @Test fun chipsAreRadioButtonsAndTheMatchingPresetIsSelected() {
        show(3)
        for (tag in listOf("gift-chip-1", "gift-chip-3", "gift-chip-5", "gift-chip-other")) {
            compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
        compose.onNodeWithTag("gift-chip-3").assertIsSelected()
        compose.onNodeWithTag("gift-chip-1").assertIsNotSelected()
        compose.onNodeWithTag("gift-chip-other").assertIsNotSelected()
        assertTrue(compose.onAllNodes(hasTestTag("gift-increase")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun otherRevealsTheStepperWithinBounds() {
        show(GiftAmount.MAX_DAILY_DOLLARS)
        compose.onNodeWithTag("gift-chip-other").assertIsSelected()
        compose.onNodeWithTag("gift-increase").assertIsNotEnabled()
        compose.onNodeWithTag("gift-decrease").assertIsEnabled().performClick()
        compose.onNodeWithTag("gift-increase").assertIsEnabled()
        compose.onNodeWithTag("gift-chip-1").performClick()
        compose.onNodeWithTag("gift-chip-1").assertIsSelected()
        compose.onNodeWithTag("gift-chip-other").performClick()
        compose.onNodeWithTag("gift-decrease").assertIsNotEnabled()
        compose.onNodeWithTag("gift-increase").performClick()
        compose.onNodeWithTag("gift-chip-other").assertIsSelected()
    }
}
