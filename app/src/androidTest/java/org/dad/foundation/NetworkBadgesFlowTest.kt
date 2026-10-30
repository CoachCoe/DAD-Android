package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Network & badges journey (Phase 5). Launched via the `preview_network` extra so it exercises the
 * signed-in network/badges screen directly without disturbing the existing preview tests. Every
 * surface renders inline (no dialogs), so each node is queryable in-tree. Covers the DoD cases:
 * badge earned/greyed rendering, anonymized network count, and the sharing opt-in minting links.
 */
class NetworkBadgesFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("preview_network", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch(intent)
    }

    @Test fun networkHomeRendersCountAndAnonymizedReferrals() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("network-home")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("network-home").assertIsDisplayed()
            // Active count + combined annualized + the "N more anonymously" line (never a name).
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("network-active")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("network-active").assertIsDisplayed()
            compose.onNodeWithTag("network-annualized").assertIsDisplayed()
            compose.onNodeWithTag("network-anonymous").assertIsDisplayed()
        }
    }

    @Test fun badgeGridRendersEarnedAndLockedStates() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("badge-summary")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("badge-summary").performScrollTo().assertIsDisplayed()
            // An earned badge (Founding Donor) and a locked badge (Legacy) both render — state is
            // carried in the tile tag + the a11y label, not colour alone (B-1). The badge grid is a
            // non-lazy Column so every tile composes; FOUNDING_DONOR is in the viewport, while LEGACY
            // (the 24th tile) is scrolled off-screen, so it is asserted present (assertExists) rather
            // than displayed — both prove the tile is in the tree with its locked state in text.
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("badge-earned-FOUNDING_DONOR")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("badge-earned-FOUNDING_DONOR").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("badge-locked-LEGACY").assertExists()
        }
    }

    @Test fun sharingOptInMintsLinkInline() {
        launch().use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("network-home")).fetchSemanticsNodes().isNotEmpty() }
            // Starts anonymous: the opt-in button is shown, no link yet.
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("sharing-optin")).fetchSemanticsNodes().isNotEmpty() }
            // No name typed yet: opting in is disabled.
            compose.onNodeWithTag("sharing-optin").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("sharing-name").performScrollTo().performTextInput("Alex C.")
            compose.onNodeWithTag("sharing-optin").performScrollTo().assertIsEnabled()
            // Opt in — inline, deterministic (no dialog): the share link appears.
            compose.onNodeWithTag("sharing-optin").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("sharing-link")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("sharing-link").performScrollTo().assertIsDisplayed()
        }
    }
}
