package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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

    // A createEmptyComposeRule() is not bound to a host activity, so right after ActivityScenario.launch there is a
    // window where MainActivity has not yet attached (or stably attached) its compose tree. The fast local emulator
    // closes that window before the first probe; the slow headless CI emulator loses the race in the heaviest test,
    // surfacing as "No compose hierarchies found" or a ComposeTimeout on the first interaction. Rather than guard
    // each symptom, every test launches through this single readiness gate: it waits for the activity to actually
    // reach RESUMED and for compose to report a non-empty tree ONCE before any interaction runs.
    private fun launchReady(vararg extras: String): ActivityScenario<MainActivity> {
        val scenario = launch(*extras)
        // 1. The launched activity must be RESUMED — same mechanism KeyboardSafeInput.resumedActivity() uses.
        compose.waitUntil(30_000) {
            runCatching {
                var resumed = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).isNotEmpty()
                }
                resumed
            }.getOrDefault(false)
        }
        // 2. Compose must report a non-empty tree. Anchor on the always-present signed-out root tag `role-select`;
        //    for launches seeded straight into a signed-in/partner state that tag never appears, so fall back to any
        //    non-empty root. A pre-attach probe throws, which counts as "not ready" rather than aborting the wait.
        compose.waitUntil(30_000) {
            runCatching {
                compose.onAllNodes(hasTestTag("role-select")).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodes(isRoot()).fetchSemanticsNodes().any { it.children.isNotEmpty() }
            }.getOrDefault(false)
        }
        return scenario
    }

    // On the slow headless CI emulator the Activity's setContent can land after the first probe, so
    // fetchSemanticsNodes throws "No compose hierarchies found" before the tree attaches. Treat that transient
    // state as "not yet" and keep waiting, rather than letting it abort the wait on the first frame.
    private fun waitFor(tag: String, timeout: Long = 15_000) =
        compose.waitUntil(timeout) {
            runCatching { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }

    // Before the hierarchy attaches, fetchSemanticsNodes throws rather than returning empty; a node that is not
    // there yet is, for this helper's purpose, absent.
    private fun absent(tag: String) =
        runCatching { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }.getOrDefault(true)

    private fun openOrganization() {
        waitFor("partner-tab-organization")
        compose.tapSafely("partner-tab-organization")
    }

    private fun openApplicantSignIn() {
        // Under CI load a single role-select tap can be dropped before the screen is interactive, leaving the app
        // on role-select so the partner email field never appears. Tap, then if the transition did not happen,
        // tap again — the real wait semantics are kept, only a lost first click is recovered.
        waitFor("role-partner")
        repeat(3) {
            if (!absent("partner-sign-in-email")) return
            // Only (re)tap while still on role-select; mid-transition the role button is already gone.
            if (!absent("role-partner")) compose.tapSafely("role-partner")
            val reached = runCatching { compose.waitUntil(6_000) { !absent("partner-sign-in-email") } }.isSuccess
            if (reached) return
            compose.waitForIdle()
        }
        waitFor("partner-sign-in-email")
    }

    @Test fun aViewerSeesNoAdminActionsAndTheServerWouldRefuseThemAnyway() {
        launchReady("stub_partner_signed_in", "stub_partner_viewer").use {
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
        launchReady("stub_partner_signed_in").use {
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
        launchReady("stub_partner_signed_in").use {
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
        launchReady("stub_partner_signed_in").use {
            openOrganization()
            compose.tapSafely("partner-export")
            compose.waitUntil(15_000) { "GET /donor/partner/report.pdf" in DebugBackend.requestLog }
            // The chooser takes over the screen; put the app back in front before the scenario closes.
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        assertTrue(DebugBackend.requestLog.count { it == "GET /donor/partner/exports" } >= 2)
    }

    @Test fun aPendingApplicationShowsItsStatusAboveSignIn() {
        launchReady("stub_application_pending").use {
            openApplicantSignIn()
            waitFor("partner-application-status")
            compose.onNodeWithTag("partner-application-message").assertTextContains("being reviewed", substring = true)
        }
    }

    private fun applicationShows(extra: String, text: String) {
        launchReady(extra).use {
            openApplicantSignIn()
            waitFor("partner-application-status")
            compose.onNodeWithTag("partner-application-message").assertTextContains(text, substring = true)
            assertTrue(absent("partner-application-error"))
        }
    }

    @Test fun anApprovedApplicationSaysSo() = applicationShows("stub_application_approved", "is approved")

    @Test fun aRejectedApplicationSaysSo() = applicationShows("stub_application_rejected", "wasn’t approved")

    @Test fun anUnknownApplicationStateIsGeneric() = applicationShows("stub_application_unknown", "Check back soon")

    @Test fun withoutAReceiptThereIsNoApplicationCard() {
        launchReady().use {
            openApplicantSignIn()
            compose.waitForIdle()
            assertTrue(absent("partner-application-status"))
            assertTrue(absent("partner-application-error"))
        }
    }

    private fun fillApplication() {
        compose.enterText("partner-app-organization", "Rivertown Mills")
        compose.enterText("partner-app-website", "rivertown.example")
        compose.enterText("partner-app-workforce", "120")
        compose.enterText("partner-app-contact", "Ada Admin")
        compose.enterText("partner-app-title", "COO")
        compose.enterText("partner-app-email", "ada@rivertown.example")
        compose.enterText("partner-app-phone", "+1 555 0100")
    }

    @Test fun submittingAnApplicationStoresTheReceiptAndTheStatusCardThenShowsPending() {
        launchReady().use {
            openApplicantSignIn()
            // No receipt yet: the status card is absent and the apply button is offered.
            assertTrue(absent("partner-application-status"))
            compose.tapSafely("partner-apply-in-app")
            waitFor("partner-app-submit")
            fillApplication()
            compose.tapSafely("partner-app-submit")
            // Back on sign-in, the just-stored receipt reads as PENDING and the apply button is gone.
            waitFor("partner-application-status")
            compose.onNodeWithTag("partner-application-message").assertTextContains("being reviewed", substring = true)
            assertTrue(absent("partner-apply-in-app"))
        }
    }

    @Test fun anInvalidApplicationShowsAnErrorAndKeepsTheFormForCorrection() {
        launchReady("stub_application_submit_invalid").use {
            openApplicantSignIn()
            compose.tapSafely("partner-apply-in-app")
            waitFor("partner-app-submit")
            fillApplication()
            compose.tapSafely("partner-app-submit")
            waitFor("partner-app-error")
            // A 422 unlocks the form: the organization field is still there to correct.
            assertTrue(!absent("partner-app-organization"))
        }
    }

    @Test fun anUnconfirmedSendLocksTheFormAndRetrySucceeds() {
        launchReady("stub_application_submit_fails_first").use {
            openApplicantSignIn()
            compose.tapSafely("partner-apply-in-app")
            waitFor("partner-app-submit")
            fillApplication()
            compose.tapSafely("partner-app-submit")
            waitFor("partner-app-error")
            // The first attempt was a 503: the button is now "Retry" and resends the same attempt, which succeeds.
            compose.tapSafely("partner-app-submit")
            waitFor("partner-application-status")
            compose.onNodeWithTag("partner-application-message").assertTextContains("being reviewed", substring = true)
        }
    }

    @Test fun signingOutReturnsToRoleSelectAndThePortalIsGone() {
        launchReady("stub_partner_signed_in").use {
            openOrganization()
            compose.tapSafely("partner-sign-out")
            waitFor("role-partner")
            assertTrue(absent("partner-portal"))
        }
    }
}
