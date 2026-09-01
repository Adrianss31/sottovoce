package it.sottovoce.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

private const val DEFAULT_COVER_EDGE_PX = 512
private const val MAX_COVER_EDGE_PX = 2_048
private const val COVER_CROSSFADE_MS = 180

private data class CoverRequest(
    val path: String,
    val widthPx: Int,
    val heightPx: Int,
)

private data class CoverCacheKey(
    val request: CoverRequest,
    val lastModified: Long,
    val fileSize: Long,
)

private object CoverBitmapCache {
    private val maxSizeKb = (Runtime.getRuntime().maxMemory() / 1024L / 12L)
        .coerceIn(4L * 1024L, 32L * 1024L)
        .toInt()

    private val bitmaps = object : LruCache<CoverCacheKey, ImageBitmap>(maxSizeKb) {
        override fun sizeOf(key: CoverCacheKey, value: ImageBitmap): Int =
            (value.width.toLong() * value.height.toLong() * 4L / 1024L)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
    }

    private val latestKey = ConcurrentHashMap<CoverRequest, CoverCacheKey>()

    fun peek(request: CoverRequest): ImageBitmap? =
        latestKey[request]?.let(bitmaps::get)

    fun get(key: CoverCacheKey): ImageBitmap? = bitmaps.get(key)

    fun put(key: CoverCacheKey, bitmap: ImageBitmap) {
        latestKey.put(key.request, key)
            ?.takeIf { it != key }
            ?.let(bitmaps::remove)
        bitmaps.put(key, bitmap)
    }

    fun forget(request: CoverRequest) {
        latestKey.remove(request)?.let(bitmaps::remove)
    }

    fun clear() {
        latestKey.clear()
        bitmaps.evictAll()
    }
}

private fun coverRequest(path: String?, requestedWidthPx: Int, requestedHeightPx: Int): CoverRequest? {
    val safePath = path?.takeIf(String::isNotBlank) ?: return null
    return CoverRequest(
        path = safePath,
        widthPx = requestedWidthPx.coerceIn(1, MAX_COVER_EDGE_PX),
        heightPx = requestedHeightPx.coerceIn(1, MAX_COVER_EDGE_PX),
    )
}

/**
 * Loads a local cover at approximately the size at which it will be drawn.
 * Missing, unreadable and unsupported files safely return null.
 */
internal suspend fun loadCoverBitmap(
    path: String?,
    requestedWidthPx: Int,
    requestedHeightPx: Int,
): ImageBitmap? {
    val request = coverRequest(path, requestedWidthPx, requestedHeightPx) ?: return null
    return withContext(Dispatchers.IO) {
        val file = runCatching { File(request.path) }.getOrNull()
        if (file == null || !file.isFile || !file.canRead()) {
            CoverBitmapCache.forget(request)
            return@withContext null
        }

        val cacheKey = CoverCacheKey(request, file.lastModified(), file.length())
        CoverBitmapCache.get(cacheKey)?.let { return@withContext it }

        val bitmap = decodeCover(file, request.widthPx, request.heightPx)
            ?: return@withContext null
        CoverBitmapCache.put(cacheKey, bitmap)
        bitmap
    }
}

/**
 * Compose-facing API. The cached bitmap is returned synchronously when available;
 * otherwise decoding continues on Dispatchers.IO and the value starts as null.
 */
@Composable
internal fun rememberCoverBitmap(
    path: String?,
    requestedWidthPx: Int,
    requestedHeightPx: Int,
): ImageBitmap? {
    val request = coverRequest(path, requestedWidthPx, requestedHeightPx) ?: return null
    return key(request) {
        val bitmap by produceState<ImageBitmap?>(
            initialValue = CoverBitmapCache.peek(request),
            key1 = request,
        ) {
            value = loadCoverBitmap(request.path, request.widthPx, request.heightPx)
        }
        bitmap
    }
}

/**
 * Ready-to-use cover surface with a bounded placeholder-to-bitmap crossfade.
 * Its modifier should provide bounded dimensions (for example width + aspectRatio).
 */
@Composable
internal fun CrossfadeCoverImage(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    placeholder: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val motion = LocalMotionPolicy.current
        val requestedWidthPx = if (constraints.hasBoundedWidth) {
            constraints.maxWidth.coerceAtLeast(1)
        } else {
            DEFAULT_COVER_EDGE_PX
        }
        val requestedHeightPx = if (constraints.hasBoundedHeight) {
            constraints.maxHeight.coerceAtLeast(1)
        } else {
            DEFAULT_COVER_EDGE_PX
        }
        val bitmap = rememberCoverBitmap(path, requestedWidthPx, requestedHeightPx)

        Crossfade(
            targetState = bitmap,
            animationSpec = tween(motion.durationMillis(COVER_CROSSFADE_MS)),
            label = "cover placeholder to bitmap",
        ) { loaded ->
            if (loaded == null) {
                Box(Modifier.fillMaxSize(), content = placeholder)
            } else {
                Image(
                    bitmap = loaded,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
            }
        }
    }
}

private fun decodeCover(file: File, requestedWidthPx: Int, requestedHeightPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val sampleSize = calculateInSampleSize(
        sourceWidth = bounds.outWidth,
        sourceHeight = bounds.outHeight,
        requestedWidth = requestedWidthPx,
        requestedHeight = requestedHeightPx,
    )

    return try {
        decodeAndScale(file, requestedWidthPx, requestedHeightPx, sampleSize)
    } catch (_: OutOfMemoryError) {
        CoverBitmapCache.clear()
        try {
            decodeAndScale(
                file,
                requestedWidthPx,
                requestedHeightPx,
                (sampleSize * 2).coerceAtMost(512),
            )
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: RuntimeException) {
            null
        }
    } catch (_: RuntimeException) {
        null
    }
}

private fun decodeAndScale(
    file: File,
    requestedWidthPx: Int,
    requestedHeightPx: Int,
    sampleSize: Int,
): ImageBitmap? {
    val decoded = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        },
    ) ?: return null

    val scale = min(
        requestedWidthPx.toFloat() / decoded.width.toFloat(),
        requestedHeightPx.toFloat() / decoded.height.toFloat(),
    ).coerceAtMost(1f)
    if (scale >= 0.999f) return decoded.asImageBitmap()

    val targetWidth = (decoded.width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (decoded.height * scale).toInt().coerceAtLeast(1)
    val scaled = try {
        Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
    } catch (error: Throwable) {
        decoded.recycle()
        throw error
    }
    if (scaled !== decoded) decoded.recycle()
    return scaled.asImageBitmap()
}

private fun calculateInSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    requestedWidth: Int,
    requestedHeight: Int,
): Int {
    var sampleSize = 1
    while (
        sourceWidth / (sampleSize * 2L) >= requestedWidth &&
        sourceHeight / (sampleSize * 2L) >= requestedHeight &&
        sampleSize < 512
    ) {
        sampleSize *= 2
    }
    return sampleSize
}
