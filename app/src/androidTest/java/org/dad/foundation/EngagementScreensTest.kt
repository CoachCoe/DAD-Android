package org.dad.foundation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The engagement screen composed directly over a scripted service, to observe what it asks the share sheet and clipboard to do. */
class EngagementScreensTest {
    @get:Rule val compose = createComposeRule()

    private class RecordingSharer : ReferralSharer {
        val shared = mutableListOf<String>()
        val copied = mutableListOf<String>()
        override fun share(link: String, channel: String) { shared += link }
        override fun copy(link: String) { copied += link }
    }

    private class Service(val referral: ReferralInfo?, val ranking: RankingStanding? = RankingStanding(2, "Austin", 4)) : EngagementService {
        override suspend fun snapshot() = EngagementSnapshot(
            impact = null, badges = null, ranking = ranking, network = null, referral = referral, needsPublicName = referral == null,
        )
        override suspend fun setRankingOptIn(optIn: Boolean) = RankingStanding.optedOut()
        override suspend fun choosePublicName(name: String) = Unit
        override suspend fun initiativeSpend(id: String) = InitiativeSpend(0, emptyList())
    }

    private fun show(service: EngagementService, sharer: RecordingSharer, origin: String = "https://dad.example") {
        val model = EngagementModel(service)
        compose.setContent { EngagementHome(model, origin, ReferralShareAction(sharer)) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("invite-section")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun shareHandsTheSheetTheMessageWithTheServerRouteLink() {
        val sharer = RecordingSharer()
        show(Service(ReferralInfo("ALEX", 1, 0, emptyList())), sharer)
        compose.onNodeWithTag("share-invite").performScrollTo().performClick()
        assertEquals(1, sharer.shared.size)
        assertTrue(sharer.shared.single().endsWith("https://dad.example/r/ALEX"))
        assertTrue(sharer.copied.isEmpty())
    }

    @Test fun copyHandsTheClipboardTheBareLink() {
        val sharer = RecordingSharer()
        show(Service(ReferralInfo("ALEX", 1, 0, emptyList())), sharer)
        compose.onNodeWithTag("copy-invite").performScrollTo().performClick()
        assertEquals(listOf("https://dad.example/r/ALEX"), sharer.copied)
    }

    @Test fun withNoConfiguredOriginThereIsNoLinkAndNoShareControls() {
        val sharer = RecordingSharer()
        show(Service(ReferralInfo("ALEX", 1, 0, emptyList())), sharer, origin = "")
        compose.onNodeWithTag("invite-unconfigured").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasTestTag("share-invite")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("show-qr")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun anUnknownCountIsUnavailableNotZeroAndUnknownConversionsSayso() {
        val sharer = RecordingSharer()
        show(Service(ReferralInfo("ALEX", null, null, null)), sharer)
        compose.onNodeWithTag("referral-total-unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("conversions-unavailable").performScrollTo().assertIsDisplayed()
    }

    @Test fun anOutOfRangePercentileIsClampedOnScreen() {
        val sharer = RecordingSharer()
        show(Service(ReferralInfo("A", 0, 0, emptyList()), ranking = RankingStanding(0, null, null)), sharer)
        compose.onNodeWithTag("ranking-national").performScrollTo().assertTextContains("Top 1% nationally")
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClick() = performClick()
