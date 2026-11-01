package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Contact details, email change, the employer link and team, and ending sessions through the REAL
 * [WireAccountManagement], session and screens on the DEBUG-only scripted transport (`stub_backend`). Script:
 * company code `ACME42` links "Acme"; an email starting `taken` belongs to another account.
 */
class AccountManagementFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun openSettings(): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("stub_backend", true).putExtra("stub_signed_in", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        compose.tapSafely("open-settings", 20_000)
        waitFor("settings-email")
        return scenario
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) { exists(tag) }
    private fun waitGone(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) { !exists(tag) }
    private fun textOf(tag: String, text: String) = compose.waitUntil(20_000) {
        runCatching { compose.onNodeWithTag(tag).assertTextContains(text, substring = true) }.isSuccess
    }

    @Test fun contactDetailsSaveANormalizedPhone() {
        openSettings().use {
            waitFor("details-save")
            compose.onNodeWithTag("details-save").assertIsNotEnabled()
            compose.enterText("details-phone", "(415) 555-0123", replace = true)
            compose.tapSafely("details-save")
            waitFor("details-saved")
            textOf("details-phone", "+14155550123")
        }
    }

    @Test fun emailChangeExplainsAConflictThenSends() {
        openSettings().use {
            compose.tapSafely("email-change-start")
            compose.enterText("email-change-input", "taken@example.org", replace = true)
            compose.tapSafely("email-change-submit")
            waitFor("email-change-error")
            compose.enterText("email-change-input", "new@example.org", replace = true)
            compose.tapSafely("email-change-submit")
            waitFor("email-change-sent")
        }
    }

    @Test fun aCompanyCodeLinksAnEmployerThenTheDonorJoinsATeam() {
        openSettings().use {
            compose.enterText("employer-code", "WRONG", replace = true)
            compose.tapSafely("employer-enroll")
            waitFor("employer-error")
            compose.enterText("employer-code", "ACME42", replace = true)
            compose.tapSafely("employer-enroll")
            waitFor("employer-linked")
            waitGone("employer-error")
            waitFor("leaderboard-55555555-5555-4555-8555-555555555555")
            compose.enterText("employer-team-name", "Platform", replace = true)
            compose.tapSafely("employer-join-team")
            textOf("employer-team", "Your team: Platform")
        }
    }

    @Test fun endingAnotherSessionThenEverySessionSignsOut() {
        openSettings().use {
            compose.tapSafely("session-revoke-22222222-2222-4222-8222-222222222222")
            waitFor("sessions-none")
            compose.tapSafely("sessions-sign-out-everywhere")
            waitFor("role-donor")
        }
    }
}
