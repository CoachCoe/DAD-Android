package org.dad.foundation

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    private val token = "a".repeat(22) + "." + "b".repeat(43)

    /** Render [text] as a QR code into ARGB pixels, [scale] pixels per module with a quiet zone, like a photo of a poster. */
    private fun render(text: String, scale: Int = 6, inverted: Boolean = false): Triple<Int, Int, IntArray> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
        val width = matrix.width * scale
        val height = matrix.height * scale
        val pixels = IntArray(width * height) { index ->
            val dark = matrix.get(index % width / scale, index / width / scale)
            if (dark != inverted) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Triple(width, height, pixels)
    }

    @Test fun readsTheCheckInTokenOutOfAPhoto() {
        val (w, h, pixels) = render(token)
        val text = QrDecoder.decode(w, h, pixels)
        assertEquals(token, text)
        assertEquals(true, CheckInCode.isWellFormed(text!!))
    }

    @Test fun readsASmallerRenderingToo() {
        val (w, h, pixels) = render(token, scale = 3)
        assertEquals(token, QrDecoder.decode(w, h, pixels))
    }

    @Test fun aBlankPhotoHasNoCode() {
        assertNull(QrDecoder.decode(64, 64, IntArray(64 * 64) { 0xFFFFFFFF.toInt() }))
    }

    @Test fun noiseHasNoCode() {
        val random = java.util.Random(7)
        assertNull(QrDecoder.decode(80, 80, IntArray(80 * 80) { if (random.nextBoolean()) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }))
    }

    @Test fun degenerateInputIsRefusedNotCrashed() {
        assertNull(QrDecoder.decode(0, 0, IntArray(0)))
        assertNull(QrDecoder.decode(10, 10, IntArray(5)))
    }

    @Test fun aCodeForAnotherPurposeIsReturnedAndLeftForTheShapeCheckToRefuse() {
        val (w, h, pixels) = render("https://example.org/not-a-checkin")
        val text = QrDecoder.decode(w, h, pixels)
        assertEquals("https://example.org/not-a-checkin", text)
        assertEquals(false, CheckInCode.isWellFormed(text!!))
    }
}
