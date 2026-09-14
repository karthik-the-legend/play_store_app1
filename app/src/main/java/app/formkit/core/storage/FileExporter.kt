package app.formkit.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.formkit.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

class ExportedFile(val uri: Uri, val displayName: String, val sizeBytes: Long)

/** The file on disk came out bigger than the limit. Never expected; checked anyway. */
class ExportOverLimitException(val sizeBytes: Long, val maxBytes: Long) :
    IOException("Saved file is $sizeBytes bytes, over the $maxBytes byte limit")

@Singleton
class FileExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val resolver get() = context.contentResolver

    /**
     * Copies [source] into Pictures/FormKit and reads the size back from what actually landed
     * on disk. If that's over [maxBytes], the file is deleted and an error is thrown, so the
     * app can never hand over a file that breaks the limit the user asked for.
     */
    suspend fun saveImage(source: File, displayName: String, mimeType: String, maxBytes: Long?): ExportedFile =
        withContext(ioDispatcher) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                insertScoped(source, displayName, mimeType)
            } else {
                insertLegacy(source, displayName, mimeType)
            }
            try {
                val onDisk = sizeOnDisk(uri)
                if (maxBytes != null && onDisk > maxBytes) throw ExportOverLimitException(onDisk, maxBytes)
                ExportedFile(uri, queryDisplayName(uri) ?: displayName, onDisk)
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }

    /** A content URI other apps can read, for sharing a file that hasn't been saved. */
    fun shareableUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    suspend fun exists(uri: Uri): Boolean = withContext(ioDispatcher) {
        try {
            resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: FileNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * Returns false if the file couldn't be deleted, for example when it was created by an
     * earlier install of the app and Android no longer counts it as ours.
     */
    suspend fun delete(uri: Uri): Boolean = withContext(ioDispatcher) {
        try {
            resolver.delete(uri, null, null) > 0
        } catch (_: SecurityException) {
            false
        }
    }

    private fun insertScoped(source: File, displayName: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused the new file")
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("No output stream for $uri")
            output.use { out -> source.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION") // DATA and the public directory are the only way on Android 9 and older.
    private fun insertLegacy(source: File, displayName: String, mimeType: String): Uri {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Couldn't create $directory")
        val target = uniqueFile(directory, displayName)
        source.copyTo(target)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, target.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.SIZE, target.length())
        }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run {
                target.delete()
                throw IOException("MediaStore refused ${target.name}")
            }
    }

    private fun uniqueFile(directory: File, displayName: String): File {
        val base = displayName.substringBeforeLast('.')
        val extension = displayName.substringAfterLast('.', "")
        var candidate = File(directory, displayName)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($counter)${if (extension.isEmpty()) "" else ".$extension"}")
            counter++
        }
        return candidate
    }

    private fun sizeOnDisk(uri: Uri): Long =
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            ?: throw IOException("Couldn't read back $uri")

    private fun queryDisplayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    companion object {
        const val FOLDER = "FormKit"
    }
}
