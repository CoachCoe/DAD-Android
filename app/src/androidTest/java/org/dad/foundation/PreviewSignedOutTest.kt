package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/** The session-less preview launch lands on sign-in (role select), never on the signed-in shell. */
class PreviewSignedOutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun signedOutLaunchShowsRoleSelect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra("preview_signed_out", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("role-select")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("role-select").assertIsDisplayed()
        }
    }
}
