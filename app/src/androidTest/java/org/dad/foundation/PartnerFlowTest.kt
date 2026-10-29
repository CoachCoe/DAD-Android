package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Partner portal journey (Phase 8). Launched via dedicated preview_partner[/_viewer/_pending]
 * extras. Covers the DoD cases: verified-or-hidden Overview figures (§7.3), admin-vs-viewer
 * capability gating (§7.2), and the pending-approval gate (P-2). The portal is a long scrolling
 * column, so controls below the fold are scrolled into view before assertion.
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

    @Test fun adminPortalShowsVerifiedFiguresAndCanManage() {
        launch("preview_partner").use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("partner-portal")).fetchSemanticsNodes().isNotEmpty() }
            // Verified figures render; the unverified "deployed" figure is intentionally ABSENT (§7.3).
            compose.onNodeWithTag("partner-committed").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("partner-reached").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("partner-verified-line").performScrollTo().assertIsDisplayed()
            // Admin can manage users.
            compose.onNodeWithTag("partner-invite").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun viewerIsReadOnly() {
        launch("preview_partner_viewer").use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("partner-portal")).fetchSemanticsNodes().isNotEmpty() }
            // A viewer sees the read-only notice and NO invite button (§7.2 gating).
            compose.onNodeWithTag("partner-readonly").performScrollTo().assertIsDisplayed()
            assert(compose.onAllNodes(hasTestTag("partner-invite")).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun pendingApprovalShowsOnlyTheGate() {
        launch("preview_partner_pending").use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("partner-portal")).fetchSemanticsNodes().isNotEmpty() }
            // P-2: pending shows the pending notice and NO portal data.
            compose.onNodeWithTag("partner-pending").assertIsDisplayed()
            assert(compose.onAllNodes(hasTestTag("partner-committed")).fetchSemanticsNodes().isEmpty())
        }
    }
}
