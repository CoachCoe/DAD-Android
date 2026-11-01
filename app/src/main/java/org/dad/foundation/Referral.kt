package org.dad.foundation

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The donor's personal invite link and its scannable QR (PRD R-1 share link, R-2 IRL QR).
 * The invite URL is built from the public origin + the donor's referral code; the QR encodes
 * that exact URL so a scan deep-links straight into the deferred-attribution capture path.
 */
object Referral {
    /**
     * Build the canonical invite URL for a referral code against the public origin.
     * Matches the server route `/r/<code>` (`?via=irl` for the in-person variant). Returns null for a malformed origin or a code that fails attribution sanitization, so a
     * bad code can never produce a sharable-but-broken link.
     */
    fun inviteLink(publicOrigin: String, code: String, inPerson: Boolean = false): String? {
        val sanitized = Attribution.sanitized(code) ?: return null
        val origin = publicOrigin.trimEnd('/')
        if (!origin.startsWith("https://") || origin.length < "https://a".length) return null
        return "$origin/r/$sanitized" + if (inPerson) "?via=irl" else ""
    }

    /**
     * Encode a string into a QR bit-matrix (true = dark module). Pure computation — rendering to
     * a Compose/Bitmap surface is the caller's job — so the encode/decode round-trip is a unit
     * test. Uses quartile error correction so the code stays scannable with a center logo overlay.
     */
    fun qrMatrix(contents: String, size: Int = 512): Array<BooleanArray> {
        require(contents.isNotEmpty()) { "QR contents must not be empty" }
        require(size in 64..2048) { "QR size out of range" }
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            EncodeHintType.MARGIN to 1,
        )
        val bitMatrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, size, size, hints)
        return Array(bitMatrix.height) { y -> BooleanArray(bitMatrix.width) { x -> bitMatrix.get(x, y) } }
    }
}

/**
 * Shares an invite link through the Android share sheet (R-1) or copies it (R-2). An interface
 * so tests assert the share was requested with the right payload and the app wires the real
 * `Intent.ACTION_SEND` / clipboard in the Activity. Every successful share emits `invite_shared`.
 */
interface ReferralSharer {
    fun share(link: String, channel: String)
    fun copy(link: String)
}

/** Drives sharing + analytics; the UI calls this, not the raw sharer. */
class ReferralShareAction(private val sharer: ReferralSharer, private val analytics: AnalyticsEmitter? = null) {
    fun share(link: String, channel: String = "system") {
        sharer.share(link, channel)
        analytics?.emit(AnalyticsEvent.InviteShared(channel))
    }
    fun copy(link: String) {
        sharer.copy(link)
        analytics?.emit(AnalyticsEvent.InviteShared("copy"))
    }
}
