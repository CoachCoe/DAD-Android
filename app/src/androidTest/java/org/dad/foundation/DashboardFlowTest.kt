package org.dad.foundation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Runs the REAL [WireDashboard], [AccountSession] and [WireAPI] against the DEBUG-only scripted transport
 * (`stub_backend`) and starts with a stored session (`stub_signed_in`), so the donor home renders stubbed
 * server data through the release code path with no network. Scenario extras: `stub_dashboard_empty`,
 * `stub_dashboard_fail_first`, `stub_dashboard_unranked`.
 */
class DashboardFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun launch(vararg extras: String): ActivityScenario<MainActivity> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("stub_backend", true).putExtra("stub_signed_in", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras.forEach { intent.putExtra(it, true) }
        return ActivityScenario.launch(intent)
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String, timeout: Long = 15_000) = compose.waitUntil(timeout) { exists(tag) }

    /** Scroll the history list until [tag] is composed, retrying while a page is still loading. */
    private fun scrollHistoryTo(tag: String) {
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("history-screen").performScrollToNode(hasTestTag(tag)) }.isSuccess
        }
    }

    private fun openHistory() {
        waitFor("open-history")
        compose.onNodeWithTag("open-history").performScrollTo().performClick()
    }

    @Test fun signedInDashboardRendersStubbedServerData() {
        launch().use {
            waitFor("streak-count")
            compose.onNodeWithTag("streak-count").assertTextEquals("18-day streak")
            compose.onNodeWithTag("streak-next").assertTextEquals("12 days to your 30-day milestone")
            // national_percentile 2 is the top bucket: "Top 2%", never "Top 98%".
            compose.onNodeWithTag("rank-national").assertTextEquals("Top 2% nationally")
            compose.onNodeWithTag("rank-city").assertTextEquals("#4 in Austin")
            compose.onNodeWithTag("dash-given").assertTextEquals("$1,240")
            compose.onNodeWithTag("network-annualized").assertTextContains("$4,745", substring = true)
            compose.onNodeWithTag("founding-donor").assertTextContains("Founding donor", substring = true)
            waitFor("initiative-title")
            compose.onNodeWithTag("initiative-title").assertTextEquals("Meals on Wheels")
            compose.onNodeWithTag("initiative-note").assertTextContains("not designated giving", substring = true)
        }
    }

    @Test fun giftHistoryPagesToTheEndAndShowsAGiftRecord() {
        launch().use {
            openHistory()
            waitFor("gift-row-0")
            compose.onNodeWithTag("history-year-total").assertTextEquals("$3,515")
            scrollHistoryTo("history-more")
            compose.onNodeWithTag("history-more").performClick()
            scrollHistoryTo("gift-row-99")
            scrollHistoryTo("history-more")
            compose.onNodeWithTag("history-more").performClick()
            scrollHistoryTo("gift-row-119")
            assertTrue("every page was loaded", !exists("gift-row-120"))
        }
    }

    @Test fun theLastPageEndsPaginationAndAGiftOpensItsRecord() {
        launch().use {
            openHistory()
            waitFor("gift-row-0")
            repeat(2) {
                scrollHistoryTo("history-more")
                compose.onNodeWithTag("history-more").performClick()
                compose.waitForIdle()
            }
            scrollHistoryTo("gift-row-119")
            assertTrue("the last page ends pagination", !exists("history-more"))
            scrollHistoryTo("gift-row-0")
            compose.onNodeWithTag("gift-row-0").performClick()
            waitFor("receipt-detail")
            compose.onNodeWithTag("receipt-detail").assertIsDisplayed()
            compose.onNodeWithTag("history-screen").performScrollToNode(hasTestTag("history-close"))
            compose.onNodeWithTag("history-close").performClick()
            waitFor("open-history")
        }
    }

    @Test fun emptyStatesAreExplained() {
        launch("stub_dashboard_empty").use {
            waitFor("dashboard-preparing")
            waitFor("initiative-none")
            assertTrue(!exists("streak-count"))
            openHistory()
            waitFor("history-empty")
        }
    }

    @Test fun errorsOfferRetryAndRecover() {
        launch("stub_dashboard_fail_first").use {
            waitFor("dashboard-error")
            compose.onNodeWithTag("dashboard-retry").performScrollTo().performClick()
            waitFor("streak-count")
            openHistory()
            waitFor("history-error")
            compose.onNodeWithTag("history-retry").performClick()
            waitFor("gift-row-0")
        }
    }

    @Test fun optedOutAndUnknownFiguresAreNeverShownAsZero() {
        launch("stub_dashboard_unranked").use {
            waitFor("streak-unknown")
            assertTrue(!exists("rank-national"))
            assertTrue("an opted-out donor sees no rank line", !exists("rank-unranked"))
            compose.onNodeWithTag("dash-given").assertTextEquals("--")
            assertTrue(!exists("network-card"))
            assertTrue(!exists("founding-donor"))
        }
    }
}
