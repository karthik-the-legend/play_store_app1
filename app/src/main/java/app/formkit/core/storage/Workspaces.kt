package app.formkit.core.storage

import android.content.Context
import app.formkit.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-session working folders in the cache directory. Each tool session gets its own folder, so
 * [CacheJanitor] can age them out one by one and deleting one never touches another.
 */
@Singleton
class Workspaces @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun create(tool: String): File = withContext(ioDispatcher) {
        File(context.cacheDir, "$tool-${System.currentTimeMillis()}").apply { mkdirs() }
    }

    suspend fun delete(path: String) {
        withContext(ioDispatcher) { File(path).deleteRecursively() }
    }
}
