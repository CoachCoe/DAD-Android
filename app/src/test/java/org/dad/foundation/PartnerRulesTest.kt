package org.dad.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime
import java.util.Date

/**
 * How the partner models turn each server answer into copy and state: a viewer the server refuses, the guards the server
 * enforces too (never yourself, never the last admin), an ended session, throttling, and the applicant's status card.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PartnerRulesTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** Every write throws [failure] when set, so a test can play any server answer against the model's own gating. */
    private class Service(private val role: PartnerRole = PartnerRole.ADMIN, var failure: Exception? = null) : PartnerService {
        var users = mutableListOf(
            PartnerUser("u1", "ada@x.com", "Ada", PartnerRole.ADMIN),
            PartnerUser("u2", "val@x.com", "Val", PartnerRole.VIEWER),
        )
        var exportsList = listOf(PartnerExport("r1", OffsetDateTime.now(), "Ada", 1, 1, downloadable = true))
        var retainedFailure: Exception? = null
        var writes = 0
        override suspend fun overview() = PartnerOverview("Acme", "", role, "AC", VerifiedMoney(1, true), VerifiedMoney(1, true), 1,
            VerifiedCount(1, true), 5, 10, 50, Date(), hasLogo = false, userId = if (role == PartnerRole.ADMIN) "u1" else "u2")
        override suspend fun initiatives() = emptyList<PartnerInitiative>()
        override suspend fun spend() = emptyList<PartnerSpendCategory>()
        override suspend fun people() = PartnerPeople(5, 10, 0, 0, 0, 0, 0, false)
        override suspend fun teams() = emptyList<TeamStanding>()
        override suspend fun users() = users.toList()
        private fun write() { writes++; failure?.let { throw it } }
        override suspend fun inviteUser(email: String, name: String, role: PartnerRole) { write() }
        override suspend fun removeUser(id: String) { write(); users.removeAll { it.id == id } }
        override suspend fun saveProfile(organization: String, description: String) { write() }
        override suspend fun saveWorkforce(size: Int) { write() }
        override suspend fun logo(): ByteArray? = null
        override suspend fun saveLogo(png: ByteArray?) { write() }
        override suspend fun exports() = exportsList
        override suspend fun report(): ByteArray { write(); return "%PDF".toByteArray() }
        override suspend fun retainedReport(id: String): ByteArray { retainedFailure?.let { throw it }; return "%PDF".toByteArray() }
        override suspend fun signOut() {}
        override suspend fun registerPushDevice(token: String) {}
        override suspend fun unregisterPushDevice() {}
    }

    private fun loaded(service: Service) = PartnerModel(service).also { it.load(); dispatcher.scheduler.advanceUntilIdle() }

    @Test fun aViewerTheServerRefusesIsToldOnlyAnAdminCanDoThat() = runTest {
        // The model's own gate is open (an admin by its snapshot) but the server says 403, e.g. the role was just revoked.
        val service = Service(failure = AccountFailure.Forbidden)
        val model = loaded(service)
        model.inviteUser("new@x.com", "New", PartnerRole.VIEWER); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Only an admin can do that.", model.actionError)
        model.exportReport(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Only an admin can do that.", model.actionError)
        assertNull(model.report)
        assertFalse(model.isBusy)
    }

    @Test fun aViewerNeverReachesTheServerForAdminActions() = runTest {
        val service = Service(role = PartnerRole.VIEWER)
        val model = loaded(service)
        assertTrue(model.snapshot!!.exports.isEmpty())
        model.inviteUser("new@x.com", "New", PartnerRole.VIEWER)
        model.requestRemoval("u1")
        model.exportReport()
        model.saveProfile("Acme", "About")
        model.saveWorkforce("10")
        model.saveLogo(byteArrayOf(1))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, service.writes)
        assertNotNull(model.actionError)
        assertNull(model.pendingRemoval)
        assertFalse(model.canRemove(PartnerUser("u1", "a@b.co", "Ada", PartnerRole.ADMIN)))
    }

    @Test fun neverYourselfAndRemovalNeedsConfirmation() = runTest {
        val service = Service()
        val model = loaded(service)
        model.requestRemoval("u1")
        assertEquals("You can’t remove yourself.", model.actionError)
        assertNull(model.pendingRemoval)
        model.requestRemoval("u2")
        assertNull(model.actionError)
        assertEquals("u2", model.pendingRemoval?.id)
        model.cancelRemoval()
        assertNull(model.pendingRemoval)
        assertEquals(0, service.writes)
    }

    @Test fun aConflictOnRemovalIsExplainedAndNobodyDisappears() = runTest {
        val service = Service(failure = AccountFailure.Conflict)
        val model = loaded(service)
        model.requestRemoval("u2"); model.confirmRemoval(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("You can’t remove the last admin.", model.actionError)
        assertEquals(listOf("u1", "u2"), model.snapshot!!.users.map { it.id })
    }

    @Test fun anInviteConflictAndAnInvalidInviteAreExplainedApart() = runTest {
        val service = Service(failure = AccountFailure.Conflict)
        val model = loaded(service)
        model.inviteUser("new@x.com", "New", PartnerRole.VIEWER); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("That email already signs in to DAD with another role or organization.", model.actionError)
        service.failure = AccountFailure.InvalidInput
        model.inviteUser("new@x.com", "New", PartnerRole.VIEWER); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Check the name and email address, then try again.", model.actionError)
    }

    @Test fun throttlingAndOutagesKeepThePortalAndSayWhatHappened() = runTest {
        val service = Service(failure = AccountFailure.RateLimited)
        val model = loaded(service)
        model.saveProfile("Acme", "About"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Too many attempts. Please wait a moment and try again.", model.actionError)
        service.failure = AccountFailure.Unavailable
        model.saveProfile("Acme", "About"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Something went wrong. Please try again.", model.actionError)
        assertEquals(PartnerPhase.Ready, model.phase)
        assertFalse(model.signedOut)
    }

    @Test fun aRejectedSessionLeavesThePortalForPartnerSignIn() = runTest {
        val service = Service(failure = AccountFailure.SignedOut)
        val model = loaded(service)
        model.saveProfile("Acme", "About"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.signedOut)
    }

    @Test fun anActionCancelledMidFlightKeepsThePriorState() = runTest {
        val service = Service(failure = CancellationException("left the screen"))
        val model = loaded(service)
        val before = model.snapshot
        model.saveProfile("Acme", "About"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(before, model.snapshot)
        assertEquals(PartnerPhase.Ready, model.phase)
        assertNull(model.actionError)
        assertFalse(model.signedOut)
    }

    @Test fun anAgedOutReportIsExplainedAndTheListRefreshes() = runTest {
        val service = Service()
        val model = loaded(service)
        service.retainedFailure = AccountFailure.NotFound
        service.exportsList = emptyList()
        model.download(model.snapshot!!.exports.single()); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("That report is no longer available. Export a new one.", model.actionError)
        assertTrue(model.snapshot!!.exports.isEmpty())
        assertNull(model.report)
    }

    // --- the applicant's status card ---

    private class Status(var result: () -> ApplicationStatusInfo?) : ApplicationStatusService {
        var reads = 0
        override suspend fun status(): ApplicationStatusInfo? { reads++; return result() }
    }

    @Test fun noReceiptShowsNothing() = runTest {
        val model = ApplicationStatusModel(Status { null })
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ApplicationStatusPhase.None, model.phase)
    }

    @Test fun aPendingApplicationShowsItsStatus() = runTest {
        val model = ApplicationStatusModel(Status { ApplicationStatusInfo("Acme", ApplicationState.Pending) })
        dispatcher.scheduler.advanceUntilIdle()
        val phase = model.phase as ApplicationStatusPhase.Loaded
        assertEquals("Application under review", phase.info.title)
    }

    @Test fun aFailedReadOffersARetryAndNeverReplacesAStatusAlreadyRead() = runTest {
        val status = Status { throw AccountFailure.Unavailable }
        val model = ApplicationStatusModel(status)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is ApplicationStatusPhase.Failed)
        status.result = { ApplicationStatusInfo("Acme", ApplicationState.Approved) }
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is ApplicationStatusPhase.Loaded)
        status.result = { throw AccountFailure.RateLimited }
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is ApplicationStatusPhase.Loaded)
    }

    @Test fun aCancelledReadKeepsWhatWasShowing() = runTest {
        val status = Status { ApplicationStatusInfo("Acme", ApplicationState.Rejected) }
        val model = ApplicationStatusModel(status)
        dispatcher.scheduler.advanceUntilIdle()
        status.result = { throw CancellationException("left the screen") }
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ApplicationState.Rejected, (model.phase as ApplicationStatusPhase.Loaded).info.state)
    }
}
