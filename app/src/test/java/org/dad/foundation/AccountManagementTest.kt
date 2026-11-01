package org.dad.foundation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

private const val SESSION_LIST = """{"sessions":[
 {"id":"11111111-1111-4111-8111-111111111111","client":"APP","platform":"ANDROID","label":"Pixel","created_at":"2026-10-01T10:00:00Z","last_used_at":"2026-10-30T10:00:00Z","current":true},
 {"id":"22222222-2222-4222-8222-222222222222","client":"BROWSER","platform":null,"label":"Web browser","created_at":"2026-10-02T10:00:00Z","last_used_at":"2026-10-29T10:00:00Z","current":false}
]}"""

class ProfileRulesTest {
    @Test fun zipAcceptsFiveDigitsZipPlusFourOrEmpty() {
        listOf("78701", "78701-1234", "", " 78701 ").forEach { assertTrue(it, ProfileRules.isAcceptableZip(it)) }
        listOf("7870", "787011", "78701-12", "abcde").forEach { assertFalse(it, ProfileRules.isAcceptableZip(it)) }
    }

    @Test fun phoneNormalizesTheWaysPeopleTypeAUsNumber() {
        assertEquals("+14155550123", ProfileRules.e164("(415) 555-0123"))
        assertEquals("+14155550123", ProfileRules.e164("1 415 555 0123"))
        assertEquals("+442079460958", ProfileRules.e164("+44 20 7946 0958"))
        assertEquals("", ProfileRules.e164(""))
        assertNull(ProfileRules.e164("555-0123"))
        assertNull(ProfileRules.e164("+0123"))
        assertNull(ProfileRules.e164("call me"))
    }

