package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
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
 * Email signup, password sign-in and set-password through the REAL conformers, session and screens
 * on the DEBUG-only scripted transport (`stub_backend`). Script: a claim token beginning `good`
 * signs in; alex@example.org / correct-horse-battery is the one valid password login; a wrong
 * current password on set-password is a 422 CURRENT_PASSWORD_INCORRECT that keeps the session.
 */
class CredentialAuthFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).putExtra("stub_backend", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch(intent)
    }

    private fun waitFor(tag: String, timeout: Long = 15_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun absent(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextInput(text)
        compose.waitForIdle()
    }

    private fun openMethods() {
        waitFor("role-donor")
        compose.onNodeWithTag("role-donor").performClick()
        waitFor("sign-in-methods")
    }

    private fun fillSignup(email: String = "new.donor@example.org") {
        openMethods()
        compose.onNodeWithTag("method-signup").performScrollTo().performClick()
        waitFor("signup")
        type("signup-name", "Alex Doe")
        type("signup-email", email)
        type("signup-zip", "78701")
        type("signup-city", "Austin")
        compose.onNodeWithTag("signup-submit").performScrollTo().performClick()
    }

    private fun passwordSignIn(email: String, password: String) {
        openMethods()
        compose.onNodeWithTag("method-password").performScrollTo().performClick()
        waitFor("password-sign-in")
        type("password-email", email)
        type("password-input", password)
        compose.onNodeWithTag("password-submit").performScrollTo().performClick()
    }

    @Test fun newDonorSignsUpThenClaimsAPastedLinkAndLandsInTheDonorShell() {
        launch().use {
            fillSignup()
            waitFor("check-email")
            compose.onNodeWithTag("check-email-address").assertIsDisplayed()
            type("paste-input", "https://stub.invalid/account/claim#token=good" + "A".repeat(40))
            compose.onNodeWithTag("paste-submit").performScrollTo().performClick()
            waitFor("open-settings")
        }
    }

    @Test fun aLinkThatIsNotADadSignInLinkIsRefusedInline() {
        launch().use {
            fillSignup()
            waitFor("check-email")
            type("paste-input", "https://example.com/not-dad")
            compose.onNodeWithTag("paste-submit").performScrollTo().performClick()
            waitFor("paste-error")
            assertTrue(absent("open-settings"))
        }
    }

    @Test fun anExpiredClaimLinkShowsTheNoticeAndStaysSignedOut() {
        launch().use {
            fillSignup()
            waitFor("check-email")
            type("paste-input", "https://stub.invalid/account/claim#token=stale" + "B".repeat(40))
            compose.onNodeWithTag("paste-submit").performScrollTo().performClick()
            waitFor("check-email-notice")
            assertTrue(absent("open-settings"))
        }
    }

    @Test fun signupValidationErrorShowsAndKeepsTheForm() {
        launch().use {
            fillSignup(email = "bad.address@example.org")
            waitFor("signup-error")
            compose.onNodeWithTag("signup-submit").assertExists()
        }
    }

    @Test fun passwordSignInSucceedsIntoTheDonorShell() {
        launch().use {
            passwordSignIn("alex@example.org", "correct-horse-battery")
            waitFor("open-settings")
        }
    }

    @Test fun aWrongPasswordStaysSignedOutWithTheMessage() {
        launch().use {
            passwordSignIn("alex@example.org", "not-the-password")
            waitFor("password-error")
            assertTrue(absent("open-settings"))
            compose.onNodeWithTag("password-input").assertExists()
        }
    }

    @Test fun aThrottledPasswordSignInShowsTheWaitMessage() {
        launch().use {
            passwordSignIn("throttle@example.org", "whatever-password")
            waitFor("password-error")
            assertTrue(absent("open-settings"))
        }
    }

    private fun openPasswordSettings() {
        passwordSignIn("alex@example.org", "correct-horse-battery")
        waitFor("open-settings")
        compose.onNodeWithTag("open-settings").performClick()
        waitFor("pw-save")
    }

    @Test fun settingAPasswordSavesAndTheSessionStays() {
        launch().use {
            openPasswordSettings()
            type("pw-current", "correct-horse-battery")
            type("pw-new", "a-brand-new-password")
            type("pw-confirm", "a-brand-new-password")
            compose.onNodeWithTag("pw-save").performScrollTo().performClick()
            waitFor("pw-saved")
            compose.onNodeWithTag("settings-signout").performScrollTo().assertExists()
        }
    }

    @Test fun aWrongCurrentPasswordKeepsThePersonSignedIn() {
        launch().use {
            openPasswordSettings()
            type("pw-current", "wrong-current-password")
            type("pw-new", "a-brand-new-password")
            type("pw-confirm", "a-brand-new-password")
            compose.onNodeWithTag("pw-save").performScrollTo().performClick()
            waitFor("pw-error")
            compose.onNodeWithTag("settings-signout").performScrollTo().assertExists()
            assertTrue(absent("role-donor"))
        }
    }

    @Test fun googleAndPasskeyShowTheNotAvailableHint() {
        launch().use {
            openMethods()
            compose.onNodeWithTag("external-unavailable").performScrollTo().assertIsDisplayed()
            assertTrue(absent("method-google"))
            assertTrue(absent("method-passkey"))
        }
    }
}
