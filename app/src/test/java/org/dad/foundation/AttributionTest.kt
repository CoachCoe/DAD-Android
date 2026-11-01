package org.dad.foundation

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributionTest {
    // --- sanitization ---
    @Test fun sanitizeRejectsNonCodeInput() {
        assertNull(Attribution.sanitized(null))
        assertNull(Attribution.sanitized(" "))
        assertNull(Attribution.sanitized("has space"))
        assertNull(Attribution.sanitized("drop;table"))
        assertNull(Attribution.sanitized("x".repeat(65)))
        assertEquals("ABC_123-xyz", Attribution.sanitized("  ABC_123-xyz  "))
    }

    // --- last-touch-wins precedence (PRD R-3) ---
    @Test fun lastTouchWinsOverwritesStoredField() {
        val store = MemoryAttributionStore(Attribution.of(inviteCode = "FIRST"))
        val capture = AttributionCapture(store)
        val merged = capture.capture(Attribution.of(inviteCode = "SECOND"))
        assertEquals("SECOND", merged.inviteCode) // last touch wins, unlike iOS first-writer
    }

    @Test fun absentIncomingFieldPreservesStoredValue() {
        val store = MemoryAttributionStore(Attribution.of(inviteCode = "INV"))
        val capture = AttributionCapture(store)
        val merged = capture.capture(Attribution.of(companyCode = "ACME"))
        assertEquals("INV", merged.inviteCode)   // untouched field preserved
        assertEquals("ACME", merged.companyCode) // new field filled
    }

    @Test fun emptyCapturePersistsNothingAndEmitsNoEvent() {
        val store = MemoryAttributionStore()
        val analytics = RecordingAnalyticsEmitter()
        val merged = AttributionCapture(store, analytics).capture(Attribution())
        assertTrue(merged.isEmpty)
        assertNull(store.load())
        assertTrue(analytics.events.isEmpty())
    }

    @Test fun captureEmitsAttributionCapturedOnChange() {
        val store = MemoryAttributionStore()
        val analytics = RecordingAnalyticsEmitter()
        AttributionCapture(store, analytics).capture(Attribution.of(inviteCode = "INV", source = "qr"))
        assertEquals(1, analytics.events.size)
        val event = analytics.events.first() as AnalyticsEvent.AttributionCaptured
        assertEquals("attribution_captured", event.name)
        assertEquals(mapOf("source" to "link"), event.properties)
    }

    @Test fun reCaptureOfIdenticalPayloadDoesNotReEmit() {
        val store = MemoryAttributionStore()
        val analytics = RecordingAnalyticsEmitter()
        val capture = AttributionCapture(store, analytics)
        capture.capture(Attribution.of(inviteCode = "INV"))
        capture.capture(Attribution.of(inviteCode = "INV"))
        assertEquals(1, analytics.events.size) // no duplicate event for an unchanged record
    }

    @Test fun consumeClearsStore() {
        val store = MemoryAttributionStore(Attribution.of(inviteCode = "INV"))
        val capture = AttributionCapture(store)
        capture.consume()
        assertNull(capture.current())
    }

    // --- Play Install Referrer parsing (D-3) ---
    @Test fun installReferrerParsesAndSanitizes() {
        val a = InstallReferrerParser.parse("invite=FRIEND123&utm_source=instagram&company=ACME")
        assertEquals("FRIEND123", a.inviteCode)
        assertEquals("instagram", a.source)
        assertEquals("ACME", a.companyCode)
    }

    @Test fun installReferrerRefAliasAndUrlEncoding() {
        val a = InstallReferrerParser.parse("ref=CODE_9&source=print")
        assertEquals("CODE_9", a.inviteCode)
        assertEquals("print", a.source)
    }

    @Test fun installReferrerEmptyOrGarbageYieldsEmpty() {
        assertTrue(InstallReferrerParser.parse(null).isEmpty)
        assertTrue(InstallReferrerParser.parse("").isEmpty)
        assertTrue(InstallReferrerParser.parse("not a referrer string").isEmpty)
    }

    @Test fun installReferrerReplayFlowsIntoCapture() {
        // Simulated deferred-attribution replay: Play referrer -> parse -> capture -> persisted.
        val store = MemoryAttributionStore()
        val capture = AttributionCapture(store)
        capture.capture(InstallReferrerParser.parse("invite=VIRAL&utm_source=wom"))
        assertEquals("VIRAL", capture.current()?.inviteCode)
        assertEquals("wom", capture.current()?.source)
    }

    // --- invite link + QR round-trip (R-1/R-2) ---
    @Test fun inviteLinkBuildsAndRejectsBadInput() {
        assertEquals("https://dad.org/r/ABC123", Referral.inviteLink("https://dad.org/", "ABC123"))
        assertEquals("https://dad.org/r/ABC123?via=irl", Referral.inviteLink("https://dad.org", "ABC123", inPerson = true))
        assertNull(Referral.inviteLink("http://dad.org", "ABC123")) // non-https rejected
        assertNull(Referral.inviteLink("https://dad.org", "bad code")) // unsanitary code rejected
    }

    @Test fun qrMatrixRoundTripsThroughDecoder() {
        val link = "https://dad.org/r/ROUNDTRIP9"
        val matrix = Referral.qrMatrix(link, size = 256)
        // Render the boolean matrix into an ARGB pixel buffer and decode it back.
        val h = matrix.size; val w = matrix[0].size
        val pixels = IntArray(w * h) { i -> if (matrix[i / w][i % w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val source = RGBLuminanceSource(w, h, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val decoded = QRCodeReader().decode(bitmap)
        assertEquals(link, decoded.text)
    }

    @Test fun analyticsEventsCarryExpectedNames() {
        assertEquals("invite_shared", AnalyticsEvent.InviteShared("sms").name)
        assertEquals("irl_code_shown", AnalyticsEvent.IrlCodeShown.name)
        assertEquals("referral_converted", AnalyticsEvent.ReferralConverted.name)
    }

    /** Pins each event's payload to DAD-Main's ingest allow-list (analytics.go eventProperties). */
    @Test fun analyticsPayloadsMatchTheIngestAllowList() {
        assertEquals(mapOf("source" to "link"), AnalyticsEvent.AttributionCaptured(AttributionSource.LINK).properties)
        assertEquals(mapOf("source" to "IRL"), AnalyticsEvent.AttributionCaptured(AttributionSource.IRL).properties)
        assertEquals(mapOf("source" to "company"), AnalyticsEvent.AttributionCaptured(AttributionSource.COMPANY).properties)
        assertEquals(mapOf("channel" to "sms"), AnalyticsEvent.InviteShared("sms").properties)
        assertTrue(AnalyticsEvent.IrlCodeShown.properties.isEmpty())
        assertTrue(AnalyticsEvent.ReferralConverted.properties.isEmpty())
    }

    @Test fun attributionSourceIsTheTouchKindNotTheCampaignTag() {
        assertEquals(AttributionSource.IRL, AttributionSource.of(Attribution.of(irlCode = "I1", inviteCode = "X")))
        assertEquals(AttributionSource.LINK, AttributionSource.of(Attribution.of(inviteCode = "X", source = "qr")))
        assertEquals(AttributionSource.COMPANY, AttributionSource.of(Attribution.of(companyCode = "ACME")))
        assertNull(AttributionSource.of(Attribution.of(source = "utm")))
    }

    @Test fun campaignTagOnlyCaptureEmitsNoInvalidEvent() {
        val analytics = RecordingAnalyticsEmitter()
        AttributionCapture(MemoryAttributionStore(), analytics).capture(Attribution.of(source = "utm"))
        assertTrue(analytics.events.isEmpty())
    }

    @Test fun shareActionEmitsInviteShared() {
        val analytics = RecordingAnalyticsEmitter()
        var sharedLink: String? = null
        val sharer = object : ReferralSharer {
            override fun share(link: String, channel: String) { sharedLink = link }
            override fun copy(link: String) {}
        }
        ReferralShareAction(sharer, analytics).share("https://dad.org/r/X", "sms")
        assertEquals("https://dad.org/r/X", sharedLink)
        assertEquals("invite_shared", analytics.events.single().name)
        assertEquals("sms", (analytics.events.single() as AnalyticsEvent.InviteShared).channel)
    }
}
