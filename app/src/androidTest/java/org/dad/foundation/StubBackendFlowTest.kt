package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Runs the REAL sign-in, session and account-deletion conformers (WireAuth, AccountSession,
 * WireSettings) against the DEBUG-only scripted transport (`stub_backend`), so the release code
 * path is exercised on the emulator with no network. Codes: 123456 signs in, 000000 is throttled,
 * anything else is rejected.
 */
class StubBackendFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(vararg extras: String): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra("stub_backend", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras.forEach { intent.putExtra(it, true) }
        return ActivityScenario.launch(intent)
    }

    private fun waitFor(tag: String, timeout: Long = 15_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    /** Waits until the tagged control exists AND is enabled, so a click never lands on a still-disabled button. */
    private fun waitEnabled(tag: String, timeout: Long = 15_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasTestTag(tag).and(isEnabled())).fetchSemanticsNodes().isNotEmpty() }

    private fun signIn(code: String) {
        waitFor("role-donor")
        compose.tapSafely("role-donor")
        waitFor("method-phone")
        compose.tapSafely("method-phone")
        waitFor("phone-input")
        compose.enterText("phone-input", "+14155551234")
        compose.waitForIdle()
        waitEnabled("send-code")
        compose.tapSafely("send-code")
        waitFor("code-input")
        compose.enterText("code-input", code)
        compose.waitForIdle()
        waitEnabled("verify-code")
        compose.tapSafely("verify-code")
    }

    private fun deleteAccount() {
        waitFor("open-settings")
        compose.tapSafely("open-settings")
        waitFor("delete-start")
        compose.tapSafely("delete-start")
        waitFor("delete-input")
        compose.enterText("delete-input", "DELETE")
        compose.waitForIdle()
        compose.tapSafely("delete-confirm")
    }

    @Test fun signInThenDeleteAccountReturnsToSignIn() {
        launch().use {
            signIn("123456")
            deleteAccount()
            waitFor("role-donor")
            compose.onNodeWithTag("role-donor").assertIsDisplayed()
        }
    }

    @Test fun wrongCodeKeepsPersonSignedOutOnCodeEntry() {
        launch().use {
            signIn("654321")
            waitFor("sign-in-error")
            assertTrue(compose.onAllNodes(hasTestTag("open-settings")).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("code-input").assertIsDisplayed()
        }
    }

    @Test fun throttledCodeShowsTheWaitMessageAndStaysSignedOut() {
        launch().use {
            signIn("000000")
            waitFor("sign-in-error")
            assertTrue(compose.onAllNodes(hasTestTag("open-settings")).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun blockedDeletionExplainsAndKeepsTheSession() {
        launch("stub_delete_blocked").use {
            signIn("123456")
            deleteAccount()
            waitFor("settings-action-error")
            compose.onNodeWithTag("settings-action-error").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("delete-confirm").assertExists()
        }
    }

    @Test fun signOutFromSettingsReturnsToSignIn() {
        launch().use {
            signIn("123456")
            waitFor("open-settings")
            compose.tapSafely("open-settings")
            waitFor("settings-signout")
            compose.tapSafely("settings-signout")
            waitFor("role-donor")
        }
    }

    @Test fun partnerSignsInWithAPastedLinkAndSignsOut() {
        launch().use {
            waitFor("role-partner")
            compose.tapSafely("role-partner")
            compose.enterText("partner-sign-in-email", "ada@acme.com")
            waitEnabled("partner-sign-in-submit")
            compose.tapSafely("partner-sign-in-submit")
            waitFor("partner-check-email")
            compose.enterText("partner-paste-link", "https://stub.invalid/partner/accept#token=goodpartner0123456789abcdefghijklmnop")
            waitEnabled("partner-paste-submit")
            compose.tapSafely("partner-paste-submit")
            waitFor("partner-committed")
            compose.onNodeWithTag("partner-organization").assertTextEquals("Acme Corp")
            compose.tapSafely("partner-tab-organization")
            compose.tapSafely("partner-sign-out")
            waitFor("role-partner")
        }
    }

    @Test fun aStoredPartnerSessionOpensThePortal() {
        launch("stub_partner_signed_in").use {
            waitFor("partner-committed")
            compose.tapSafely("partner-tab-organization")
            waitFor("partner-invite")
        }
    }

    @Test fun theAnalyticsQuestionIsAskedOnceAndAnsweringYesSendsAnEvent() {
        DebugBackend.resetRequestLog()
        launch("stub_analytics_unset").use {
            waitFor("analytics-choice")
            compose.tapSafely("analytics-allow")
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("analytics-choice")).fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(15_000) { "POST /analytics/events" in DebugBackend.requestLog }
        }
    }

    @Test fun aTappedNotificationOpensItsScreenAndTheTokenIsRegistered() {
        DebugBackend.resetRequestLog()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("stub_backend", true).putExtra("stub_signed_in", true).putExtra("stub_push", true)
            .putExtra("category", "STREAK_AT_RISK").putExtra("path", "/account/gifts")
        ActivityScenario.launch<MainActivity>(intent).use {
            waitFor("history-screen")
            compose.waitUntil(15_000) { "POST /donor/me/push-device" in DebugBackend.requestLog }
        }
    }
}
