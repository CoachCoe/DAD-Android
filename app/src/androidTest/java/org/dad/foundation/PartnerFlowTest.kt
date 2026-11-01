package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Partner portal journey over the preview service (launched via preview_partner[/_viewer] extras): verified-or-hidden
 * Overview figures (§7.3) and admin-vs-viewer capability gating (§7.2). Users and invites live on the Organization tab;
 * the portal is a long scrolling column, so controls below the fold are scrolled into view before assertion.
 */
class PartnerFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(extra: String): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(extra, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch(intent)
    }

    private fun openOrganization() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("partner-tab-organization")).fetchSemanticsNodes().isNotEmpty() }
        compose.tapSafely("partner-tab-organization")
    }

    @Test fun adminPortalShowsVerifiedFiguresAndCanManage() {
        launch("preview_partner").use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("partner-committed")).fetchSemanticsNodes().isNotEmpty() }
            // Verified figures render; the unverified "deployed" figure is intentionally ABSENT (§7.3).
            compose.onNodeWithTag("partner-committed").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("partner-reached").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("partner-verified-line").performScrollTo().assertIsDisplayed()
            assert(compose.onAllNodes(hasTestTag("partner-deployed")).fetchSemanticsNodes().isEmpty())
            openOrganization()
            compose.onNodeWithTag("partner-invite").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun inviteNeedsANameAndAValidEmailAndAddsTheUser() {
        launch("preview_partner").use {
            openOrganization()
            compose.onNodeWithTag("partner-invite").performScrollTo().assertIsNotEnabled()
            compose.enterText("partner-invite-name", "New Person")
            compose.enterText("partner-invite-email", "not-an-email")
            compose.onNodeWithTag("partner-invite").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("partner-invite-email").performTextClearance()
            compose.enterText("partner-invite-email", "new.person@acme.com")
            compose.onNodeWithTag("partner-invite").performScrollTo().assertIsEnabled()
            compose.tapSafely("partner-invite")
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("New Person · Viewer")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun viewerIsReadOnly() {
        launch("preview_partner_viewer").use {
            openOrganization()
            // A viewer sees the read-only notice and NO invite button (§7.2 gating).
            compose.onNodeWithTag("partner-readonly").performScrollTo().assertIsDisplayed()
            assert(compose.onAllNodes(hasTestTag("partner-invite")).fetchSemanticsNodes().isEmpty())
            assert(compose.onAllNodes(hasTestTag("partner-export")).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun removeNeedsConfirmationAndCanBeCancelled() {
        launch("preview_partner").use {
            openOrganization()
            compose.tapSafely("partner-remove-u2")
            compose.onNodeWithTag("partner-remove-confirm-u2").performScrollTo().assertIsDisplayed()
            // The user is still listed until the confirm; Keep backs out.
            compose.tapSafely("partner-remove-keep-u2")
            compose.onNodeWithTag("partner-remove-u2").performScrollTo().assertIsDisplayed()
            compose.tapSafely("partner-remove-u2")
            compose.tapSafely("partner-remove-confirm-u2")
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Val Viewer · Viewer")).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test fun adminHasNoRemoveControlForThemselves() {
        launch("preview_partner").use {
            openOrganization()
            compose.onNodeWithTag("partner-invite").performScrollTo()
            assert(compose.onAllNodes(hasTestTag("partner-remove-u1")).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithText("Ada Admin (you) · Admin").performScrollTo().assertIsDisplayed()
        }
    }
}
