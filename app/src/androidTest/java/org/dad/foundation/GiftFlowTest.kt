package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The giving journey end to end through the REAL [WireGiving] conformer, [AccountSession] and Retrofit stack,
 * over the DEBUG scripted transport (`stub_backend`, with a stored session via `stub_signed_in`). The hosted
 * Stripe pages are stubbed by an in-app stand-in whose "Complete payment" plays the Stripe webhook. Scenario
 * extras: `stub_gift_active` (a $150/month gift), `stub_gift_fail_first` (the first recurring read is a 503).
 */
class GiftFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(vararg extras: String, link: String? = null): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("stub_backend", true).putExtra("stub_signed_in", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras.forEach { intent.putExtra(it, true) }
        link?.let { intent.data = android.net.Uri.parse(it) }
        DebugBackend.resetRequestLog()
        DebugBackend.checkoutReferral = null
        return ActivityScenario.launch(intent)
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) { exists(tag) }
    private fun waitGone(tag: String, timeout: Long = 20_000) = compose.waitUntil(timeout) { !exists(tag) }
    private fun tap(tag: String) = compose.tapSafely(tag)

    /** Wait for the home gift card to finish loading, then open the gift screen from it. */
    private fun openGift() {
        waitFor("open-gift")
        compose.waitUntil(20_000) { !exists("dashboard-loading") && !exists("initiative-loading") }
        compose.waitForIdle()
        tap("open-gift")
        waitFor("gift-screen")
    }


    private fun waitSummary(text: String) = compose.waitUntil(20_000) {
        runCatching { compose.onNodeWithTag("gift-summary").assertTextContains(text, substring = true) }.isSuccess
    }

    private fun closeGift() {
        tap("gift-close")
        waitFor("donor-home")
    }

    @Test fun aGiftStartedAfterAnInviteLinkCreditsTheReferrer() {
        launch(link = "https://stub.invalid/r/ALEX42?via=irl").use {
            compose.waitUntil(20_000) { DebugBackend.requestLog.contains("POST /donor/referrals/ALEX42") }
            waitFor("gift-summary")
            waitSummary("No monthly gift yet.")
            openGift()
            waitFor("start-gift")
            tap("start-gift")
            waitFor("hosted-page-stub")
            assertEquals(DebugBackend.REFERRAL_TOKEN, DebugBackend.checkoutReferral)
        }
    }

    @Test fun startAGiftThroughTheHostedPageThenTheHomeCardShowsIt() {
        launch().use {
            waitFor("gift-summary")
            waitSummary("No monthly gift yet.")
            openGift()
            waitFor("start-gift")
            waitFor("gift-none")
            // Pick $6 a day, then start: the app sends the amount and opens the (stubbed) hosted page.
            repeat(5) { tap("gift-increase") }
            compose.onNodeWithTag("gift-daily").assertTextEquals("$6/day")
            tap("start-gift")
            waitFor("hosted-page-stub")
            assertTrue(DebugBackend.requestLog.contains("POST /donor/me/recurring/checkout"))
            tap("stub-complete")
            waitFor("gift-monthly")
            compose.onNodeWithTag("gift-monthly").assertTextEquals("$180/month")
            compose.onNodeWithTag("gift-notice").assertTextEquals("Your gift is set up.")
            closeGift()
            waitSummary("$180/month · renews")
        }
    }

    @Test fun closingTheHostedPageWithoutPayingLeavesNoGift() {
        launch().use {
            openGift()
            tap("start-gift")
            waitFor("hosted-page-stub")
            tap("stub-close")
            waitFor("gift-notice")
            compose.onNodeWithTag("gift-notice").assertTextContains("We haven’t seen a new gift yet", substring = true)
            assertTrue(exists("gift-none"))
            assertEquals(1, DebugBackend.requestLog.count { it == "POST /donor/me/recurring/checkout" })
            closeGift()
            waitSummary("No monthly gift yet.")
        }
    }

    @Test fun changeTheAmount() {
        launch("stub_gift_active").use {
            openGift()
            waitFor("gift-monthly")
            compose.onNodeWithTag("gift-monthly").assertTextEquals("$150/month")
            compose.onNodeWithTag("gift-daily").assertTextEquals("$5/day")
            tap("gift-increase")
            tap("save-gift")
            waitFor("gift-notice")
            compose.onNodeWithTag("gift-notice").assertTextEquals("Saved. Your next renewal will be $180.")
            compose.onNodeWithTag("gift-monthly").assertTextEquals("$180/month")
            assertTrue(DebugBackend.requestLog.contains("POST /donor/me/recurring/amount"))
            closeGift()
            waitSummary("$180/month")
        }
    }

    @Test fun cancelTheMonthlyGiftThenTheHomeCardShowsItEnding() {
        launch("stub_gift_active").use {
            openGift()
            tap("gift-cancel")
            waitFor("cancel-confirm")
            compose.onNodeWithTag("cancel-confirm").assertIsDisplayed()
            tap("cancel-end")
            waitFor("gift-ending")
            compose.onNodeWithTag("gift-ending").assertTextContains("won’t renew", substring = true)
            closeGift()
            waitSummary("no further renewals")
        }
    }

    @Test fun keepGivingLeavesTheGiftActive() {
        launch("stub_gift_active").use {
            openGift()
            tap("gift-cancel")
            waitFor("cancel-confirm")
            tap("cancel-keep")
            waitGone("cancel-confirm")
            assertTrue(exists("gift-cancel"))
            assertTrue(!exists("gift-ending"))
            assertTrue(DebugBackend.requestLog.none { it == "POST /donor/me/recurring/cancel" })
        }
    }

    @Test fun updateThePaymentMethodThroughTheHostedPage() {
        launch("stub_gift_active").use {
            openGift()
            waitFor("payment-status")
            tap("update-payment")
            waitFor("hosted-page-stub")
            tap("stub-complete")
            waitFor("gift-notice")
            compose.onNodeWithTag("gift-notice").assertTextEquals("Your payment method was updated.")
            compose.onNodeWithTag("payment-status").assertTextContains("Card updated", substring = true)
        }
    }

    @Test fun aFailedGiftReadShowsAMessageAndOpeningTheScreenRecovers() {
        launch("stub_gift_fail_first").use {
            waitFor("gift-summary")
            waitSummary("Your gift isn’t available right now.")
            openGift()
            waitFor("gift-monthly")
            compose.onNodeWithTag("gift-monthly").assertTextEquals("$150/month")
            closeGift()
            waitSummary("$150/month")
        }
    }

    private fun startDeleting() {
        waitFor("open-settings")
        compose.waitUntil(20_000) { !exists("dashboard-loading") && !exists("initiative-loading") }
        tap("open-settings")
        tap("delete-start")
        waitFor("delete-input")
        compose.enterText("delete-input", "DELETE")
    }

    @Test fun deleteAccountWithAnActiveGiftOffersTheCancelFlowThenDeletes() {
        launch("stub_gift_active").use {
            startDeleting()
            tap("delete-confirm")
            // The server refuses (409): the screen explains and offers the real cancel flow.
            waitFor("settings-action-error")
            compose.onNodeWithTag("settings-action-error").assertTextContains("Cancel your monthly gift first", substring = true)
            tap("delete-cancel-gift")
            waitFor("gift-screen")
            tap("gift-cancel")
            waitFor("cancel-confirm")
            tap("cancel-end")
            waitFor("gift-ending")
            tap("gift-close")
            // Back in Settings with the half-finished confirmation intact: with the gift no longer renewing, deleting goes through.
            waitFor("delete-confirm")
            tap("delete-confirm")
            waitFor("role-donor")
            assertEquals(1, DebugBackend.requestLog.count { it == "POST /donor/me/recurring/cancel" })
        }
    }

    @Test fun deleteWithNoGiftStillJustDeletes() {
        launch().use {
            startDeleting()
            tap("delete-confirm")
            waitFor("role-donor")
            assertTrue(!exists("delete-cancel-gift"))
        }
    }

    @Test fun theLoadingLayoutReservesTheSpaceTheCardsFillSoGiftHistoryDoesNotMove() {
        launch("stub_dashboard_slow", "stub_gift_active").use {
            waitFor("dashboard-loading")
            waitFor("open-history")
            compose.waitForIdle()
            val density = compose.density.density
            val before = compose.onNodeWithTag("open-history").fetchSemanticsNode().boundsInRoot.top
            assertTrue("the measurement must be taken while the cards are still loading", exists("dashboard-loading") && exists("initiative-loading"))
            compose.waitUntil(30_000) { exists("streak-count") && exists("initiative-title") }
            compose.waitForIdle()
            val after = compose.onNodeWithTag("open-history").fetchSemanticsNode().boundsInRoot.top
            val shiftDp = Math.abs(after - before) / density
            assertTrue("Gift history moved ${shiftDp}dp when the cards loaded (before=$before after=$after)", shiftDp <= SHIFT_TOLERANCE_DP)
        }
    }

    private companion object {
        /** The skeleton lays out the same cards in the same type styles, so the button lands where it will end up (measured under 1dp on the stub data; the old spinner moved it by hundreds). */
        const val SHIFT_TOLERANCE_DP = 4f
    }
}
