package org.dad.foundation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real conformer + real AccountSession + real Retrofit stack over a scripted transport. */
class WireDashboardTest {
    private class Rig(
        creds: Credentials? = validCredentials(),
        scope: kotlinx.coroutines.CoroutineScope,
        handler: (Recorded) -> Pair<Int, String>,
    ) {
        val http = ScriptedHttp(handler)
        val store = RecordingStore(creds)
        val client = http.client()
        val session = AccountSession(WireAPI(TEST_ORIGIN, client), store, scope)
        val wire = WireDashboard(ProductClient(TEST_ORIGIN, session, client))
    }

    private suspend fun failure(block: suspend () -> Any?): Throwable? = runCatching { block() }.exceptionOrNull()

    private val giftId = "00000000-0000-4000-8000-000000000007"

    private val bar = (1..30).joinToString(",") { if (it <= 18) "true" else "false" }
    private fun ready(
        streak: String = """{"at_risk":false,"current":18,"grace_ends_at":null,"ladder":{"days":18,"last_milestone":7,"next_milestone":30,"days_to_next":12,"bar":[$bar]}}""",
        rank: String = """{"national_percentile":2,"city":"Austin","city_rank":4,"computed_at":"2026-10-30T00:00:00Z"}""",
        optedOut: Boolean = false,
        network: String = """{"active_count":3,"annualized_minor":474500,"currency":"USD"}""",
        referrals: String = """{"currency":"USD","irl":1,"irl_minor":3000,"link":2,"link_minor":9000,"total_minor":12000}""",
        given: String = "124000",
    ) = """{"state":"ready","mode":"test","computed_at":"2026-10-30T12:00:00Z","founding_donor":true,"referrals":$referrals,"streak":$streak,"rank":$rank,"rankings_opted_out":$optedOut,"network":$network,"impact":{"given_minor":$given,"currency":"USD","initiatives_funded":4,"events_volunteered":2}}"""

    private fun gift(id: String = giftId, refunded: Long = 500, refunds: String? = null) =
        """{"id":"$id","amount_minor":3000,"currency":"USD","paid_at":"2026-09-14T10:00:00Z","frequency":"MONTHLY","refunded_minor":$refunded,"net_minor":${3000 - refunded}${refunds?.let { ""","refunds":$it""" }.orEmpty()}}"""

    private fun page(ids: List<String>, next: String) =
        """{"email":"alex@example.org","mode":"test","gifts":[${ids.joinToString(",") { gift(it) }}],"next_offset":$next}"""

    private val today = """{"initiative_id":"11111111-1111-4111-8111-111111111111","slug":"meals","title":"Meals on Wheels","summary":"Hot meals.","region":"TX","location":"Austin, TX","type":"PROGRAM","started_on":"2025-01-02","metrics":[{"label":"Meals","value":1200,"unit":"PEOPLE","verified_on":"2026-10-28"},{"label":"Per meal","value":425,"unit":"USD_CENTS","verified_on":"2026-10-28"},{"label":"Odd","value":3,"unit":"FUTURE_UNIT","verified_on":"2026-10-28"}],"distinct_shown":4}"""

    // --- dashboard ---