    @Test fun onlyAGoogleWalletSaveLinkIsOpened() {
        assertTrue(ProfileRules.isGoogleWalletSaveUrl("https://pay.google.com/gp/v/save/eyJhbGciOi"))
        listOf("http://pay.google.com/gp/v/save/x", "https://pay.google.com.evil.test/gp/v/save/x", "https://pay.google.com/other",
            "https://user@pay.google.com/gp/v/save/x", "https://pay.google.com:8443/gp/v/save/x", "not a url")
            .forEach { assertFalse(it, ProfileRules.isGoogleWalletSaveUrl(it)) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountManagementModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val profile = ProfileSummary("alex@example.org", "DONOR", "78701", "Austin", null)

    private class Fake : AccountManagementService {
        var failure: Exception? = null
        val saved = mutableListOf<Triple<String, String, String>>()
        val emailRequests = mutableListOf<String>()
        var list = listOf(
            DeviceSessionSummary("this", "Pixel", true, OffsetDateTime.parse("2026-10-30T10:00:00Z"), true),
            DeviceSessionSummary("web", "Web browser", false, OffsetDateTime.parse("2026-10-29T10:00:00Z"), false),
        )
        var team = EmployerTeam(null, null, emptyList())
        var everywhere = 0
        private fun check() { failure?.let { throw it } }

        override suspend fun saveProfile(zip: String, city: String, phone: String): ProfileSummary {
            check(); saved += Triple(zip, city, phone)
            return ProfileSummary("alex@example.org", "DONOR", zip.ifEmpty { null }, city.ifEmpty { null }, phone.ifEmpty { null })
        }
        override suspend fun requestEmailChange(email: String) { check(); emailRequests += email }
        override suspend fun sessions(): List<DeviceSessionSummary> { check(); return list }
        override suspend fun revokeSession(id: String): List<DeviceSessionSummary> {
            check()
            if (list.none { it.id == id }) throw AccountFailure.NotFound
            list = list.filterNot { it.id == id }
            return list
        }
        override suspend fun revokeOtherSessions(): List<DeviceSessionSummary> { check(); list = list.filter { it.current }; return list }
        override suspend fun signOutEverywhere() { check(); everywhere++ }
        override suspend fun employer(): EmployerTeam { check(); return team }
        override suspend fun enroll(companyCode: String): String {
            check()
            if (companyCode != "ACME42") throw AccountFailure.InvalidInput
            team = EmployerTeam("Acme", null, emptyList())
            return "Acme"
        }
        override suspend fun joinTeam(name: String) { check(); team = team.copy(teamName = name) }
    }

    @Test fun profileSavesOnlyAValidChangeAndSendsE164() {
        val service = Fake()
        val model = ProfileDetailsModel(profile, service)
        assertFalse("nothing changed", model.canSave)
        model.phone = "415 555 012"
        assertFalse(model.canSave)
        model.phone = "(415) 555-0123"
        model.zip = "7870"
        assertFalse(model.canSave)
        model.zip = "78702"
        model.save()
        assertEquals(DetailsPhase.Saved, model.phase)
        assertEquals(Triple("78702", "Austin", "+14155550123"), service.saved.single())
        assertEquals("+14155550123", model.phone)
        assertFalse("the saved values are the new baseline", model.canSave)
    }

    @Test fun profileRejectedByTheServerExplainsItself() {
        val service = Fake().apply { failure = AccountFailure.InvalidInput }
        val model = ProfileDetailsModel(profile, service).apply { zip = "" }
        model.save()
        assertEquals(DetailsPhase.Failed("Check the ZIP code and phone number, then try again."), model.phase)
    }

    @Test fun emailChangeNeedsANewPlausibleAddressAndNamesAConflict() {
        val service = Fake()
        val model = EmailChangeModel("alex@example.org", service)
        model.email = "ALEX@example.org"
        assertFalse(model.canSubmit)
        model.email = " new@example.org "
        model.submit()
        assertEquals(EmailChangePhase.Sent, model.phase)
        assertEquals(listOf("new@example.org"), service.emailRequests)

        service.failure = AccountFailure.Conflict
        val conflicted = EmailChangeModel("alex@example.org", service).apply { email = "taken@example.org" }
        conflicted.submit()
        assertEquals(EmailChangePhase.Failed("That address can’t replace your account email. Existing accounts are never merged."), conflicted.phase)
    }

    @Test fun sessionsRevokeOneThenOthersAndAGoneOneReloads() {
        val service = Fake()
        val model = DeviceSessionsModel(service)
        model.load()
        assertEquals(LoadPhase.Ready, model.phase)
        assertEquals(listOf("web"), model.others.map { it.id })
        model.revoke("web")
        assertTrue(model.others.isEmpty())
        model.revoke("gone")
        assertNull(model.actionError)
        assertEquals(LoadPhase.Ready, model.phase)
        model.revokeOthers()
        assertEquals("Signed out of every other device.", model.notice)
    }

    @Test fun signOutEverywhereLeavesOnlyWhenConfirmed() {
        val service = Fake().apply { failure = AccountFailure.Unavailable }
        val model = DeviceSessionsModel(service)
        model.signOutEverywhere()
        assertFalse(model.signedOut)
        assertNotNull(model.actionError)
        service.failure = null
        model.signOutEverywhere()
        assertTrue(model.signedOut)
        assertEquals(1, service.everywhere)
    }

    @Test fun employerEnrollThenJoinTeam() {
        val service = Fake()
        val model = EmployerModel(service)
        model.load()
        assertEquals(EmployerTeam(null, null, emptyList()), model.team)
        model.companyCode = "WRONG"
        model.enroll()
        assertEquals("That company code isn’t recognized. Check it with your employer and try again.", model.actionError)
        model.companyCode = " ACME42 "
        model.enroll()
        assertNull(model.actionError)
        assertEquals("", model.companyCode)
        assertEquals("Acme", model.team?.organization)
        model.teamName = "x".repeat(81)
        assertFalse(model.canJoinTeam)
        model.teamName = "Platform"
        model.joinTeam()
        assertEquals("Platform", model.team?.teamName)
    }

    @Test fun employerAlreadyLinkedElsewhere() {
        val service = Fake()
        val model = EmployerModel(service).apply { load() }
        service.failure = AccountFailure.Conflict
        model.companyCode = "OTHER"
        model.enroll()
        assertEquals("Your account is already linked to another employer.", model.actionError)
    }

    private class FakeWallet(var available: Boolean = true, var url: String = "https://pay.google.com/gp/v/save/abc", var fails: Boolean = false) : WalletPassService {
        override suspend fun googleWalletAvailable(): Boolean { if (fails) throw AccountFailure.Unavailable; return available }
        override suspend fun googlePassUrl(): String { if (fails) throw AccountFailure.Unavailable; return url }
    }

    @Test fun walletOpensOnlyAValidatedSaveLink() {
        val wallet = FakeWallet()
        val model = WalletPassModel(wallet)
        model.check()
        model.prepare()
        assertEquals("https://pay.google.com/gp/v/save/abc", model.saveUrl)

        val hostile = WalletPassModel(FakeWallet(url = "https://evil.test/gp/v/save/abc")).apply { check() }
        hostile.prepare()
        assertNull(hostile.saveUrl)
        assertEquals(WalletPassModel.FAILURE, hostile.error)
    }

    @Test fun walletUnavailableOrFailingSaysTheCodeStillWorks() {
        val off = WalletPassModel(FakeWallet(available = false)).apply { check() }
        assertEquals(false, off.available)
        off.prepare()
        assertNull(off.saveUrl)

        val wallet = FakeWallet(fails = true)
        val failing = WalletPassModel(wallet).apply { check() }
        assertTrue(failing.checkFailed)
        wallet.fails = false
        failing.check()
        wallet.fails = true
        failing.prepare()
        assertEquals(WalletPassModel.FAILURE, failing.error)
    }
}

class WireAccountManagementTest {
    private class Rig(scope: kotlinx.coroutines.CoroutineScope, handler: (Recorded) -> Pair<Int, String>) {
        val http = ScriptedHttp(handler)
        val store = RecordingStore(validCredentials())
        val client = http.client()
        val session = AccountSession(WireAPI(TEST_ORIGIN, client), store, scope)
        val wire = WireAccountManagement(ProductClient(TEST_ORIGIN, session, client))
        val wallet = WireWalletPass(ProductClient(TEST_ORIGIN, session, client))
    }
    private suspend fun failure(block: suspend () -> Any?): Throwable? = runCatching { block() }.exceptionOrNull()

    @Test fun saveProfileSendsAllThreeFieldsAndMapsTheProfile() = runTest {
        val rig = Rig(backgroundScope) { 200 to profileJson() }
        val saved = rig.wire.saveProfile("78701", "Austin", "")
        assertEquals("alex@example.org", saved.email)
        val sent = rig.http.requests.single()
        assertEquals("POST", sent.method)
        assertEquals("/donor/me/profile", sent.path)
        assertEquals("Bearer access-0", sent.headers["authorization"])
        assertEquals("""{"zip":"78701","city":"Austin","phone":""}""", sent.body)
        assertEquals(AccountFailure.InvalidInput, failure { Rig(backgroundScope) { 422 to "{}" }.wire.saveProfile("1", "", "") })
    }

    @Test fun emailChangeStatusMapping() = runTest {
        val rig = Rig(backgroundScope) { 200 to """{"stage":"CURRENT"}""" }
        rig.wire.requestEmailChange("new@example.org")
        assertEquals("""{"email":"new@example.org"}""", rig.http.requests.single().body)
        mapOf(409 to AccountFailure.Conflict, 422 to AccountFailure.InvalidInput, 503 to AccountFailure.Unavailable, 429 to AccountFailure.RateLimited)
            .forEach { (status, expected) ->
                assertEquals("$status", expected, failure { Rig(backgroundScope) { status to "{}" }.wire.requestEmailChange("x@example.org") })
            }
    }

    @Test fun sessionsMapCurrentAndClientAndRevokeAddressesTheId() = runTest {
        val rig = Rig(backgroundScope) { 200 to SESSION_LIST }
        val list = rig.wire.sessions()
        assertEquals(listOf(true, false), list.map { it.current })
        assertEquals(listOf(true, false), list.map { it.isApp })
        rig.wire.revokeSession("22222222-2222-4222-8222-222222222222")
        assertEquals("/donor/me/sessions/22222222-2222-4222-8222-222222222222/revoke", rig.http.requests.last().path)
        assertEquals(AccountFailure.NotFound, failure { Rig(backgroundScope) { 404 to "{}" }.wire.revokeSession("x") })
    }

    @Test fun signOutEverywhereClearsLocalOnlyAfterTheServerConfirms() = runTest {
        val failing = Rig(backgroundScope) { 503 to "{}" }
        assertNotNull(failure { failing.wire.signOutEverywhere() })
        assertNotNull("a failed request must not pretend every device was signed out", failing.store.value)

        val rig = Rig(backgroundScope) { 200 to """{"signed_out":true}""" }
        rig.wire.signOutEverywhere()
        assertNull(rig.store.value)
        assertEquals(1, rig.http.count("/donor/logout-all"))
    }

    @Test fun employerMapsUnlinkedAndLinked() = runTest {
        assertEquals(EmployerTeam(null, null, emptyList()),
            Rig(backgroundScope) { 200 to """{"partner_id":"","organization":"","leaderboard":[]}""" }.wire.employer())
        val linked = Rig(backgroundScope) {
            200 to """{"partner_id":"p","organization":"Acme","team_id":"t","team_name":"Platform","leaderboard":[{"team_id":"t","name":"Platform","active_givers":6,"annualized_minor":360000,"avg_streak_days":12,"volunteer_minutes":90}]}"""
        }.wire.employer()
        assertEquals("Acme", linked.organization)
        assertEquals("Platform", linked.teamName)
        assertEquals(6L, linked.leaderboard.single().activeGivers)
    }

    @Test fun enrollAndJoinTeamStatusMapping() = runTest {
        val rig = Rig(backgroundScope) { 200 to """{"organization":"Acme"}""" }
        assertEquals("Acme", rig.wire.enroll("ACME42"))
        assertEquals("""{"company_code":"ACME42"}""", rig.http.requests.single().body)
        assertEquals(AccountFailure.InvalidInput, failure { Rig(backgroundScope) { 422 to "{}" }.wire.enroll("X") })
        assertEquals(AccountFailure.Conflict, failure { Rig(backgroundScope) { 409 to "{}" }.wire.enroll("X") })
        val join = Rig(backgroundScope) { 200 to """{"team_id":"t"}""" }
        join.wire.joinTeam("Platform")
        assertEquals("""{"name":"Platform"}""", join.http.requests.single().body)
    }

    @Test fun walletProvidersAndGoogleSaveLink() = runTest {
        val rig = Rig(backgroundScope) {
            if (it.path == "/donor/me/wallet") 200 to """{"apple":false,"google":true}"""
            else 200 to """{"url":"https://pay.google.com/gp/v/save/abc"}"""
        }
        assertTrue(rig.wallet.googleWalletAvailable())
        assertEquals("https://pay.google.com/gp/v/save/abc", rig.wallet.googlePassUrl())
        assertEquals("POST", rig.http.requests.last().method)
        assertEquals(AccountFailure.Conflict, failure { Rig(backgroundScope) { 409 to "{}" }.wallet.googlePassUrl() })
    }
}
