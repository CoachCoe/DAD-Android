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
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class PartnerTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // --- Role (§7.2) ---
    @Test fun adminManagesViewerReadOnly() {
        assertTrue(PartnerRole.ADMIN.canManageUsers)
        assertTrue(PartnerRole.ADMIN.canExport)
        assertFalse(PartnerRole.VIEWER.canManageUsers)
        assertFalse(PartnerRole.VIEWER.canExport)
        assertEquals(PartnerRole.VIEWER, PartnerRole.from("SUPERUSER")) // unknown -> least privileged
    }

    // --- Verified figures (§7.3) ---
    @Test fun verifiedShownUnverifiedHidden() {
        assertEquals("$1,240", VerifiedMoney(124_000, verified = true).label)
        assertNull(VerifiedMoney(124_000, verified = false).label)
        assertEquals("42", VerifiedCount(42, verified = true).label)
        assertNull(VerifiedCount(42, verified = false).label)
    }

    private fun overview(enrolled: Long?, role: PartnerRole = PartnerRole.ADMIN) = PartnerOverview(
        "Acme", "d", role, "ACME", VerifiedMoney(500_000, true), VerifiedMoney(300_000, false), 60,
        VerifiedCount(1200, true), enrolled, 120, if (enrolled == null) null else 35,
        Date(1_793_000_000_000), hasLogo = true,
    )

    @Test fun overviewHidesUnverifiedAndSuppressedEnrollment() {
        val o = overview(42)
        assertEquals("$5,000", o.committed.label)
        assertNull(o.deployed.label) // unverified -> hidden, never estimated
        assertEquals("42 of 120", o.enrolledLabel)
        assertNull(overview(null).enrolledLabel) // <5 suppressed -> null
        assertTrue(o.verifiedLine!!.contains("Verified by DAD"))
    }

    // --- Our People suppression (EM-3) ---
    @Test fun peopleSuppressionHidesFigures() {
        val sup = PartnerPeople(3, 120, 40_000, 20_000, 10, 60, 1, suppressed = true)
        assertNull(sup.givenLabel)
        assertNull(sup.matchedLabel)
        assertTrue(sup.suppressionNotice!!.contains("Fewer than 5"))
        val shown = PartnerPeople(42, 120, 40_000, 20_000, 10, 60, 3, suppressed = false)
        assertEquals("$400", shown.givenLabel)
        assertNull(shown.suppressionNotice)
    }

    // --- Initiative metric cap (§7.2) ---
    @Test fun initiativeMetricsCappedAtThree() {
        val metrics = (1..5).map { PartnerMetric("m$it", it.toLong(), PartnerMetric.Unit.PEOPLE) }
        val i = PartnerInitiative("i", "T", "S", InitiativeStatus.ACTIVE, "West", 100_000, 50_000, metrics)
        assertEquals(3, i.headlineMetrics.size)
        assertEquals("$1,000", i.committedLabel)
        assertEquals("Active", i.status.label)
    }

    // --- Model ---
    private class FakeService(
        var role: PartnerRole = PartnerRole.ADMIN,
        var pending: Boolean = false,
        var fail: Boolean = false,
    ) : PartnerService {
        var invited = mutableListOf<Pair<String, PartnerRole>>()
        var removed = mutableListOf<String>()
        var pushRegistered = false
        var pushUnregistered = false
        var users = mutableListOf(PartnerUser("u1", "a@x.com", "A", PartnerRole.ADMIN))
        override suspend fun overview(): PartnerOverview {
            if (pending) throw PartnerPendingApproval()
            if (fail) throw AccountFailure.SignedOut
            return PartnerOverview("Acme", "d", role, "AC", VerifiedMoney(1, true), VerifiedMoney(1, true),
                1, VerifiedCount(1, true), 42, 120, 35, Date(), false)
        }
        override suspend fun initiatives() = emptyList<PartnerInitiative>()
        override suspend fun spend() = emptyList<PartnerSpendCategory>()
        override suspend fun people() = PartnerPeople(42, 120, 0, 0, 0, 0, 0, false)
        override suspend fun users() = users
        override suspend fun inviteUser(email: String, role: PartnerRole) { invited.add(email to role) }
        override suspend fun removeUser(id: String) { removed.add(id); users.removeAll { it.id == id } }
        override suspend fun registerPushDevice(token: String) { pushRegistered = true }
        override suspend fun unregisterPushDevice() { pushUnregistered = true }
    }

    @Test fun loadReadyCarriesRole() = runTest {
        val model = PartnerModel(FakeService())
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PartnerPhase.Ready, model.phase)
        assertEquals(PartnerRole.ADMIN, model.role)
    }

    @Test fun pendingApprovalGate() = runTest {
        val model = PartnerModel(FakeService(pending = true))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PartnerPhase.Pending, model.phase)
        assertNull(model.snapshot)
    }

    @Test fun loadFailureSurfacesFailed() = runTest {
        val model = PartnerModel(FakeService(fail = true))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is PartnerPhase.Failed)
    }

    @Test fun viewerCannotInviteOrRemove() = runTest {
        val svc = FakeService(role = PartnerRole.VIEWER)
        val model = PartnerModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.inviteUser("b@x.com", PartnerRole.VIEWER); dispatcher.scheduler.advanceUntilIdle()
        model.removeUser("u1"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(svc.invited.isEmpty()) // gated client-side, never hit service
        assertTrue(svc.removed.isEmpty())
        assertTrue(model.actionError != null)
    }

    @Test fun adminCanInvite() = runTest {
        val svc = FakeService()
        val model = PartnerModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.inviteUser("b@x.com", PartnerRole.VIEWER); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, svc.invited.size)
    }

    @Test fun pushRegisterAndUnregister() = runTest {
        val svc = FakeService()
        val model = PartnerModel(svc)
        model.registerPush("t"); dispatcher.scheduler.advanceUntilIdle()
        model.unregisterPush(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(svc.pushRegistered)
        assertTrue(svc.pushUnregistered)
    }
}
