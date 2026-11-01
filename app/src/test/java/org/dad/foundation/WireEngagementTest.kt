package org.dad.foundation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real engagement conformer + real AccountSession + real Retrofit stack over a scripted transport. */
class WireEngagementTest {
    private class Rig(scope: kotlinx.coroutines.CoroutineScope, handler: (Recorded) -> Pair<Int, String>) {
        val http = ScriptedHttp(handler)
        val store = RecordingStore(validCredentials())
        val client = http.client()
        val session = AccountSession(WireAPI(TEST_ORIGIN, client), store, scope)
        val wire = WireEngagement(ProductClient(TEST_ORIGIN, session, client))
    }

    private suspend fun failure(block: suspend () -> Any?): Throwable? = runCatching { block() }.exceptionOrNull()

    private val spendId = "11111111-1111-4111-8111-111111111111"
    private val readyDashboard = """{"state":"ready","computed_at":"2026-10-30T12:00:00Z","founding_donor":true,"referrals":{"irl":1,"link":2,"total_minor":12000},"streak":null,"rank":{"national_percentile":2,"city":"Austin","city_rank":4},"rankings_opted_out":false,"network":null,"impact":{"given_minor":124000,"initiatives_funded":4,"events_volunteered":2}}"""
    private val badgesJson = """{"badges":[{"code":"F","title":"Founding Donor","rule":"Started early","perk":"FOUNDING_EVENTS","earned":true,"earned_at":"2026-06-01T00:00:00Z","artwork":"x"},{"code":"TC","title":"Team Captain","rule":"20 referrals","perk":"TEAM_CAPTAIN","earned":false,"earned_at":null,"artwork":"x"},{"code":"Z","title":"New one","rule":"r","perk":"BRAND_NEW_KEY","earned":false,"earned_at":null,"artwork":"x"},{"code":"","title":"Blank code","rule":"r","perk":"","earned":false,"artwork":"x"}]}"""
    private val networkJson = """{"active_count":5,"annualized_minor":348000,"next_milestone":{"label":"Brought 12 In","target":12,"remaining":7},"invitees":[{"name":"Jordan M.","active":true}],"anonymous_active":2}"""
    private val sharingJson = """{"public_name":"Alex C.","link":"https://elsewhere.example/r/ALEX2026","irl_link":"https://elsewhere.example/r/ALEX2026?via=irl"}"""
    private val conversionsJson = """{"conversions":[{"converted_at":"2026-10-20T12:00:00Z","kind":"LINK"},{"converted_at":"2026-10-12T12:00:00Z","kind":"BRAND_NEW_KIND"}],"more":true}"""

    private fun happy(r: Recorded): Pair<Int, String> = when (r.path) {
        "/donor/me/dashboard" -> 200 to readyDashboard
        "/donor/me/badges" -> 200 to badgesJson
        "/donor/me/network" -> 200 to networkJson
        "/donor/me/sharing" -> 200 to sharingJson
        "/donor/me/conversions" -> 200 to conversionsJson
        "/donor/me/profile" -> 200 to profileJson()
        else -> 404 to "{}"
    }

    // --- snapshot ---

    @Test fun snapshotAssemblesEverySection() = runTest {
        val rig = Rig(backgroundScope, ::happy)
        val snapshot = rig.wire.snapshot()
        assertEquals("$1,240", snapshot.impact?.givenLabel)
        assertEquals("Top 2% nationally", snapshot.ranking?.nationalLabel)
        // Only the filtered, mapped badges: the blank-code row is dropped, perk keys become copy.
        assertEquals(listOf("F", "TC", "Z"), snapshot.badges!!.badges.map { it.code })
        assertEquals("Invite-only founding events", snapshot.badges!!.badges[0].perk)
        assertEquals("Unlocks a member perk", snapshot.badges!!.badges[2].perk)
        assertTrue(snapshot.badges!!.hasInviteOnlyAccess)
        assertEquals(5L, snapshot.network?.activeCount)
        assertEquals(2L, snapshot.network?.anonymousActive)
        assertEquals("ALEX2026", snapshot.referral?.code)
        assertEquals(3L, snapshot.referral?.totalConverted)
        assertEquals(listOf("LINK", "BRAND_NEW_KIND"), snapshot.referral?.conversions?.map { it.kind })
        assertTrue(snapshot.referral!!.hasMoreConversions)
        assertFalse(snapshot.needsPublicName)
        assertTrue(rig.http.requests.all { it.method == "GET" && it.headers["x-dad-client"] == "native" && it.headers["authorization"] == "Bearer access-0" })
    }

