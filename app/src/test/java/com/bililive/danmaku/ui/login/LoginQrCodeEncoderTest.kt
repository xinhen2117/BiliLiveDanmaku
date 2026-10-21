package com.bililive.danmaku.ui.login

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.WriterException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginQrCodeEncoderTest {
    // Synthetic challenge only. Tests must never request or use a real login token.
    private val challenge = "https://passport.bilibili.com/h5-app/passport/login/scan?fake_test_key=a%2Bb&source=test"

    @Test
    fun `generated pixels decode to exact login link`() {
        val qrCode = LoginQrCodeEncoder.encode(challenge)
        assertEquals(512, qrCode.width)
        assertEquals(512, qrCode.height)
        assertEquals(qrCode.width * qrCode.height, qrCode.pixels.size)
        assertEquals(challenge, decode(qrCode))
    }

    @Test
    fun `encoding is deterministic`() {
        assertArrayEquals(
            LoginQrCodeEncoder.encode(challenge).pixels,
            LoginQrCodeEncoder.encode(challenge).pixels
        )
    }

    @Test
    fun `UTF-8 data round trips without changing text`() {
        val content = "https://example.invalid/扫码?value=登录测试&plus=+&escaped=%2F"
        assertEquals(content, decode(LoginQrCodeEncoder.encode(content)))
    }

    @Test
    fun `pixels are opaque black and white with a white border`() {
        val qrCode = LoginQrCodeEncoder.encode(challenge)
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertTrue(qrCode.pixels.any { it == black })
        assertTrue(qrCode.pixels.all { it == black || it == white })
        for (x in 0 until qrCode.width) {
            assertEquals(white, qrCode.pixels[x])
            assertEquals(white, qrCode.pixels[(qrCode.height - 1) * qrCode.width + x])
        }
        for (y in 0 until qrCode.height) {
            assertEquals(white, qrCode.pixels[y * qrCode.width])
            assertEquals(white, qrCode.pixels[y * qrCode.width + qrCode.width - 1])
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank content is rejected`() {
        LoginQrCodeEncoder.encode(" \n\t")
    }

    @Test(expected = WriterException::class)
    fun `oversized content fails rather than silently truncating`() {
        LoginQrCodeEncoder.encode("x".repeat(10_000))
    }

    private fun decode(qrCode: QrCodePixels): String {
        val source = RGBLuminanceSource(qrCode.width, qrCode.height, qrCode.pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }
}
