package app.formkit.core.storage

import java.io.File

/**
 * Housekeeping for the app's cache directory, where tools keep their working files.
 *
 * All calls block on disk I/O; run them on the IO dispatcher.
 */
class CacheJanitor(
    rootProvider: () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    constructor(root: File, clock: () -> Long = System::currentTimeMillis) : this({ root }, clock)

    // Context.getCacheDir() touches the disk, so it's resolved on first use (an IO thread),
    // not when Hilt builds this object on the main thread.
    private val root: File by lazy(rootProvider)

    /**
     * Deletes top-level cache entries untouched for longer than [maxAgeMillis]. A directory
     * counts as touched when anything inside it was, so a tool's in-progress folder survives.
     *
     * @return the number of top-level entries removed.
     */
    fun pruneOlderThan(maxAgeMillis: Long): Int {
        val cutoff = clock() - maxAgeMillis
        return entries().count { entry -> entry.newestModification() < cutoff && entry.deleteRecursively() }
    }

    fun sizeBytes(): Long = entries().sumOf { it.totalBytes() }

    /** Deletes everything in the cache directory. Returns the bytes freed. */
    fun clear(): Long = entries().sumOf { entry ->
        val bytes = entry.totalBytes()
        if (entry.deleteRecursively()) bytes else 0L
    }

    private fun entries(): List<File> = root.listFiles()?.toList().orEmpty()

    private fun File.newestModification(): Long =
        walkTopDown().maxOfOrNull { it.lastModified() } ?: lastModified()

    private fun File.totalBytes(): Long =
        walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val STALE_AFTER_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}
