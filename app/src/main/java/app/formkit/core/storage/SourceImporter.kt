package app.formkit.core.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.formkit.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

class ImportedFile(val file: File, val displayName: String, val sizeBytes: Long)

@Singleton
class SourceImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Copies a picked file into [directory]. The Photo Picker's read grant doesn't survive the
     * process, and a local copy is what lets a tool screen come back after process death.
     */
    suspend fun import(uri: Uri, directory: File): ImportedFile = withContext(ioDispatcher) {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
            ?: DEFAULT_NAME

        directory.mkdirs()
        val target = File(directory, SOURCE_FILE_NAME)
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("Couldn't open $uri")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        ImportedFile(target, displayName, target.length())
    }

    private companion object {
        const val SOURCE_FILE_NAME = "source"
        const val DEFAULT_NAME = "photo"
    }
}
