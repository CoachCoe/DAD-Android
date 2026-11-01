package org.dad.foundation

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The engagement journey end to end through the REAL [WireEngagement] conformer, [AccountSession] and Retrofit
 * stack, over the DEBUG scripted transport (`stub_backend` with a stored session). Scenario extras:
 * `stub_open_engagement` (open the screen at launch), `stub_no_public_name`, `stub_rankings_fail_toggle`,
 * `stub_badges_down`, `stub_network_down`, `stub_spend_unpublished`. See [StubEngagement].
 */
class EngagementWiredFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(vararg extras: String): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("stub_backend", true).putExtra("stub_signed_in", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras.forEach { intent.putExtra(it, true) }
        DebugBackend.resetRequestLog()
        return ActivityScenario.launch(intent)
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) { exists(tag) }
    private fun waitGone(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) { !exists(tag) }
    private fun tap(tag: String) = compose.tapSafely(tag)
    private fun textOf(tag: String, text: String) = compose.waitUntil(20_000) {
        runCatching { compose.onNodeWithTag(tag).assertTextContains(text, substring = true) }.isSuccess
    }

    private fun openEngagement(vararg extras: String): ActivityScenario<MainActivity> {
        val scenario = launch("stub_open_engagement", *extras)
        waitFor("engagement-home")
        waitGone("engagement-loading")
        return scenario
    }

    @Test fun theHomeCardOpensTheScreenAndCloseReturns() {
        launch().use {
            waitFor("open-engagement")
            compose.waitUntil(20_000) { !exists("dashboard-loading") && !exists("initiative-loading") }
            compose.waitForIdle()
            tap("open-engagement")
            waitFor("engagement-home")
            waitFor("badge-summary")
            tap("engagement-close")
            waitFor("donor-home")
        }
    }

    @Test fun badgesShowEarnedAndLockedInTextWithAPerkAndInviteOnlyAccess() {
        openEngagement().use {
            waitFor("badge-summary")
            textOf("badge-summary", "3 of 8 earned")
            compose.onNodeWithTag("badge-invite-access").performScrollTo().assertIsDisplayed()
            // Earned Founding Donor carries its perk as copy (the server sends a key); locked Team Captain shows its rule.
            compose.onNodeWithTag("badge-earned-F").performScrollTo().assertIsDisplayed()
            textOf("badge-perk-F", "Invite-only founding events")
            compose.onNodeWithTag("badge-locked-TC").performScrollTo().assertIsDisplayed()
            textOf("badge-locked-TC", "20 converted referrals")
            textOf("badge-locked-TC", "Locked")
        }
    }

    @Test fun rankingShowsTheTopBucketAsIsAndTheToggleWorksBothWays() {
        openEngagement().use {
            waitFor("ranking-national")
            textOf("ranking-national", "Top 2% nationally")
            tap("ranking-toggle")
            waitFor("ranking-optedout")
            waitGone("ranking-national")
            tap("ranking-toggle")
            waitFor("ranking-national")
            textOf("ranking-national", "Top 2% nationally")
        }
    }

    @Test fun aFailedToggleRevertsAndSaysSo() {
        openEngagement("stub_rankings_fail_toggle").use {
            waitFor("ranking-national")
            tap("ranking-toggle")
            waitFor("engagement-action-error")
            waitFor("ranking-national")
            textOf("ranking-national", "Top 2%")
        }
    }

    @Test fun theNetworkListsNamedInviteesAndCountsTheAnonymousOnes() {
        openEngagement().use {
            textOf("network-active", "5 people giving")
            textOf("network-annualized", "$3,480/yr")
            textOf("network-invitee-0", "Jordan M.")
            textOf("network-anonymous", "2 people choose to stay anonymous")
            waitFor("network-milestone")
        }
    }

    @Test fun theInviteLinkIsTheServerRouteAndRecentFirstGiftsListed() {
        openEngagement().use {
            textOf("referral-code", "ALEX2026")
            textOf("referral-total", "3 people have joined through you")
            textOf("invite-link", "https://stub.invalid/r/ALEX2026")
            compose.onNodeWithTag("conversion-0").performScrollTo().assertIsDisplayed()
            textOf("conversion-0", "Link")
            textOf("conversion-1", "In person")
        }
    }

    @Test fun copyPutsTheLinkOnTheClipboard() {
        openEngagement().use {
            waitFor("copy-invite")
            tap("copy-invite")
            textOf("copy-invite", "Copied")
            var copied: String? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            }
            assertEquals("https://stub.invalid/r/ALEX2026", copied)
        }
    }

    @Test fun theQrIsDrawnLocallyForTheInPersonLink() {
        openEngagement().use {
            waitFor("show-qr")
            tap("show-qr")
            waitFor("qr-code")
            compose.onNodeWithTag("qr-code").assertIsDisplayed()
            tap("qr-close")
            waitFor("engagement-home")
        }
    }

    @Test fun aDonorWithNoPublicNameIsPromptedToChooseOne() {
        openEngagement("stub_no_public_name").use {
            waitFor("name-field")
            compose.enterText("name-field", "Alex C.")
            tap("save-name")
            waitFor("referral-code")
            textOf("invite-link", "https://stub.invalid/r/ALEX2026")
        }
    }

    @Test fun aSectionThatFailedSaysSoWhileTheRestStaysOnScreen() {
        openEngagement("stub_badges_down").use {
            waitFor("badges-unavailable")
            waitFor("network-active")
            waitFor("ranking-national")
        }
        openEngagement("stub_network_down").use {
            waitFor("network-unavailable")
            waitFor("badge-summary")
        }
    }

    @Test fun todaysInitiativeOpensTheSpendByCategory() {
        launch().use {
            waitFor("open-spend")
            tap("open-spend")
            waitFor("spend-screen")
            textOf("spend-total", "$5,000")
            textOf("spend-row-0", "Food")
            textOf("spend-row-1", "Transport")
            tap("spend-close")
            waitFor("donor-home")
        }
    }

    @Test fun anInitiativeWithNoPublishedSpendSaysSo() {
        launch("stub_spend_unpublished").use {
            waitFor("open-spend")
            tap("open-spend")
            waitFor("spend-unpublished")
        }
    }
}
