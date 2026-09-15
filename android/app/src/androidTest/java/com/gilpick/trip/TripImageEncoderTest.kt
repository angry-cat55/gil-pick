package com.gilpick.trip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #555: 업로드 전 사진 변환(긴 변 1920px 이하, JPEG 품질 85, 투명 영역 흰색)과 읽기 실패 처리.
 *
 * Bitmap 압축·Coil 읽기는 기기 구현을 쓰므로 계측 test로 확인한다.
 */
class TripImageEncoderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 긴_변이_1920을_넘으면_비율을_지켜_줄이고_JPEG로_만든다() {
        val bytes = encodeTripImage(Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) })

        val size = bounds(bytes)
        assertEquals(1920, size.first)
        assertEquals(1440, size.second)
        assertJpeg(bytes)
    }

    @Test
    fun 세로_사진도_긴_변_기준으로_줄인다() {
        val size = bounds(encodeTripImage(Bitmap.createBitmap(3000, 4000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }))

        assertEquals(1440, size.first)
        assertEquals(1920, size.second)
    }

    @Test
    fun 작은_사진은_키우지_않는다() {
        val size = bounds(encodeTripImage(Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }))

        assertEquals(800, size.first)
        assertEquals(600, size.second)
    }

    @Test
    fun 투명한_부분은_흰색으로_채운다() {
        val transparent = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }

        val bytes = encodeTripImage(transparent)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val pixel = decoded.getPixel(50, 50)
        assertTrue("투명 영역이 흰색이 아니다: ${Integer.toHexString(pixel)}", Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }

    @Test
    fun 큰_PNG를_고르면_1920px_JPEG로_바꿔_올릴_사진으로_만든다() = runBlocking {
        val file = File(context.cacheDir, "trip-encoder-large.png")
        file.outputStream().use {
            Bitmap.createBitmap(4032, 3024, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        val pick = readTripImage(context, Uri.fromFile(file)) as TripImagePick.Picked

        assertEquals("image/jpeg", pick.image.mimeType)
        assertJpeg(pick.image.bytes)
        assertEquals(1920, bounds(pick.image.bytes).first)
    }

    @Test
    fun 기기가_읽을_수_없는_파일은_읽지_못함으로_거절한다() = runBlocking {
        val file = File(context.cacheDir, "trip-encoder-broken.jpg").apply { writeText("이미지가 아니다") }

        val pick = readTripImage(context, Uri.fromFile(file))

        assertEquals(TripImagePick.Rejected(TripImageError.UNREADABLE), pick)
    }

    private fun bounds(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    /** 서버가 파일 앞부분 서명으로 형식을 판정한다(`FF D8 FF`). */
    private fun assertJpeg(bytes: ByteArray) {
        assertTrue(bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte())
    }
}