    @Test fun theInviteLinkIsNeverTakenFromTheServerHost() = runTest {
        val snapshot = Rig(backgroundScope, ::happy).wire.snapshot()
        assertEquals("https://dad.org/r/ALEX2026", snapshot.referral?.link("https://dad.org"))
    }

    @Test fun aFailedSectionIsNullNotZero() = runTest {
        val rig = Rig(backgroundScope) { r -> if (r.path == "/donor/me/badges" || r.path == "/donor/me/network") 503 to "{}" else happy(r) }
        val snapshot = rig.wire.snapshot()
        assertNull(snapshot.badges)
        assertNull(snapshot.network)
        assertNotNull(snapshot.impact)
        assertNotNull(snapshot.referral)
    }

    @Test fun aFailedConversionsReadLeavesTheCodeAndUnknownConversions() = runTest {
        val rig = Rig(backgroundScope) { r -> if (r.path == "/donor/me/conversions") 503 to "{}" else happy(r) }
        val referral = rig.wire.snapshot().referral!!
        assertEquals("ALEX2026", referral.code)
        assertNull(referral.conversions)
    }

    @Test fun anUnknownImpactTotalStaysUnknown() = runTest {
        val dashboard = readyDashboard.replace("\"given_minor\":124000", "\"given_minor\":null")
        val snapshot = Rig(backgroundScope) { r -> if (r.path == "/donor/me/dashboard") 200 to dashboard else happy(r) }.wire.snapshot()
        assertNull(snapshot.impact?.givenMinor)
        assertEquals("--", snapshot.impact?.givenLabel)
    }

    @Test fun rankStatesMapDistinctly() = runTest {
        val unranked = readyDashboard.replace(Regex("\"rank\":\\{[^}]*\\}"), "\"rank\":null")
        val notRanked = Rig(backgroundScope) { r -> if (r.path == "/donor/me/dashboard") 200 to unranked else happy(r) }.wire.snapshot().ranking!!
        assertFalse(notRanked.isOptedOut)
        assertNull(notRanked.nationalLabel)
        val hidden = readyDashboard.replace("\"rankings_opted_out\":false", "\"rankings_opted_out\":true")
        assertTrue(Rig(backgroundScope) { r -> if (r.path == "/donor/me/dashboard") 200 to hidden else happy(r) }.wire.snapshot().ranking!!.isOptedOut)
    }

    @Test fun aPreparingDashboardStillReadsTheRankingsChoiceFromTheProfile() = runTest {
        val rig = Rig(backgroundScope) { r ->
            when (r.path) {
                "/donor/me/dashboard" -> 200 to """{"state":"preparing"}"""
                "/donor/me/profile" -> 200 to profileJson(version = 3, "rankings_opted_out" to true)
                else -> happy(r)
            }
        }
        val snapshot = rig.wire.snapshot()
        assertNull(snapshot.impact)
        assertTrue(snapshot.ranking!!.isOptedOut)
    }

    @Test fun noPublicNameMeansNeedsNameAndNoConversionsRead() = runTest {
        val rig = Rig(backgroundScope) { r -> if (r.path == "/donor/me/sharing") 200 to """{"public_name":null,"link":null,"irl_link":null}""" else happy(r) }
        val snapshot = rig.wire.snapshot()
        assertTrue(snapshot.needsPublicName)
        assertNull(snapshot.referral)
        assertEquals(0, rig.http.count("/donor/me/conversions"))
    }

    @Test fun anUnusableLinkIsAnUnavailableInviteNotANamePrompt() = runTest {
        val odd = """{"public_name":"Alex","link":"https://x.example/join?ref=A","irl_link":null}"""
        val snapshot = Rig(backgroundScope) { r -> if (r.path == "/donor/me/sharing") 200 to odd else happy(r) }.wire.snapshot()
        assertNull(snapshot.referral)
        assertFalse(snapshot.needsPublicName)
    }

