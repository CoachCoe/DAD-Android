package org.dad.foundation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkBadgesTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // --- Badges (B-1): earned renders perk; unearned renders the RULE text, a11y conveys state ---
    @Test fun earnedBadgeConveysStateInTextAndShowsPerk() {
        val b = Badge(
            code = "FOUNDING_DONOR", title = "Founding Donor",
            rule = "Give before January 2027", perk = "Founding Donor recognition",
            earned = true, earnedAt = OffsetDateTime.parse("2026-06-01T00:00:00Z"),
        )
        // State is in TEXT (not colour alone) — screen reader + colour-blind users.
        assertEquals("Founding Donor, earned", b.accessibilityLabel)
        // Earned badges surface the perk, not the rule.
        assertEquals("Founding Donor recognition", b.detailLine)
        assertTrue(b.perkActive)
    }

    @Test fun earnedCityBadgeShowsItsDisplayTitleInTheLabel() {
        val b = Badge("CITY50", "Top 50 City", "r", "", earned = true, earnedAt = null, city = "Chicago", displayTitle = "Top 50 in Chicago")
        assertEquals("Top 50 in Chicago", b.shownTitle)
        assertEquals("Top 50 in Chicago, earned", b.accessibilityLabel)
        assertEquals("Top 50 City", b.copy(displayTitle = "").shownTitle)
    }

    @Test fun lockedCityBadgeKeepsTheGenericTitle() {
        val b = Badge("CITY50", "Top 50 City", "Reach the top 50", "", earned = false, earnedAt = null, city = "Chicago", displayTitle = "Top 50 in Chicago")
        assertEquals("Top 50 City", b.shownTitle)
        assertEquals("Top 50 City, locked. Reach the top 50", b.accessibilityLabel)
    }

    @Test fun lockedBadgeShowsRuleTextAndNoActivePerk() {
        val b = Badge(
            code = "LEGACY", title = "Legacy",
            rule = "Give 1,460 days running", perk = "Recognition",
            earned = false, earnedAt = null,
        )
        // Locked state + how to earn it, both in text (B-1).
        assertEquals("Legacy, locked. Give 1,460 days running", b.accessibilityLabel)
        // Locked badges show the rule, not the perk.
        assertEquals("Give 1,460 days running", b.detailLine)
        // Perk is not active until earned (B-4 gating).
        assertFalse(b.perkActive)
    }

    @Test fun badgeGridCountsAndActivePerks() {
        val grid = BadgeGrid(
            badges = listOf(
                Badge("A", "A", "rule a", "Perk A", earned = true, earnedAt = null),
                Badge("B", "B", "rule b", "Perk B", earned = true, earnedAt = null),
                Badge("C", "C", "rule c", "Perk C", earned = false, earnedAt = null),
                Badge("D", "D", "rule d", "", earned = true, earnedAt = null), // earned but blank perk
            ),
        )
        assertEquals(3, grid.earned.size)
        assertEquals(1, grid.locked.size)
        assertEquals("3 of 4 earned", grid.summaryLabel)
        // Only earned badges with a non-blank perk count as active perks.
        assertEquals(listOf("Perk A", "Perk B"), grid.activePerks)
    }

    @Test fun fullCatalogueRendersBothStatesAccessibly() {
        // DoD: every badge renders both states accessibly — i.e. a non-blank a11y label and
        // detail line regardless of earned state.
        val grid = kotlinx.coroutines.runBlocking { PreviewEngagementService().snapshot().badges!! }
        assertEquals(24, grid.badges.size)
        assertEquals(7, grid.earned.size)
        grid.badges.forEach { badge ->
            assertTrue("a11y label must be non-blank for ${badge.code}", badge.accessibilityLabel.isNotBlank())
            assertTrue("detail line must be non-blank for ${badge.code}", badge.detailLine.isNotBlank())
            if (badge.earned) assertTrue(badge.accessibilityLabel.endsWith("earned"))
            else assertTrue(badge.accessibilityLabel.contains("locked"))
        }
    }

    @Test fun previewCatalogueMatchesPrdAppendixA() {
        val badges = kotlinx.coroutines.runBlocking { PreviewEngagementService().snapshot().badges!!.badges }
        assertEquals(
            listOf(
                "Founding Donor", "First Day", "One Week", "One Month", "Hundred", "One Year", "1,000 Days",
                "Three Years", "Comeback", "Level Up", "First Referral", "Brought 3 In", "Brought 12 In",
                "Team Captain", "IRL", "Volunteer", "Showed Up", "Volunteer ×3", "Volunteer ×10",
                "7 Initiatives", "Top 50 City", "Top 2%", "Team Player", "Winning Team",
            ),
            badges.map { it.title },
        )
        assertEquals(24, badges.map { it.code }.toSet().size)
        // Codes are the server catalogue's (DAD-Main badges.go), in Appendix A order.
        assertEquals(
            listOf(
                "F", "1D", "7", "30", "100", "Y1", "1K", "Y3", "CB", "UP", "REF1", "REF3", "REF12", "TC",
                "IRL", "V", "SU", "V3", "V10", "INIT7", "CITY50", "NAT2", "TP", "WT",
            ),
            badges.map { it.code },
        )
        // Perk gating keys on the server codes F, 1K and TC.
        assertEquals(setOf("F", "1K", "TC"), badges.filter { it.perk.isNotBlank() }.map { it.code }.toSet())
        assertEquals(Badge.PERK_GATING_CODES, badges.filter { it.perk.isNotBlank() }.map { it.code }.toSet())
        // Only Founding Donor, 1,000 Days and Team Captain carry perks (B-4).
        assertEquals(setOf("Founding Donor", "1,000 Days", "Team Captain"), badges.filter { it.perk.isNotBlank() }.map { it.title }.toSet())
        assertEquals("365-day streak", badges.first { it.title == "One Year" }.rule)
    }

    // --- Network (N-1..N-3): anonymous referrals are counted, never named ---
    @Test fun networkCountsAnonymousButNeverNamesThem() {
        val net = NetworkView(
            activeCount = 5, annualizedMinor = 348_000,
            nextMilestone = NetworkMilestone("Circle of 10", target = 10, remaining = 5),
            invitees = listOf(
                NetworkInvitee("Alex C.", active = true),
                NetworkInvitee("Jordan M.", active = true),
                NetworkInvitee("Sam R.", active = false),
            ),
            anonymousActive = 2,
        )
        assertEquals("5 people giving", net.activeLabel)
        assertEquals("$3,480/yr", net.annualizedLabel)
        // Only opted-in invitees are listable; the count never leaks an anonymous name.
        assertEquals(3, net.namedInvitees.size)
        assertEquals(2, net.anonymousActiveCount)
        assertEquals("and 2 more giving anonymously", net.anonymousLabel)
        assertFalse(net.allTiersReached)
        assertEquals("5 more to Circle of 10", net.nextMilestone!!.progressLabel)
    }

    @Test fun networkWithNoAnonymousHidesTheLine() {
        val net = NetworkView(
            activeCount = 1, annualizedMinor = 12_000,
            nextMilestone = null, invitees = listOf(NetworkInvitee("Pat L.", active = true)),
            anonymousActive = 0,
        )
        assertEquals("1 person giving", net.activeLabel)
        assertNull(net.anonymousLabel)
        assertTrue(net.allTiersReached)
    }

    // --- Sharing visibility (N opt-in): off = anonymous, no links ---
    @Test fun sharingOffIsAnonymousWithNoLinks() {
        val off = SharingState(publicName = null, link = null, irlLink = null)
        assertFalse(off.isPublic)
        assertNull(off.shareLink)
        assertNull(off.inPersonLink)
        assertEquals("Giving anonymously", off.visibilityLabel)
    }

    @Test fun sharingOnExposesLinksAndName() {
        val on = SharingState(
            publicName = "Alex C.",
            link = "https://dad.org/r/alex",
            irlLink = "https://dad.org/r/alex?via=irl",
        )
        assertTrue(on.isPublic)
        assertEquals("https://dad.org/r/alex", on.shareLink)
        assertEquals("https://dad.org/r/alex?via=irl", on.inPersonLink)
        assertEquals("Shown as Alex C.", on.visibilityLabel)
    }

    @Test fun sharingWithBlankNameTreatedAsAnonymous() {
        // A blank public name is not "opted in" — guards against an empty-string leak.
        val blank = SharingState(publicName = "  ", link = "x", irlLink = "y")
        assertFalse(blank.isPublic)
        assertNull(blank.shareLink)
    }

    // --- Invite (R-1..R-4) ---
    @Test fun inviteLinksAreBuiltFromTheConfiguredOriginAndServerRoute() {
        val referral = ReferralInfo("ALEX", linkCount = 2, irlCount = 1, conversions = null)
        assertEquals("https://dad.org/r/ALEX", referral.link("https://dad.org"))
        assertEquals("https://dad.org/r/ALEX?via=irl", referral.irlLink("https://dad.org"))
        assertNull(referral.link("")) // no configured origin: no link, never an invented one
        assertTrue(referral.shareMessage("https://dad.org").endsWith("https://dad.org/r/ALEX"))
        assertFalse(referral.shareMessage("").contains("http"))
    }

    @Test fun unknownCountsAreNotZero() {
        assertEquals(3L, ReferralInfo("A", 2, 1, null).totalConverted)
        assertNull(ReferralInfo("A", null, 1, null).totalConverted)
        assertNull(ReferralInfo("A", 2, null, null).totalConverted)
    }

    @Test fun conversionKindsAreOpenStrings() {
        val at = OffsetDateTime.parse("2026-10-20T12:00:00Z")
        assertEquals("Link", Conversion(at, "LINK").kindLabel)
        assertEquals("In person", Conversion(at, "IRL").kindLabel)
        assertEquals("Invite", Conversion(at, "SOMETHING_NEW").kindLabel)
    }

    @Test fun perkKeysMapToCopyAndUnknownKeysStillSayThereIsAPerk() {
        assertEquals("", Badge.perkCopy(""))
        assertTrue(Badge.perkCopy("FOUNDING_EVENTS").contains("founding"))
        assertTrue(Badge.perkCopy("MILESTONE_EVENTS").contains("milestone"))
        assertTrue(Badge.perkCopy("TEAM_CAPTAIN").contains("merch"))
        assertEquals("Unlocks a member perk", Badge.perkCopy("SOMETHING_NEW"))
    }

    @Test fun anonymousReferralsAreCountedNeverNamed() {
        val net = NetworkView(3, 100, null, listOf(NetworkInvitee("Alex C.", true), NetworkInvitee("Sam R.", false)), 1)
        assertEquals("giving", net.invitees[0].statusLabel)
        assertEquals("paused", net.invitees[1].statusLabel)
        assertEquals("1 person chooses to stay anonymous", net.anonymousStayingLabel)
        assertNull(NetworkView(1, 0, null, emptyList(), 0).anonymousStayingLabel)
    }

    @Test fun inviteOnlyAccessKeysOnServerBadgeCodes() {
        fun badge(code: String, earned: Boolean) = Badge(code, code, "r", "", earned, null)
        assertTrue(BadgeGrid(listOf(badge("F", true))).hasInviteOnlyAccess)
        assertTrue(BadgeGrid(listOf(badge("1K", true))).hasInviteOnlyAccess)
        assertTrue(BadgeGrid(listOf(badge("TC", true))).hasInviteOnlyAccess)
        assertFalse(BadgeGrid(listOf(badge("TC", false))).hasInviteOnlyAccess) // not earned
        assertFalse(BadgeGrid(listOf(badge("7", true), badge("FOUNDING_DONOR", true))).hasInviteOnlyAccess)
    }
}
