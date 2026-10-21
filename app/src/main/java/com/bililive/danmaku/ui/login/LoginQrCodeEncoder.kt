package com.bililive.danmaku.ui.login

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Pure, local encoder: login challenges must not be sent to an image service. */
internal object LoginQrCodeEncoder {
    fun encode(content: String): QrCodePixels {
        require(content.isNotBlank()) { "QR content must not be blank" }
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            512,
            512,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4
            )
        )
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) {
                0xFF000000.toInt()
            } else {
                0xFFFFFFFF.toInt()
            }
        }
        return QrCodePixels(matrix.width, matrix.height, pixels)
    }
}

internal class QrCodePixels(val width: Int, val height: Int, val pixels: IntArray)
