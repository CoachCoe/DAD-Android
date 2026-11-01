package org.dad.foundation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkAttributionTest {
    private val kept = "k".repeat(43)
    private val fresh = "f".repeat(43)

    private class Rig(scope: kotlinx.coroutines.CoroutineScope, tokens: LinkTokens = LinkTokens(), handler: (Recorded) -> Pair<Int, String>) {
        val http = ScriptedHttp(handler)
        val store = MemoryLinkTokenStore(tokens)
        val analytics = RecordingAnalyticsEmitter()
        private val client = http.client()
        private val session = AccountSession(WireAPI(TEST_ORIGIN, client), RecordingStore(validCredentials()), scope)
        val links = LinkAttribution(WireLinkAttribution(TEST_ORIGIN, ProductClient(TEST_ORIGIN, session, client), client), store, analytics)
    }

    @Test fun referralCaptureSendsTheKeptTokenAnonymouslyAndKeepsTheNewOne() = runTest {
        val rig = Rig(backgroundScope, LinkTokens(referral = kept)) { 200 to """{"captured":true,"public_name":"Alex R","token":"$fresh"}""" }
        assertEquals("Alex R", rig.links.captureReferral("ALEX42", inPerson = true))
        assertEquals(fresh, rig.store.load().referral)
        assertEquals(fresh, rig.links.referralToken)
        assertEquals(listOf(AnalyticsEvent.AttributionCaptured(AttributionSource.IRL)), rig.analytics.events)
        val call = rig.http.requests.single()
        assertEquals("/donor/referrals/ALEX42", call.path)
        assertNull("a link visit is anonymous", call.headers["authorization"])
        assertEquals("native", call.headers["x-dad-client"])
        assertEquals("""{"kind":"IRL","token":"$kept"}""", call.body)
    }

    @Test fun aCaptureThatKeepsNothingDiscardsTheTokenAndAFailureKeepsIt() = runTest {
        val cleared = Rig(backgroundScope, LinkTokens(referral = kept, company = kept)) { 200 to """{"captured":false,"public_name":null,"token":null}""" }
        cleared.links.captureReferral("gone", inPerson = false)
        assertEquals(LinkTokens(referral = null, company = kept), cleared.store.load())
        assertEquals(emptyList<AnalyticsEvent>(), cleared.analytics.events)

        val down = Rig(backgroundScope, LinkTokens(referral = kept, company = kept)) { 503 to "{}" }
        down.links.captureReferral("ALEX42", inPerson = false)
        down.links.captureCompany("ACME42")
        assertEquals(LinkTokens(referral = kept, company = kept), down.store.load())
    }

    @Test fun companyTokenIsCapturedThenBoundOnceSignedIn() = runTest {
        val rig = Rig(backgroundScope) {
            if (it.path == "/donor/me/company-touch") 200 to """{"bound":true}""" else 200 to """{"captured":true,"token":"$fresh"}"""
        }
        rig.links.captureCompany("ACME42")
        assertEquals(fresh, rig.store.load().company)
        assertEquals(listOf(AnalyticsEvent.AttributionCaptured(AttributionSource.COMPANY)), rig.analytics.events)
        assertEquals("{}", rig.http.requests[0].body)
        rig.links.bindCompany()
        val bind = rig.http.requests[1]
        assertEquals("/donor/me/company-touch", bind.path)
        assertEquals("Bearer access-0", bind.headers["authorization"])
        assertEquals("""{"token":"$fresh"}""", bind.body)
        assertNull("a bound token is spent", rig.store.load().company)
        rig.links.bindCompany()
        assertEquals("nothing left to bind", 2, rig.http.requests.size)
    }

    @Test fun aTransientBindFailureKeepsTheCompanyTokenAndARefusalSpendsIt() = runTest {
        val down = Rig(backgroundScope, LinkTokens(company = kept)) { 503 to "{}" }
        down.links.bindCompany()
        assertEquals(kept, down.store.load().company)
        val refused = Rig(backgroundScope, LinkTokens(company = kept)) { 422 to "{}" }
        refused.links.bindCompany()
        assertNull("a token the server refuses is not retried forever", refused.store.load().company)
    }
}
