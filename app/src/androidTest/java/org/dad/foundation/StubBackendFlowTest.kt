package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
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
        compose.onNodeWithTag("role-donor").performClick()
        waitFor("method-phone")
        compose.onNodeWithTag("method-phone").performClick()
        waitFor("phone-input")
        compose.onNodeWithTag("phone-input").performTextInput("+14155551234")
        compose.waitForIdle()
        waitEnabled("send-code")
        compose.onNodeWithTag("send-code").performClick()
        waitFor("code-input")
        compose.onNodeWithTag("code-input").performTextInput(code)
        compose.waitForIdle()
        waitEnabled("verify-code")
        compose.onNodeWithTag("verify-code").performClick()
    }

    private fun deleteAccount() {
        waitFor("open-settings")
        compose.onNodeWithTag("open-settings").performClick()
        waitFor("delete-start")
        compose.onNodeWithTag("delete-start").performScrollTo().performClick()
        waitFor("delete-input")
        compose.onNodeWithTag("delete-input").performScrollTo().performTextInput("DELETE")
        compose.waitForIdle()
        compose.onNodeWithTag("delete-confirm").performScrollTo().performClick()
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
            compose.onNodeWithTag("open-settings").performClick()
            waitFor("settings-signout")
            compose.onNodeWithTag("settings-signout").performScrollTo().performClick()
            waitFor("role-donor")
        }
    }

    @Test fun partnerSignInIsAnHonestNotice() {
        launch().use {
            waitFor("role-partner")
            compose.onNodeWithTag("role-partner").performClick()
            waitFor("partner-sign-in-notice")
            compose.onNodeWithTag("partner-sign-in-notice").assertIsDisplayed()
        }
    }
}
