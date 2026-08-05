package com.xa.sharebox.net

import com.xa.sharebox.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lightweight helper for listing local files.
 * Does NOT implement FileSource — it's only used internally by MainVM
 * for the "Local" tab file browser.
 */
class LocalFileSource(private val rootPath: String) {

    suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        try {
            val dir = File(path)
            dir.listFiles()?.map { f ->
                FileEntry(
                    name = f.name,
                    path = f.absolutePath,
                    isDirectory = f.isDirectory,
                    size = if (f.isFile) f.length() else 0L,
                    lastModified = f.lastModified()
                )
            }?.sorted() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
