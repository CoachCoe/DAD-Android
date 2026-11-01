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
        var fail: Boolean = false,
        var peopleError: Exception? = null,
        var pushError: Exception? = null,
        var actionError: Exception? = null,
        var retainedError: Exception? = null,
        var hasLogo: Boolean = false,
    ) : PartnerService {
        var invited = mutableListOf<Triple<String, String, PartnerRole>>()
        val profiles = mutableListOf<Pair<String, String>>()
        val workforce = mutableListOf<Int>()
        val logos = mutableListOf<ByteArray?>()
        var reports = 0
        var signedOut = false
        var removed = mutableListOf<String>()
        var pushRegistered = false
        var pushUnregistered = false
        var users = mutableListOf(
            PartnerUser("u1", "a@x.com", "A", PartnerRole.ADMIN),
            PartnerUser("u2", "b@x.com", "B", PartnerRole.VIEWER),
            PartnerUser("u3", "c@x.com", "C", PartnerRole.ADMIN),
        )
        var selfId: String? = "u1"
        override suspend fun overview(): PartnerOverview {
            if (fail) throw AccountFailure.SignedOut
            return PartnerOverview("Acme", "d", role, "AC", VerifiedMoney(1, true), VerifiedMoney(1, true),
                1, VerifiedCount(1, true), 42, workforce.lastOrNull() ?: 120, 35, Date(), hasLogo, userId = selfId)
        }
        override suspend fun initiatives() = emptyList<PartnerInitiative>()
        override suspend fun spend() = emptyList<PartnerSpendCategory>()
        override suspend fun people(): PartnerPeople {
            peopleError?.let { throw it }
            return PartnerPeople(42, 120, 0, 0, 0, 0, 0, false)
        }
        override suspend fun teams() = emptyList<TeamStanding>()
        override suspend fun users() = users.toList()
        override suspend fun inviteUser(email: String, name: String, role: PartnerRole) {
            actionError?.let { throw it }
            invited.add(Triple(email, name, role))
        }
        override suspend fun removeUser(id: String) { actionError?.let { throw it }; removed.add(id); users.removeAll { it.id == id } }
        override suspend fun saveProfile(organization: String, description: String) { actionError?.let { throw it }; profiles.add(organization to description) }
        override suspend fun saveWorkforce(size: Int) { workforce.add(size) }
        override suspend fun logo() = "LOGO".toByteArray()
        override suspend fun saveLogo(png: ByteArray?) { logos.add(png) }
        override suspend fun exports() = listOf(PartnerExport("r1", OffsetDateTime.now(), "A", 1, 1, downloadable = true))
        override suspend fun report(): ByteArray { reports++; return "%PDF".toByteArray() }
        override suspend fun retainedReport(id: String): ByteArray { retainedError?.let { throw it }; return "%PDF".toByteArray() }
        override suspend fun signOut() { signedOut = true }
        override suspend fun registerPushDevice(token: String) { pushError?.let { throw it }; pushRegistered = true }
        override suspend fun unregisterPushDevice() { pushUnregistered = true }
    }

    @Test fun loadReadyCarriesRole() = runTest {
        val model = PartnerModel(FakeService())
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PartnerPhase.Ready, model.phase)
        assertEquals(PartnerRole.ADMIN, model.role)
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
        model.inviteUser("b@x.com", "Bea", PartnerRole.VIEWER); dispatcher.scheduler.advanceUntilIdle()
        model.removeUser("u1"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(svc.invited.isEmpty()) // gated client-side, never hit service
        assertTrue(svc.removed.isEmpty())
        assertTrue(model.actionError != null)
    }

    @Test fun adminCanInvite() = runTest {
        val svc = FakeService()
        val model = PartnerModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.inviteUser("b@x.com", "Bea", PartnerRole.VIEWER); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, svc.invited.size)
    }

    @Test fun pushRegistersAndSignOutUnregistersThenEndsTheSession() = runTest {
        val svc = FakeService()
        val model = PartnerModel(svc)
        model.registerPush("t"); dispatcher.scheduler.advanceUntilIdle()
        model.signOut(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(svc.pushRegistered)
        assertTrue(svc.pushUnregistered)
        assertTrue(svc.signedOut)
        assertTrue(model.signedOut)
    }

    @Test fun endedSessionOnASecondaryReadFailsTheLoad() = runTest {
        val model = PartnerModel(FakeService(peopleError = AccountFailure.SignedOut))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.phase is PartnerPhase.Failed)
    }

    @Test fun otherSecondaryFailureHidesOnlyThatSection() = runTest {
        val model = PartnerModel(FakeService(peopleError = AccountFailure.Unavailable))
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PartnerPhase.Ready, model.phase)
        assertNull(model.snapshot!!.people)
        assertEquals("Acme", model.snapshot!!.overview.organization)
    }

    @Test fun failedPushRegistrationIsSilentAndDoesNotCrash() = runTest {
        val svc = FakeService(pushError = AccountFailure.Unavailable)
        val model = PartnerModel(svc)
        model.registerPush("t"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(!svc.pushRegistered)
        assertNull(model.actionError)
    }

    @Test fun emailShapeCheckAcceptsAddressesAndRejectsJunk() {
        assertTrue(isPlausibleEmail(" ada@acme.com "))
        assertTrue(!isPlausibleEmail(""))
        assertTrue(!isPlausibleEmail("ada@acme"))
        assertTrue(!isPlausibleEmail("ada acme@x.com"))
        assertTrue(!isPlausibleEmail("a@@b.com"))
        assertTrue(!isPlausibleEmail("a@b..c"))
        assertTrue(!isPlausibleEmail("a@.b.c"))
        assertTrue(!isPlausibleEmail("a@b.c."))
        assertTrue(!isPlausibleEmail("@b.com"))
        assertTrue(isPlausibleEmail("a@b.co.uk"))
        assertTrue(!isPlausibleEmail("a".repeat(250) + "@b.com")) // > 254 chars
    }

    @Test fun inviteWithAMalformedEmailNeverReachesTheService() = runTest {
        val svc = FakeService()
        val model = PartnerModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        model.inviteUser("not-an-email", "Bea", PartnerRole.VIEWER); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(svc.invited.isEmpty())
        assertTrue(model.actionError != null)
    }

    @Test fun countsAreThousandsGrouped() {
        assertEquals("12,400", VerifiedCount(12_400, verified = true).label)
        assertEquals("8,400", PartnerMetric("People reached", 8400, PartnerMetric.Unit.PEOPLE).valueLabel)
    }

    @Test fun adminCannotRemoveThemselves() = runTest {
        val svc = FakeService()
        val model = PartnerModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.canRemove(svc.users.first { it.id == "u1" }))
        model.removeUser("u1"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(svc.removed.isEmpty())
        assertTrue(model.actionError != null)
    }

    @Test fun adminCanRemoveAnotherUser() = runTest {
        val svc = FakeService()
        val model = PartnerModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.canRemove(svc.users.first { it.id == "u2" }))
        model.removeUser("u2"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("u2"), svc.removed)
        assertNull(model.actionError)
    }

    @Test fun lastAdminCannotBeRemovedEvenWhenSelfIsUnknown() = runTest {
        val svc = FakeService().apply { selfId = null; users.removeAll { it.id == "u3" } }
        val model = PartnerModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.canRemove(svc.users.first { it.id == "u1" }))
        model.removeUser("u1"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(svc.removed.isEmpty())
    }

    @Test fun selfIdComesFromOverviewUserId() = runTest {
        val svc = FakeService().apply { selfId = "u3" }
        val model = PartnerModel(svc)
        model.load(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("u3", model.snapshot?.selfId)
        assertFalse(model.canRemove(svc.users.first { it.id == "u3" }))
    }

    private fun loaded(svc: FakeService) = PartnerModel(svc).also { it.load(); dispatcher.scheduler.advanceUntilIdle() }

    @Test fun inviteNeedsANameSendsItTrimmedAndExplainsAConflict() = runTest {
        val svc = FakeService()
        val model = loaded(svc)
        model.inviteUser("b@x.com", "  ", PartnerRole.VIEWER); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Enter their name.", model.actionError)
        model.inviteUser(" new@acme.com ", " Nell ", PartnerRole.ADMIN); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(Triple("new@acme.com", "Nell", PartnerRole.ADMIN), svc.invited.single())
        assertEquals("Invitation sent to new@acme.com.", model.notice)
        svc.actionError = AccountFailure.Conflict
        model.inviteUser("taken@acme.com", "Tak", PartnerRole.VIEWER); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("That email already signs in to DAD with another role or organization.", model.actionError)
    }

    @Test fun anEndedSessionDuringAnActionLeavesThePortal() = runTest {
        val svc = FakeService()
        val model = loaded(svc)
        svc.actionError = AccountFailure.SignedOut
        model.removeUser("u2"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.signedOut)
    }

    @Test fun profileIsValidatedTrimmedAndReloaded() = runTest {
        val svc = FakeService()
        val model = loaded(svc)
        model.saveProfile("  ", "x")
        assertEquals(PartnerModel.PROFILE_RULE, model.actionError)
        model.saveProfile("a".repeat(201), "")
        assertEquals(PartnerModel.PROFILE_RULE, model.actionError)
        assertTrue(svc.profiles.isEmpty())
        model.saveProfile(" Acme Co ", " Builds wells. "); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Acme Co" to "Builds wells.", svc.profiles.single())
        assertEquals("Organization profile saved.", model.notice)
    }

    @Test fun viewerCannotEditProfileWorkforceLogoOrExport() = runTest {
        val svc = FakeService(role = PartnerRole.VIEWER)
        val model = loaded(svc)
        model.saveProfile("Acme", "")
        model.saveWorkforce("300")
        model.saveLogo("P".toByteArray())
        model.exportReport()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(svc.profiles.isEmpty() && svc.workforce.isEmpty() && svc.logos.isEmpty())
        assertEquals(0, svc.reports)
        assertTrue(model.snapshot!!.exports.isEmpty())
    }

    @Test fun workforceMustBeAWholeNumber() = runTest {
        val svc = FakeService()
        val model = loaded(svc)
        for (bad in listOf("", "-1", "12.5", "lots")) {
            model.saveWorkforce(bad)
            assertEquals(bad, PartnerModel.WORKFORCE_RULE, model.actionError)
        }
        model.saveWorkforce(" 300 "); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(300), svc.workforce)
        assertEquals(300, model.snapshot!!.overview.workforceSize)
    }

    @Test fun logoLoadsWhenPresentAndCanBeRemoved() = runTest {
        val svc = FakeService(hasLogo = true)
        val model = loaded(svc)
        assertEquals("LOGO", model.snapshot!!.logo?.decodeToString())
        model.saveLogo(null); dispatcher.scheduler.advanceUntilIdle()
        assertNull(model.snapshot!!.logo)
        assertEquals(listOf<ByteArray?>(null), svc.logos)
        assertEquals("Logo removed.", model.notice)
    }

    @Test fun exportHandsOverAPdfOnceAndAnAgedOutReportSaysSo() = runTest {
        val svc = FakeService(retainedError = AccountFailure.NotFound)
        val model = loaded(svc)
        model.exportReport(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("%PDF", model.report?.pdf?.decodeToString())
        model.reportShared()
        assertNull(model.report)
        model.download(model.snapshot!!.exports.first()); dispatcher.scheduler.advanceUntilIdle()
        assertNull(model.report)
        assertEquals("That report is no longer available. Export a new one.", model.actionError)
    }

    private class FakeSignIn(var error: Exception? = null) : PartnerSignInService {
        val requested = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        override suspend fun requestSignInLink(email: String) { error?.let { throw it }; requested.add(email) }
        override suspend fun accept(token: String) { error?.let { throw it }; accepted.add(token) }
    }

    @Test fun partnerSignInRequestsALinkThenAcceptsAPastedLinkOrToken() = runTest {
        val svc = FakeSignIn()
        val model = PartnerSignInModel(svc)
        model.email = "nope"
        assertFalse(model.canSubmit)
        model.email = " ada@acme.com "
        model.submit(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("ada@acme.com"), svc.requested)
        assertEquals("ada@acme.com", model.sentTo)
        model.acceptPasted(" https://dad.example/partner/accept#token=abc123 "); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PartnerSignInPhase.SignedIn, model.phase)
        model.acceptPasted("def456"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("abc123", "def456"), svc.accepted)
        model.acceptPasted("two words")
        assertEquals(PartnerSignInPhase.Failed("Paste the whole sign-in link from your email."), model.phase)
    }

    @Test fun aUsedLinkAndABlockedAccountAreExplained() = runTest {
        val svc = FakeSignIn(AccountFailure.InvalidInput)
        val model = PartnerSignInModel(svc)
        model.accept("t"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PartnerSignInPhase.Failed("That sign-in link has expired or was already used. Request a new one."), model.phase)
        svc.error = AccountFailure.Forbidden
        model.accept("t"); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PartnerSignInPhase.Failed("This partner account can’t sign in. Ask your organization’s admin or DAD support."), model.phase)
    }

    @Test fun partnerSessionLastsTwelveHoursThenIsForgotten() {
        var now = 1_800_000_000_000L
        val store = MemoryCredentialStore()
        val session = PartnerSession(store) { now }
        session.install("P1")
        assertEquals("P1", session.token())
        now += PartnerSession.LIFETIME_MILLIS + 1
        assertFalse(session.isSignedIn)
        assertNull(store.load())
    }

    private class MemoryCredentialStore(private var value: Credentials? = null) : CredentialStore {
        override fun load() = value
        override fun save(value: Credentials) { this.value = value }
        override fun clear() { value = null }
    }
}