    @Test fun readyDashboardMapsEveryCardAndSendsTheNativeBearer() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to ready() }
        val state = rig.wire.dashboard() as DashboardState.Ready
        val d = state.dashboard
        assertTrue(d.foundingDonor)
        assertEquals("18-day streak", d.streak?.headline)
        assertEquals("12 days to your 30-day milestone", d.streak?.ladder?.nextLabel)
        assertEquals(18, d.streak?.ladder?.bar?.count { it })
        assertEquals(30, d.streak?.ladder?.bar?.size)
        assertFalse(d.streak!!.atRisk)
        assertNull(d.streak.graceEndsAt)
        val rank = (d.rank as RankLine.Ranked).standing
        // national_percentile is already the top bucket: 2 is "Top 2%", never "Top 98%".
        assertEquals("Top 2% nationally", rank.nationalLabel)
        assertEquals("#4 in Austin", rank.cityLabel)
        assertEquals("$1,240", d.impact.givenLabel)
        assertEquals(4, d.impact.initiativesFunded)
        assertEquals(2, d.impact.eventsVolunteered)
        assertEquals("$4,745", d.network?.annualizedLabel)
        assertEquals(3L, d.network?.activeCount)
        assertEquals(3L, d.referrals?.joined)
        assertEquals("$120", d.referrals?.totalLabel)
        val sent = rig.http.requests.single()
        assertEquals("GET", sent.method)
        assertEquals("/donor/me/dashboard", sent.path)
        assertEquals("Bearer access-0", sent.headers["authorization"])
        assertEquals("native", sent.headers["x-dad-client"])
        assertNull(sent.headers["origin"])
        assertNull(sent.headers["cookie"])
    }

    @Test fun preparingIsANormalAnswerNotAnError() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to """{"state":"preparing","mode":"test"}""" }
        assertEquals(DashboardState.Preparing, rig.wire.dashboard())
    }

    @Test fun anAtRiskStreakCarriesItsGraceDeadline() = runTest {
        val streak = """{"at_risk":true,"current":5,"grace_ends_at":"2026-11-03T12:00:00Z","ladder":null}"""
        val d = (Rig(scope = backgroundScope) { 200 to ready(streak = streak) }.wire.dashboard() as DashboardState.Ready).dashboard
        assertTrue(d.streak!!.atRisk)
        assertEquals(2026, d.streak.graceEndsAt?.year)
        assertNull(d.streak.ladder)
    }

    @Test fun anOptedOutDonorHasNoRankEvenIfTheServerSentOneAndUnknownFiguresAreNotZero() = runTest {
        val body = ready(streak = "null", optedOut = true, network = "null", referrals = "null", given = "null")
        val d = (Rig(scope = backgroundScope) { 200 to body }.wire.dashboard() as DashboardState.Ready).dashboard
        assertEquals(RankLine.OptedOut, d.rank)
        assertNull(d.streak)
        assertNull(d.network)
        assertNull(d.referrals)
        assertNull(d.impact.givenMinor)
        assertEquals("--", d.impact.givenLabel)
    }

    @Test fun optedInButNotRankedYetIsDistinctFromOptedOut() = runTest {
        val d = (Rig(scope = backgroundScope) { 200 to ready(rank = "null", optedOut = false) }.wire.dashboard() as DashboardState.Ready).dashboard
        assertEquals(RankLine.NotRankedYet, d.rank)
    }

    @Test fun aRankWithoutACityHasNoCityLabel() = runTest {
        val rank = """{"national_percentile":1,"city":null,"city_rank":null,"computed_at":"2026-10-30T00:00:00Z"}"""
        val d = (Rig(scope = backgroundScope) { 200 to ready(rank = rank) }.wire.dashboard() as DashboardState.Ready).dashboard
        val standing = (d.rank as RankLine.Ranked).standing
        assertEquals("Top 1% nationally", standing.nationalLabel)
        assertNull(standing.cityLabel)
    }

    @Test fun anUnknownDashboardStateIsAnInvalidResponse() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to """{"state":"someday","mode":"test"}""" }
        assertEquals(AccountFailure.InvalidResponse, failure { rig.wire.dashboard() })
    }

    // --- today's initiative ---

    @Test fun todayInitiativeMapsMetricsAndKeepsAnUnknownUnitsNumber() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to today }
        val initiative = rig.wire.todayInitiative()!!
        assertEquals("Meals on Wheels", initiative.title)
        assertEquals("PROGRAM", initiative.kind)
        assertEquals("2025-01-02", initiative.startedOn)
        assertEquals(listOf("1,200 people", "$4.25", "3"), initiative.headlineMetrics.map { it.valueLabel })
        assertEquals("/donor/me/today-initiative", rig.http.requests.single().path)
        assertTrue(HomeInitiative.ATTRIBUTION_NOTE.contains("display attribution, not designated giving"))
    }

    @Test fun nothingInRotationIsNullNotAnError() = runTest {
        val rig = Rig(scope = backgroundScope) { 404 to """{"detail":"nothing in rotation"}""" }
        assertNull(rig.wire.todayInitiative())
        assertNotNull(rig.store.value)
    }

    // --- gifts ---

    @Test fun giftsPageMapsRowsAndTheNextOffset() = runTest {
        val ids = (0 until 50).map { "00000000-0000-4000-8000-%012d".format(it) }
        val rig = Rig(scope = backgroundScope) { 200 to page(ids, "50") }
        val first = rig.wire.gifts(0)
        assertEquals(50, first.gifts.size)
        assertEquals(50, first.nextOffset)
        assertEquals("Monthly", first.gifts[0].frequencyLabel)
        assertEquals("$25", first.gifts[0].netLabel)
        assertEquals("$5 refunded", first.gifts[0].refundLabel)
        val sent = rig.http.requests.single()
        assertEquals("/donor/me/gifts", sent.path)
        assertEquals("offset=0", sent.query)
        rig.wire.gifts(50)
        assertEquals("offset=50", rig.http.requests.last().query)
    }

    @Test fun theLastPageHasNoNextOffsetAndNullIsNotZero() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to page(listOf(giftId), "null") }
        assertNull(rig.wire.gifts(100).nextOffset)
    }

    @Test fun theOffsetIsSentAsAQueryParameter() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to page(listOf(giftId), "null") }
        rig.wire.gifts(150)
        assertEquals("offset=150", rig.http.requests.single().query)
    }

    @Test fun aNegativeOffsetIsRefusedWithoutARequest() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to page(emptyList(), "null") }
        assertEquals(AccountFailure.InvalidInput, failure { rig.wire.gifts(-1) })
        assertTrue(rig.http.requests.isEmpty())
    }

    @Test fun anOversizedPageIsTruncatedNotTrusted() = runTest {
        val ids = (0 until 130).map { "00000000-0000-4000-8000-%012d".format(it) }
        val rig = Rig(scope = backgroundScope) { 200 to page(ids, "130") }
        assertEquals(100, rig.wire.gifts(0).gifts.size)
    }

    @Test fun anEmptyHistoryIsAnEmptyPage() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to page(emptyList(), "null") }
        val result = rig.wire.gifts(0)
        assertTrue(result.gifts.isEmpty())
        assertNull(result.nextOffset)
    }

    // --- receipt ---

    @Test fun receiptMapsTheGiftAndItsRefunds() = runTest {
        val refunds = """[{"id":"re_1","amount_minor":500,"status":"succeeded","observed_at":"2026-09-29T10:00:00Z"}]"""
        val body = """{"mode":"test","tax_receipt":false,"as_of":"2026-10-30T00:00:00Z","receipt_id":"rcpt-7","gift":${gift(refunds = refunds)}}"""
        val rig = Rig(scope = backgroundScope) { 200 to body }
        val receipt = rig.wire.receipt(giftId)
        assertEquals("$30", receipt.gift.amountLabel)
        assertEquals(1, receipt.refunds.size)
        assertEquals(500L, receipt.refunds[0].amountMinor)
        assertEquals("succeeded", receipt.refunds[0].status)
        assertFalse(receipt.taxReceipt)
        assertEquals("/donor/me/receipts/$giftId", rig.http.requests.single().path)
    }

    @Test fun aMalformedGiftIdIsRefusedBeforeItBecomesAPathSegment() = runTest {
        val rig = Rig(scope = backgroundScope) { 200 to "{}" }
        for (bad in listOf("", "not-a-uuid", "../me/profile", "00000000-0000-4000-8000-0000000000071", " $giftId")) {
            assertEquals(AccountFailure.InvalidInput, failure { rig.wire.receipt(bad) })
        }
        assertTrue(rig.http.requests.isEmpty())
    }

    @Test fun anUnknownReceiptIsInvalidInputAndKeepsTheSession() = runTest {
        val rig = Rig(scope = backgroundScope) { 404 to """{"detail":"not found"}""" }
        assertEquals(AccountFailure.InvalidInput, failure { rig.wire.receipt(giftId) })
        assertNotNull(rig.store.value)
    }

    // --- annual summary ---

    @Test fun annualSummaryMapsTotals() = runTest {
        val body = """{"mode":"test","tax_receipt":false,"year":2026,"timezone":"America/Chicago","as_of":"2026-10-30T00:00:00Z","gifts":[${gift()},${gift("00000000-0000-4000-8000-000000000008")}],"amount_minor":360000,"refunded_minor":8500,"net_minor":351500,"currency":"USD"}"""
        val rig = Rig(scope = backgroundScope) { 200 to body }
        val summary = rig.wire.annualSummary(2026)
        assertEquals(2026, summary.year)
        assertEquals(2, summary.giftCount)
        assertEquals("$3,515", summary.netLabel)
        assertEquals("/donor/me/annual-summary/2026", rig.http.requests.single().path)
    }

    @Test fun anOutOfRangeYearIsRefusedAndNoGiftsIs404InvalidInput() = runTest {
        val rig = Rig(scope = backgroundScope) { 404 to "{}" }
        assertEquals(AccountFailure.InvalidInput, failure { rig.wire.annualSummary(1999) })
        assertEquals(AccountFailure.InvalidInput, failure { rig.wire.annualSummary(10000) })
        assertTrue(rig.http.requests.isEmpty())
        assertEquals(AccountFailure.InvalidInput, failure { rig.wire.annualSummary(2026) })
    }

    // --- status mapping ---

    @Test fun statusCodesMapToTheFailuresTheAppReasonsAbout() = runTest {
        val reads: List<suspend (WireDashboard) -> Any?> = listOf(
            { it.dashboard() }, { it.todayInitiative() }, { it.gifts(0) }, { it.receipt(giftId) }, { it.annualSummary(2026) },
        )
        for (read in reads) {
            for ((status, expected) in listOf(
                403 to AccountFailure.Unavailable, 409 to AccountFailure.Unavailable, 422 to AccountFailure.InvalidInput,
                429 to AccountFailure.RateLimited, 500 to AccountFailure.Unavailable, 503 to AccountFailure.Unavailable,
            )) {
                val rig = Rig(scope = backgroundScope) { status to """{"detail":"x"}""" }
                assertEquals("status $status", expected, failure { read(rig.wire) })
                assertNotNull("status $status must not end the session", rig.store.value)
            }
        }
        // 404 outside today's initiative: a missing dashboard is unavailable.
        assertEquals(AccountFailure.Unavailable, failure { Rig(scope = backgroundScope) { 404 to "{}" }.wire.dashboard() })
        assertEquals(AccountFailure.Unavailable, failure { Rig(scope = backgroundScope) { 404 to "{}" }.wire.gifts(0) })
    }

    // --- 401: refresh once, then sign out ---

    @Test fun anUnauthorizedReadRefreshesOnceAndRetriesWithTheNewBearer() = runTest {
        var calls = 0
        val rig = Rig(scope = backgroundScope) { r ->
            when (r.path) {
                "/donor/refresh" -> 200 to nativeSessionJson("access-new", "refresh-new")
                else -> if (++calls == 1) 401 to "{}" else 200 to ready()
            }
        }
        assertTrue(rig.wire.dashboard() is DashboardState.Ready)
        assertEquals(1, rig.http.count("/donor/refresh"))
        assertEquals(listOf("Bearer access-0", "Bearer access-new"), rig.http.requests.filter { it.path == "/donor/me/dashboard" }.map { it.headers["authorization"] })
        assertEquals("access-new", rig.store.value?.access)
    }

    @Test fun aSecondUnauthorizedEndsTheSession() = runTest {
        val rig = Rig(scope = backgroundScope) { r -> if (r.path == "/donor/refresh") 200 to nativeSessionJson("access-new") else 401 to "{}" }
        assertEquals(AccountFailure.SignedOut, failure { rig.wire.gifts(0) })
        assertNull(rig.store.value)
        assertFalse(rig.session.isSignedIn())
        assertEquals(1, rig.http.count("/donor/refresh"))
    }

    @Test fun concurrentReadsAcrossExpiryShareOneRefresh() = runTest {
        val rig = Rig(creds = Credentials("old", "r", 0), scope = backgroundScope) { r ->
            when (r.path) {
                "/donor/refresh" -> 200 to nativeSessionJson("fresh")
                "/donor/me/dashboard" -> 200 to ready()
                "/donor/me/today-initiative" -> 200 to today
                else -> 200 to page(listOf(giftId), "null")
            }
        }
        val calls = List(4) { async { rig.wire.dashboard() } } + List(4) { async { rig.wire.todayInitiative() } } + List(4) { async { rig.wire.gifts(0) } }
        calls.forEach { it.await() }
        assertEquals(1, rig.http.count("/donor/refresh"))
        assertTrue(rig.http.requests.filter { it.path != "/donor/refresh" }.all { it.headers["authorization"] == "Bearer fresh" })
    }

    // --- bad bodies ---

    @Test fun malformedBodiesAreInvalidResponsesNotCrashes() = runTest {
        for (body in listOf("{not json", "[]", "\"text\"", "null", "", """{"state":"ready"}""")) {
            val rig = Rig(scope = backgroundScope) { 200 to body }
            assertEquals("body <$body>", AccountFailure.InvalidResponse, failure { rig.wire.dashboard() })
        }
        for (body in listOf("{not json", "[]", "{}", """{"gifts":"nope","next_offset":null}""", """{"gifts":[{"id":1}],"next_offset":null}""")) {
            val rig = Rig(scope = backgroundScope) { 200 to body }
            assertEquals("body <$body>", AccountFailure.InvalidResponse, failure { rig.wire.gifts(0) })
        }
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(scope = backgroundScope) { 200 to "{}" }.wire.todayInitiative() })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(scope = backgroundScope) { 200 to "{oops" }.wire.receipt(giftId) })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(scope = backgroundScope) { 200 to "{oops" }.wire.annualSummary(2026) })
    }

    @Test fun wrongTypesAndFractionalMoneyAreInvalid() = runTest {
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(scope = backgroundScope) { 200 to ready(streak = """{"at_risk":false,"current":"18","grace_ends_at":null,"ladder":null}""") }.wire.dashboard() })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(scope = backgroundScope) { 200 to ready(given = "12.5") }.wire.dashboard() })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(scope = backgroundScope) { 200 to ready(rank = """{"national_percentile":99999999999,"city":null,"city_rank":null,"computed_at":"2026-10-30T00:00:00Z"}""") }.wire.dashboard() })
        assertEquals(AccountFailure.InvalidResponse, failure { Rig(scope = backgroundScope) { 200 to page(listOf(giftId), "null").replace("2026-09-14T10:00:00Z", "yesterday") }.wire.gifts(0) })
    }

    @Test fun anOversizedBodyIsRejected() = runTest {
        val huge = """{"state":"preparing","mode":"test","pad":"${"x".repeat(1_100_000)}"}"""
        val rig = Rig(scope = backgroundScope) { 200 to huge }
        assertEquals(AccountFailure.InvalidResponse, failure { rig.wire.dashboard() })
    }

    @Test fun errorBodiesAreNeverSurfaced() = runTest {
        val rig = Rig(scope = backgroundScope) { 500 to """{"detail":"alex@example.org exploded at 78701"}""" }
        val error = failure { rig.wire.dashboard() }
        assertEquals(AccountFailure.Unavailable, error)
        assertFalse(error.toString().contains("alex"))
    }

    // --- cancellation ---

    @Test fun cancellingARequestInFlightRethrowsCancellationAndKeepsTheSession() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rig = Rig(scope = backgroundScope) { _ ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            200 to ready()
        }
        val thrown = AtomicReference<Throwable?>()
        val read = async(Dispatchers.Default) {
            try { rig.wire.dashboard() } catch (error: Throwable) { thrown.set(error); throw error }
        }
        withContext(Dispatchers.IO) { assertTrue(started.await(10, TimeUnit.SECONDS)) }
        read.cancel()
        release.countDown()
        withContext(Dispatchers.IO) { read.join() }
        assertTrue(read.isCancelled)
        assertTrue("expected CancellationException, got ${thrown.get()}", thrown.get() is CancellationException)
        assertNotNull(rig.store.value)
        assertTrue(rig.session.isSignedIn())
    }
}
