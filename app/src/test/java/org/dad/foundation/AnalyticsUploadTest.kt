package org.dad.foundation

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AnalyticsUploadTest {
    private fun observed(method: String, path: String, request: String? = null, response: String? = null) =
        observedEvents(method, path, { request }, { response })

    @Test fun successfulCallsMapToTheEventsTheWebsiteCounts() {
        assertEquals(listOf(PlainEvent.SIGNUP_STARTED), observed("POST", "/donor/signup"))
        assertEquals(listOf(PlainEvent.SIGNUP_COMPLETED), observed("POST", "/donor/claim"))
        assertEquals(listOf(PlainEvent.GIFT_STARTED), observed("POST", "/donor/me/recurring/checkout"))
        assertEquals(listOf(PlainEvent.ACCOUNT_DELETED), observed("POST", "/donor/me/delete"))
        assertEquals(listOf(PlainEvent.GIFT_CANCELLED), observed("POST", "/donor/me/recurring/cancel"))
        assertEquals(listOf(PlainEvent.AMOUNT_CHANGED), observed("POST", "/donor/me/recurring/amount"))
        assertEquals(listOf(PlainEvent.TEAM_JOINED), observed("POST", "/donor/me/team"))
        assertEquals(listOf(PlainEvent.EVENT_CHECKED_IN), observed("POST", "/donor/me/events/check-in"))
        assertEquals(listOf(PlainEvent.EVENT_VIEWED), observed("GET", "/donor/me/events/e1"))
        assertEquals(listOf(PlainEvent.EVENT_VIEWED), observed("GET", "/events/e1"))
        assertEquals(listOf(PlainEvent.PARTNER_REPORT_EXPORTED), observed("GET", "/donor/partner/report.pdf"))
        assertEquals(listOf(PlainEvent.EVENT_RESERVED), observed("POST", "/donor/me/events/e1/reserve", response = """{"status":"RESERVED"}"""))
        assertEquals(listOf(PlainEvent.EVENT_WAITLISTED), observed("POST", "/donor/me/events/e1/reserve", response = """{"status":"WAITLISTED"}"""))
        assertEquals(emptyList<PlainEvent>(), observed("POST", "/donor/me/events/e1/reserve", response = "not json"))
        assertEquals(listOf(PlainEvent.RANKINGS_TOGGLED, PlainEvent.VOLUNTEER_OPTED_IN),
            observed("POST", "/donor/me/preferences", request = """{"rankings_opted_out":true,"volunteer":true}"""))
        assertEquals(listOf(PlainEvent.RANKINGS_TOGGLED), observed("POST", "/donor/me/preferences", request = """{"rankings_opted_out":false,"volunteer":false}"""))
        listOf("GET" to "/donor/me/events", "GET" to "/donor/me/events/e1/calendar.ics", "POST" to "/donor/me/profile", "POST" to "/analytics/events")
            .forEach { (method, path) -> assertEquals(path, emptyList<PlainEvent>(), observed(method, path)) }
    }

    @Test fun theObserverSeesOnlySuccessfulCalls() {
        val recorded = RecordingAnalyticsEmitter()
        val http = ScriptedHttp { if (it.path == "/donor/signup") 200 to "{}" else 422 to "{}" }
        val client = OkHttpClient.Builder().addInterceptor(AnalyticsObserver(recorded)).addInterceptor(http).build()
        val json = "{}".toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url("$TEST_ORIGIN/donor/signup").post(json).build()).execute().close()
        client.newCall(Request.Builder().url("$TEST_ORIGIN/donor/claim").post(json).build()).execute().close()
        assertEquals(listOf<AnalyticsEvent>(AnalyticsEvent.Plain(PlainEvent.SIGNUP_STARTED)), recorded.events)
    }

    private class Sink(var failure: Exception? = null) : AnalyticsSink {
        val batches = mutableListOf<List<AnalyticsRecord>>()
        override suspend fun send(records: List<AnalyticsRecord>) {
            failure?.let { throw it }
            batches += records
        }
    }

    private fun uploader(scope: TestScope, consent: AnalyticsConsent, sink: Sink) =
        AnalyticsUploader(consent, sink, scope, now = { Instant.parse("2026-11-01T10:00:00Z") })

    @Test fun nothingIsRecordedUntilTheDonorAllowsIt() = runTest(StandardTestDispatcher()) {
        val sink = Sink()
        val consent = MemoryAnalyticsConsent()
        val analytics = uploader(this, consent, sink)
        analytics.emit(AnalyticsEvent.Plain(PlainEvent.ROLE_SELECTED))
        advanceUntilIdle()
        assertTrue(sink.batches.isEmpty())
        assertNull(analytics.choice)
        analytics.choose(true)
        analytics.emit(AnalyticsEvent.InviteShared("copy"))
        advanceUntilIdle()
        assertEquals(true, consent.choice)
        val sent = sink.batches.flatten()
        assertEquals(listOf("app_opened", "invite_shared"), sent.map { it.name })
        assertEquals(mapOf("channel" to "copy"), sent[1].properties)
        assertEquals(1, sent.map { it.journeyId }.distinct().size)
        assertEquals(2, sent.map { it.id }.distinct().size)
    }

    @Test fun aFailedUploadIsRetriedWithTheSameIdsAndDecliningDropsIt() = runTest(StandardTestDispatcher()) {
        val sink = Sink(failure = AccountFailure.Unavailable)
        val analytics = uploader(this, MemoryAnalyticsConsent(true), sink)
        analytics.emit(AnalyticsEvent.Plain(PlainEvent.APP_OPENED))
        advanceUntilIdle()
        assertEquals(1, analytics.pending)
        sink.failure = null
        analytics.emit(AnalyticsEvent.IrlCodeShown)
        advanceUntilIdle()
        assertEquals(listOf("app_opened", "irl_code_shown"), sink.batches.single().map { it.name })
        assertEquals(0, analytics.pending)

        sink.failure = AccountFailure.Unavailable
        analytics.emit(AnalyticsEvent.IrlCodeShown)
        advanceUntilIdle()
        analytics.choose(false)
        assertEquals(0, analytics.pending)
        analytics.emit(AnalyticsEvent.IrlCodeShown)
        assertEquals(0, analytics.pending)
    }

    @Test fun aRejectedBatchIsDroppedNotRetried() = runTest(StandardTestDispatcher()) {
        val sink = Sink(failure = AccountFailure.InvalidInput)
        val analytics = uploader(this, MemoryAnalyticsConsent(true), sink)
        analytics.emit(AnalyticsEvent.Plain(PlainEvent.APP_OPENED))
        advanceUntilIdle()
        assertEquals(0, analytics.pending)
    }

    @Test fun aBufferLargerThanABatchIsSentInBatches() = runTest(StandardTestDispatcher()) {
        val sink = Sink(failure = AccountFailure.Unavailable)
        val analytics = uploader(this, MemoryAnalyticsConsent(true), sink)
        repeat(150) { analytics.emit(AnalyticsEvent.IrlCodeShown) }
        advanceUntilIdle()
        sink.failure = null
        analytics.flush()
        assertEquals(listOf(100, 50), sink.batches.map { it.size })
    }

    @Test fun theWireBatchIsTheIngestShapeAndAttributedWhenSignedIn() = runTest {
        val http = ScriptedHttp { 200 to """{"accepted":1}""" }
        val record = AnalyticsRecord("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", "invite_shared",
            Instant.parse("2026-11-01T10:00:00Z"), mapOf("channel" to "copy"))
        WireAnalytics(TEST_ORIGIN, access = { "access-1" }, http.client()).send(listOf(record))
        WireAnalytics(TEST_ORIGIN, access = { throw AccountFailure.SignedOut }, http.client()).send(listOf(record.copy(properties = emptyMap())))
        val (signedIn, anonymous) = http.requests
        assertEquals("/analytics/events", signedIn.path)
        assertEquals("""{"events":[{"id":"11111111-1111-4111-8111-111111111111","journey_id":"22222222-2222-4222-8222-222222222222",""" +
            """"name":"invite_shared","occurred_at":"2026-11-01T10:00:00Z","properties":{"channel":"copy"}}]}""", signedIn.body)
        assertEquals("Bearer access-1", signedIn.headers["authorization"])
        assertEquals("ANDROID", signedIn.headers["x-dad-platform"])
        assertEquals("native", signedIn.headers["x-dad-client"])
        assertNull(anonymous.headers["authorization"])
        assertTrue(!anonymous.body.contains("properties"))
    }

    @Test fun aRejectedWireBatchIsInvalidInput() = runTest {
        val wire = WireAnalytics(TEST_ORIGIN, access = { null }, ScriptedHttp { 422 to "{}" }.client())
        val record = AnalyticsRecord("i", "j", "app_opened", Instant.EPOCH, emptyMap())
        assertEquals(AccountFailure.InvalidInput, runCatching { wire.send(listOf(record)) }.exceptionOrNull())
    }

    @Test fun pushOpenedAndPlainEventsCarryTheAllowedPayloads() {
        assertEquals("push_opened", AnalyticsEvent.PushOpened("STREAK_AT_RISK").name)
        assertEquals(mapOf("type" to "STREAK_AT_RISK"), AnalyticsEvent.PushOpened("STREAK_AT_RISK").properties)
        PlainEvent.entries.forEach { assertTrue(AnalyticsEvent.Plain(it).properties.isEmpty()) }
    }
}