    @Test fun signedOutOrTotalFailureFailsTheLoad() = runTest {
        assertEquals(AccountFailure.SignedOut, failure { Rig(backgroundScope) { 401 to "{}" }.wire.snapshot() })
        assertEquals(AccountFailure.Unavailable, failure { Rig(backgroundScope) { 503 to "{}" }.wire.snapshot() })
        assertEquals(AccountFailure.RateLimited, failure { Rig(backgroundScope) { 429 to "" }.wire.snapshot() })
    }

    @Test fun malformedAndEmptyBodiesDegradeTheSectionOrFailTheLoad() = runTest {
        val partly = Rig(backgroundScope) { r -> if (r.path == "/donor/me/badges") 200 to "not json" else happy(r) }.wire.snapshot()
        assertNull(partly.badges)
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to "" }.wire.snapshot() })
    }

    @Test fun anOversizedBodyIsAnInvalidResponse() = runTest {
        val big = """{"badges":[],"pad":"${"x".repeat(1_100_000)}"}"""
        val partly = Rig(backgroundScope) { r -> if (r.path == "/donor/me/badges") 200 to big else happy(r) }.wire.snapshot()
        assertNull(partly.badges)
    }

    // --- rankings choice ---

    private fun prefsHandler(saveStatus: () -> Int, onSave: (Recorded) -> Unit = {}): (Recorded) -> Pair<Int, String> = { r ->
        when {
            r.path == "/donor/me/profile" -> 200 to profileJson(version = 3)
            r.path == "/donor/me/preferences" -> { onSave(r); saveStatus().let { it to if (it == 200) profileJson(version = 4, "rankings_opted_out" to true) else "{}" } }
            r.path == "/donor/me/dashboard" -> 200 to readyDashboard
            else -> 404 to "{}"
        }
    }

    @Test fun optingOutReadsTheProfileThenSavesTheWholeBlockWithItsVersion() = runTest {
        val rig = Rig(backgroundScope, prefsHandler({ 200 }))
        val standing = rig.wire.setRankingOptIn(false)
        assertTrue(standing.isOptedOut)
        assertEquals(listOf("/donor/me/profile", "/donor/me/preferences"), rig.http.requests.map { it.path })
        val body = rig.http.requests.last().body
        assertTrue(body.contains("\"version\":3"))
        assertTrue(body.contains("\"rankings_opted_out\":true"))
        assertTrue(body.contains("\"volunteer\":true")) // the rest of the block is carried, not reset
    }

    @Test fun optingBackInSavesFalseAndReadsTheServersPosition() = runTest {
        val rig = Rig(backgroundScope, prefsHandler({ 200 }))
        val standing = rig.wire.setRankingOptIn(true)
        assertTrue(rig.http.requests.first { it.path == "/donor/me/preferences" }.body.contains("\"rankings_opted_out\":false"))
        assertEquals("Top 2% nationally", standing.nationalLabel)
    }

    @Test fun optingBackInKeepsTheChoiceWhenThePositionReadFails() = runTest {
        val rig = Rig(backgroundScope) { r -> if (r.path == "/donor/me/dashboard") 503 to "{}" else prefsHandler({ 200 })(r) }
        val standing = rig.wire.setRankingOptIn(true)
        assertFalse(standing.isOptedOut)
        assertNull(standing.nationalLabel)
    }

    @Test fun aStaleVersionRereadsAndRetriesOnce() = runTest {
        var saves = 0
        val rig = Rig(backgroundScope, prefsHandler({ if (++saves == 1) 409 else 200 }))
        assertTrue(rig.wire.setRankingOptIn(false).isOptedOut)
        assertEquals(2, rig.http.count("/donor/me/preferences"))
        assertEquals(2, rig.http.count("/donor/me/profile"))
    }

    @Test fun aSecondStaleVersionIsAConflict() = runTest {
        val rig = Rig(backgroundScope, prefsHandler({ 409 }))
        assertEquals(AccountFailure.Conflict, failure { rig.wire.setRankingOptIn(false) })
        assertEquals(2, rig.http.count("/donor/me/preferences"))
    }

    @Test fun saveStatusesMapToTheirFailures() = runTest {
        assertEquals(AccountFailure.SignedOut, failure { val save = prefsHandler({ 401 }); Rig(backgroundScope) { r -> if (r.path == "/donor/refresh") 401 to "{}" else save(r) }.wire.setRankingOptIn(false) })
        assertEquals(AccountFailure.Forbidden, failure { Rig(backgroundScope, prefsHandler({ 403 })).wire.setRankingOptIn(false) })
        assertEquals(AccountFailure.InvalidInput, failure { Rig(backgroundScope, prefsHandler({ 422 })).wire.setRankingOptIn(false) })
        assertEquals(AccountFailure.RateLimited, failure { Rig(backgroundScope, prefsHandler({ 429 })).wire.setRankingOptIn(false) })
        assertEquals(AccountFailure.Unavailable, failure { Rig(backgroundScope, prefsHandler({ 503 })).wire.setRankingOptIn(false) })
    }

    // --- public name ---

    @Test fun choosingANameSendsItTrimmedWithoutReplacingTheCode() = runTest {
        val rig = Rig(backgroundScope) { 200 to sharingJson }
        rig.wire.choosePublicName("  Alex C.  ")
        val sent = rig.http.requests.single()
        assertEquals("POST", sent.method)
        assertEquals("/donor/me/sharing", sent.path)
        assertTrue(sent.body.contains("\"public_name\":\"Alex C.\""))
        assertTrue(sent.body.contains("\"replace_code\":false"))
    }

    @Test fun badNamesNeverReachTheNetwork() = runTest {
        val rig = Rig(backgroundScope) { 200 to sharingJson }
        assertEquals(AccountFailure.InvalidInput, failure { rig.wire.choosePublicName("   ") })
        assertEquals(AccountFailure.InvalidInput, failure { rig.wire.choosePublicName("x".repeat(81)) })
        assertTrue(rig.http.requests.isEmpty())
    }

    @Test fun nameStatusesMap() = runTest {
        assertEquals(AccountFailure.Forbidden, failure { Rig(backgroundScope) { 403 to "{}" }.wire.choosePublicName("Alex") })
        assertEquals(AccountFailure.InvalidInput, failure { Rig(backgroundScope) { 422 to "{}" }.wire.choosePublicName("Alex") })
        assertEquals(AccountFailure.RateLimited, failure { Rig(backgroundScope) { 429 to "" }.wire.choosePublicName("Alex") })
        assertEquals(AccountFailure.SignedOut, failure { Rig(backgroundScope) { 401 to "{}" }.wire.choosePublicName("Alex") })
    }

    // --- spend ---

    @Test fun spendIsTotalsByCategory() = runTest {
        val rig = Rig(backgroundScope) { 200 to """{"initiative_id":"$spendId","total_minor":500000,"categories":[{"category":"Food","amount_minor":300000},{"category":"Transport","amount_minor":200000}]}""" }
        val spend = rig.wire.initiativeSpend(spendId)
        assertEquals("$5,000", spend.totalLabel)
        assertEquals(listOf("Food", "Transport"), spend.categories.map { it.category })
        assertEquals("/initiatives/$spendId/spend", rig.http.requests.single().path)
    }

    @Test fun spendRefusesMalformedIdsAndMapsStatuses() = runTest {
        val rig = Rig(backgroundScope) { 200 to "{}" }
        assertEquals(AccountFailure.InvalidInput, failure { rig.wire.initiativeSpend("../donor/me") })
        assertTrue(rig.http.requests.isEmpty())
        assertEquals(AccountFailure.NotFound, failure { Rig(backgroundScope) { 404 to "{}" }.wire.initiativeSpend(spendId) })
        assertEquals(AccountFailure.Unavailable, failure { Rig(backgroundScope) { 503 to "{}" }.wire.initiativeSpend(spendId) })
        assertEquals(AccountFailure.RateLimited, failure { Rig(backgroundScope) { 429 to "" }.wire.initiativeSpend(spendId) })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to """{"initiative_id":"$spendId","total_minor":-5,"categories":[]}""" }.wire.initiativeSpend(spendId) })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(backgroundScope) { 200 to "" }.wire.initiativeSpend(spendId) })
    }
}
