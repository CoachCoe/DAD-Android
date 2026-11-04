package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import android.accessibilityservice.AccessibilityService
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The partner portal driven through the REAL Wire conformers over the DEBUG scripted transport (`stub_backend`), where the
 * stub answers like the server: a viewer's writes are 403, removing yourself or the last admin is 409, an invite for a
 * taken address is 409, and a stored application receipt is recognized only when the launch seeded one.
 */
class PartnerWiredFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(vararg extras: String): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra("stub_backend", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras.forEach { intent.putExtra(it, true) }
        return ActivityScenario.launch(intent)
    }

    private fun waitFor(tag: String, timeout: Long = 15_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun absent(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()

    private fun openOrganization() {
        waitFor("partner-tab-organization")
        compose.tapSafely("partner-tab-organization")
    }

    private fun openApplicantSignIn() {
        waitFor("role-partner")
        compose.tapSafely("role-partner")
        waitFor("partner-sign-in-email")
    }

    @Test fun aViewerSeesNoAdminActionsAndTheServerWouldRefuseThemAnyway() {
        launch("stub_partner_signed_in", "stub_partner_viewer").use {
            waitFor("partner-committed")
            openOrganization()
            compose.onNodeWithTag("partner-readonly").performScrollTo().assertIsDisplayed()
            assertTrue(absent("partner-invite"))
            assertTrue(absent("partner-export"))
            assertTrue(absent("partner-profile-save"))
            assertTrue(absent("partner-remove-u1"))
            assertTrue(absent("partner-remove-u2"))
        }
    }

    @Test fun anInviteForATakenAddressIsExplainedThenAValidOneAddsTheUser() {
        launch("stub_partner_signed_in").use {
            openOrganization()
            compose.enterText("partner-invite-name", "Taken Person")
            compose.enterText("partner-invite-email", "taken@acme.com")
            compose.tapSafely("partner-invite")
            waitFor("partner-action-error")
            compose.onNodeWithTag("partner-action-error").performScrollTo()
                .assertTextContains("That email already signs in to DAD with another role or organization.")
            compose.enterText("partner-invite-name", "New Person")
            compose.enterText("partner-invite-email", "new.person@acme.com")
            compose.tapSafely("partner-invite")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("New Person · Viewer")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun removingAUserNeedsConfirmationAndTheAdminCannotRemoveThemselves() {
        launch("stub_partner_signed_in").use {
            openOrganization()
            waitFor("partner-remove-u2")
            assertTrue(absent("partner-remove-u1"))
            compose.tapSafely("partner-remove-u2")
            compose.tapSafely("partner-remove-confirm-u2")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Val Viewer · Viewer")).fetchSemanticsNodes().isEmpty() }
            // Ada is now the only user and the last admin: nothing is left to remove.
            assertTrue(absent("partner-remove-u1"))
        }
    }

    @Test fun anExportIsFetchedAndListedAndHandedToTheShareChooser() {
        DebugBackend.resetRequestLog()
        launch("stub_partner_signed_in").use {
            openOrganization()
            compose.tapSafely("partner-export")
            compose.waitUntil(15_000) { "GET /donor/partner/report.pdf" in DebugBackend.requestLog }
            // The chooser takes over the screen; put the app back in front before the scenario closes.
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        assertTrue(DebugBackend.requestLog.count { it == "GET /donor/partner/exports" } >= 2)
    }

    @Test fun aPendingApplicationShowsItsStatusAboveSignIn() {
        launch("stub_application_pending").use {
            openApplicantSignIn()
            waitFor("partner-application-status")
            compose.onNodeWithTag("partner-application-message").assertTextContains("being reviewed", substring = true)
        }
    }

    @Test fun anApprovedAndARejectedApplicationSaySoAndAnUnknownStateIsGeneric() {
        mapOf(
            "stub_application_approved" to "is approved",
            "stub_application_rejected" to "wasn’t approved",
            "stub_application_unknown" to "Check back soon",
        ).forEach { (extra, text) ->
            launch(extra).use {
                openApplicantSignIn()
                waitFor("partner-application-status")
                compose.onNodeWithTag("partner-application-message").assertTextContains(text, substring = true)
                assertTrue(absent("partner-application-error"))
            }
        }
    }

    @Test fun withoutAReceiptThereIsNoApplicationCard() {
        launch().use {
            openApplicantSignIn()
            compose.waitForIdle()
            assertTrue(absent("partner-application-status"))
            assertTrue(absent("partner-application-error"))
        }
    }

    @Test fun signingOutReturnsToRoleSelectAndThePortalIsGone() {
        launch("stub_partner_signed_in").use {
            openOrganization()
            compose.tapSafely("partner-sign-out")
            waitFor("role-partner")
            assertTrue(absent("partner-portal"))
        }
    }
}
