package org.dad.foundation

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads a QR code out of a photo's pixels (E-8). Uses the ZXing core already shipped for the invite QR, so
 * scanning adds no dependency: the system camera app takes the photo, this reads it. Pure JVM so it is a unit test.
 */
object QrDecoder {
    /** The text of the first QR code in the ARGB [pixels] (row-major, [width] x [height]), or null when there is none. */
    fun decode(width: Int, height: Int, pixels: IntArray): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        return try {
            QRCodeReader().decode(bitmap, hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ReaderException) {
            null
        }
    }
}
