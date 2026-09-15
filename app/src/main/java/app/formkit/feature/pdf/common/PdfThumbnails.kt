package app.formkit.feature.pdf.common

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.formkit.core.pdf.PdfRasterizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Page previews shared by every PDF screen. Rendering is limited to two pages at a time. */
object PdfThumbnailCache {
    private const val MAX_BYTES = 24 * 1024 * 1024
    private val gate = Semaphore(2)
    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(path: String, pageIndex: Int, maxEdge: Int): Bitmap? {
        val key = "$path#$pageIndex@$maxEdge"
        cache.get(key)?.let { return it }
        return gate.withPermit {
            cache.get(key) ?: withContext(Dispatchers.IO) {
                runCatching { PdfRasterizer(File(path)).use { it.thumbnail(pageIndex, maxEdge) } }.getOrNull()
            }?.also { cache.put(key, it) }
        }
    }
}

@Composable
fun PdfPageThumbnail(
    path: String,
    pageIndex: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxEdge: Int = 360,
) {
    val image by produceState<ImageBitmap?>(null, path, pageIndex, maxEdge) {
        value = PdfThumbnailCache.load(path, pageIndex, maxEdge)?.asImageBitmap()
    }
    Box(
        modifier = modifier
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = image
        if (loaded != null) {
            Image(loaded, contentDescription, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
