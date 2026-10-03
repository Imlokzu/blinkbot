package me.waveio.claudebot.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class QrDecoderTest {
    @Test fun bundledDecoderReadsPairingPayloadOffline() {
        val payload = "claudebot://pair?url=https%3A%2F%2Fexample.invalid&token=test-only"
        val image = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 320, 320)
        assertEquals(payload, pairingQrDecoderFactory().createDecoder(emptyMap<DecodeHintType, Any>()).decode(luminance(image))?.text)
    }

    @Test fun utf8PayloadSurvivesDecoding() {
        val payload = "device-\u00e9-\u03bb"
        val image = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 320, 320, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        assertEquals(payload, pairingQrDecoderFactory().createDecoder(emptyMap<DecodeHintType, Any>()).decode(luminance(image))?.text)
    }

    @Test fun corruptedFrameCanBeFollowedByValidQr() {
        // A failed frame must leave the decoder usable for the next camera frame.
        val decoder = pairingQrDecoderFactory().createDecoder(emptyMap<DecodeHintType, Any>())
        assertNull(decoder.decode(luminance(BitMatrix(320, 320))))
        val image = QRCodeWriter().encode("test-pairing", BarcodeFormat.QR_CODE, 320, 320)
        assertEquals("test-pairing", decoder.decode(luminance(image))?.text)
    }

    @Test fun scannerRejectsNonQrBarcodes() {
        val image = Code128Writer().encode("123456789", BarcodeFormat.CODE_128, 320, 160)
        assertNull(pairingQrDecoderFactory().createDecoder(emptyMap<DecodeHintType, Any>()).decode(luminance(image)))
    }

    private fun luminance(matrix: BitMatrix): RGBLuminanceSource {
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return RGBLuminanceSource(matrix.width, matrix.height, pixels)
    }
}
