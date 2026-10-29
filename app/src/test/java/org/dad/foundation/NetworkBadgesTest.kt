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
        val grid = BadgeGrid(badges = PreviewNetworkBadgesService().let { svc ->
            kotlinx.coroutines.runBlocking { svc.snapshot().grid.badges }
        })
        assertEquals(24, grid.badges.size)
        assertEquals(7, grid.earned.size)
        grid.badges.forEach { badge ->
            assertTrue("a11y label must be non-blank for ${badge.code}", badge.accessibilityLabel.isNotBlank())
            assertTrue("detail line must be non-blank for ${badge.code}", badge.detailLine.isNotBlank())
            if (badge.earned) assertTrue(badge.accessibilityLabel.endsWith("earned"))
            else assertTrue(badge.accessibilityLabel.contains("locked"))
        }
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
            irlLink = "https://dad.org/r/alex/irl",
        )
        assertTrue(on.isPublic)
        assertEquals("https://dad.org/r/alex", on.shareLink)
        assertEquals("https://dad.org/r/alex/irl", on.inPersonLink)
        assertEquals("Shown as Alex C.", on.visibilityLabel)
    }

    @Test fun sharingWithBlankNameTreatedAsAnonymous() {
        // A blank public name is not "opted in" — guards against an empty-string leak.
        val blank = SharingState(publicName = "  ", link = "x", irlLink = "y")
        assertFalse(blank.isPublic)
        assertNull(blank.shareLink)
    }

    // --- Model state machine ---
    private class FakeService(
        var failLoad: Boolean = false,
        var failName: Boolean = false,
    ) : NetworkBadgesService {
        var sharing = SharingState(null, null, null)
        override suspend fun snapshot(): NetworkBadgesSnapshot {
            if (failLoad) throw AccountFailure.SignedOut
            return NetworkBadgesSnapshot(
                network = NetworkView(5, 348_000, NetworkMilestone("Circle of 10", 10, 5), emptyList(), 2),
                grid = BadgeGrid(listOf(Badge("A", "A", "rule", "perk", earned = false, earnedAt = null))),
                sharing = sharing,
            )
        }
        override suspend fun choosePublicName(name: String): SharingState {
            if (failName) throw AccountFailure.Unavailable
            sharing = SharingState(name, "https://dad.org/r/x", "https://dad.org/r/x/irl")
            return sharing
        }
    }

    @Test fun loadReadySetsSnapshot() = runTest {
        val model = NetworkBadgesModel(FakeService())
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(NetworkBadgesPhase.Ready, model.phase)
        assertEquals(5L, model.snapshot?.network?.activeCount)
        assertFalse(model.snapshot!!.sharing.isPublic)
    }

    @Test fun loadFailureSurfacesFailedPhase() = runTest {
        val model = NetworkBadgesModel(FakeService(failLoad = true))
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is NetworkBadgesPhase.Failed)
    }

    @Test fun choosingPublicNameMintsLinksInPlace() = runTest {
        val model = NetworkBadgesModel(FakeService())
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.snapshot!!.sharing.isPublic)
        model.choosePublicName("Alex C.")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.snapshot!!.sharing.isPublic)
        assertEquals("https://dad.org/r/x", model.snapshot!!.sharing.shareLink)
        // Still Ready — the rest of the snapshot is intact.
        assertEquals(NetworkBadgesPhase.Ready, model.phase)
    }

    @Test fun blankNameIsIgnored() = runTest {
        val model = NetworkBadgesModel(FakeService())
        model.load()
        dispatcher.scheduler.advanceUntilIdle()
        model.choosePublicName("   ")
        dispatcher.scheduler.advanceUntilIdle()
        // No-op: still anonymous, still Ready.
        assertFalse(model.snapshot!!.sharing.isPublic)
        assertEquals(NetworkBadgesPhase.Ready, model.phase)
    }
}