class PushTest {
    @Test fun theDonorRegistersAnAndroidTokenWithNoEnvironment() = runTest {
        val http = ScriptedHttp {
            when (it.path) {
                "/donor/me/push-device", "/donor/me/push-device/unregister" -> 200 to """{"registered":true,"platform":"ANDROID","environment":null}"""
                else -> 404 to "{}"
            }
        }
        val session = AccountSession(WireAPI(TEST_ORIGIN, http.client()), InMemoryCredentialStore(Credentials("access-0", "refresh-0", Long.MAX_VALUE)), backgroundScope)
        val push = WireDonorPush(ProductClient(TEST_ORIGIN, session, http.client()))
        push.register("fcm-token-0123456789-0123456789-0123")
        push.unregister()
        assertEquals("""{"platform":"ANDROID","token":"fcm-token-0123456789-0123456789-0123"}""", http.requests[0].body)
        assertEquals("Bearer access-0", http.requests[0].headers["authorization"])
        assertEquals("/donor/me/push-device/unregister", http.requests[1].path)
    }

    @Test fun onlyAWellFormedCategoryIsATap() {
        assertEquals(PushTap("STREAK_AT_RISK", "/account/dashboard"), PushTap.from("STREAK_AT_RISK", "/account/dashboard"))
        assertEquals(PushTap("REFERRAL_CONVERTED", null), PushTap.from("REFERRAL_CONVERTED", null))
        assertNull(PushTap.from(null, "/account/gifts"))
        assertNull(PushTap.from("streak; drop", "/account/gifts"))
        assertNull(PushTap.from("STREAK_AT_RISK", "/" + "a".repeat(300))?.path)
    }
}
