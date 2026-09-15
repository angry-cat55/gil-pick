package com.gilpick.trip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Photo Picker가 돌려준 사진을 업로드용 JPEG로 바꾼다(FR-019, #555).
 *
 * 휴대폰 사진은 5MB·HEIC처럼 서버 제한(jpeg·png·webp, 5MB)을 넘기 쉬워 원본을 그대로 올리지 않는다.
 * - 읽기는 앱이 이미 쓰는 Coil에 맡긴다. 필요한 크기 근처로만 줄여 읽어(sampling) 큰 사진을 통째로 메모리에 올리지 않고,
 *   EXIF 방향 보정과 기기가 읽을 수 있는 형식(HEIC 등) 해석도 Coil이 한다.
 * - 긴 변을 [MAX_TRIP_IMAGE_EDGE_PX] 이하로 줄이고 JPEG 품질 [TRIP_IMAGE_JPEG_QUALITY]로 압축한다. 작은 사진은 키우지 않는다.
 * - 기기가 읽지 못하면 [TripImageError.UNREADABLE], 압축 뒤에도 5MB를 넘으면 [TripImageError.TOO_LARGE]다.
 */
internal suspend fun readTripImage(context: Context, uri: Uri): TripImagePick = withContext(Dispatchers.IO) {
    val request = ImageRequest.Builder(context)
        .data(uri)
        .size(MAX_TRIP_IMAGE_EDGE_PX)
        .scale(Scale.FIT)
        // 목표보다 조금 크게 읽혀도 아래에서 정확히 줄인다. 정확히 맞추려고 한 번 더 크게 읽지 않게 한다.
        .precision(Precision.INEXACT)
        // JPEG로 다시 쓰려면 픽셀을 읽을 수 있어야 한다. hardware bitmap은 읽을 수 없다.
        .allowHardware(false)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .build()

    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
        ?: return@withContext TripImagePick.Rejected(TripImageError.UNREADABLE)
    val bytes = encodeTripImage(result.image.toBitmap())
    if (bytes.size > MAX_TRIP_IMAGE_BYTES) return@withContext TripImagePick.Rejected(TripImageError.TOO_LARGE)
    TripImagePick.Picked(PickedTripImage(bytes, TRIP_IMAGE_MIME_TYPE))
}

/**
 * 비트맵을 업로드용 JPEG로 만든다. 긴 변이 [maxEdgePx]보다 크면 비율을 지켜 줄이고, 투명한 부분은 흰색으로 채운다.
 *
 * JPEG에는 투명도가 없어 그대로 압축하면 투명 영역이 검게 나온다. 커버 사진이라 흰 바탕이 자연스럽다.
 */
internal fun encodeTripImage(
    source: Bitmap,
    maxEdgePx: Int = MAX_TRIP_IMAGE_EDGE_PX,
    quality: Int = TRIP_IMAGE_JPEG_QUALITY,
): ByteArray {
    val longEdge = max(source.width, source.height)
    val ratio = if (longEdge > maxEdgePx) maxEdgePx.toFloat() / longEdge else 1f
    val width = (source.width * ratio).roundToInt().coerceAtLeast(1)
    val height = (source.height * ratio).roundToInt().coerceAtLeast(1)
    val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(source, width, height, true) else source

    val opaque = if (scaled.hasAlpha()) {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { background ->
            Canvas(background).apply {
                drawColor(Color.WHITE)
                drawBitmap(scaled, 0f, 0f, null)
            }
        }
    } else {
        scaled
    }

    return ByteArrayOutputStream().use { out ->
        opaque.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
}

/** 업로드 사진 긴 변 최대(#555 결정). 커버는 폭 최대 약 412dp라 고해상도 화면(약 1440px)에서도 선명하다. */
internal const val MAX_TRIP_IMAGE_EDGE_PX = 1920

/** 업로드 JPEG 품질(#555 결정). 보통 1920px 사진이 300KB~1MB가 된다. */
internal const val TRIP_IMAGE_JPEG_QUALITY = 85

/** 서버 최대 크기 5MB(FR-019, `api/app/services/trip_image.py`). 압축 뒤에도 넘으면 올리지 않는다. */
private const val MAX_TRIP_IMAGE_BYTES = 5 * 1024 * 1024

private const val TRIP_IMAGE_MIME_TYPE = "image/jpeg"
