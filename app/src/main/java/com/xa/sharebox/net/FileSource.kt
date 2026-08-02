package com.xa.sharebox.net

import com.xa.sharebox.model.FileEntry
import java.io.File

interface FileSource {
    val displayName: String
    val connectError: String

    suspend fun connect(): Boolean
    suspend fun list(path: String): List<FileEntry>
    suspend fun mkdir(path: String): Boolean
    suspend fun delete(path: String): Boolean
    suspend fun rename(from: String, to: String): Boolean
    suspend fun download(remotePath: String, localFile: File, onProgress: (Long, Long) -> Unit)
    suspend fun upload(localFile: File, remotePath: String, onProgress: (Long, Long) -> Unit)
    fun close()
}

class LocalFileSource(private val rootPath: String) : FileSource {
    override val displayName: String = "本地存储"
    override val connectError: String = ""

    override suspend fun connect(): Boolean = true

    override suspend fun list(path: String): List<FileEntry> {
        val dir = if (path.isEmpty() || path == "/") File(rootPath) else File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.name != "." && it.name != ".." }
            ?.map {
                FileEntry(
                    name = it.name,
                    path = it.absolutePath,
                    isDirectory = it.isDirectory,
                    size = if (it.isFile) it.length() else 0,
                    lastModified = it.lastModified()
                )
            }
            ?.sorted()
            ?: emptyList()
    }

    override suspend fun mkdir(path: String): Boolean = File(path).mkdirs()
    override suspend fun delete(path: String): Boolean {
        val f = File(path)
        if (f.isDirectory) f.listFiles()?.forEach { delete(it.absolutePath) }
        return f.delete()
    }
    override suspend fun rename(from: String, to: String): Boolean = File(from).renameTo(File(to))

    override suspend fun download(remotePath: String, localFile: File, onProgress: (Long, Long) -> Unit) {
        val src = File(remotePath)
        if (src.isDirectory) {
            com.xa.sharebox.util.FileUtils.copyDirectory(src, localFile)
        } else {
            localFile.parentFile?.mkdirs()
            src.inputStream().use { input ->
                localFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var total = 0L
                    val size = src.length()
                    while (input.read(buffer).also { read = it } > 0) {
                        output.write(buffer, 0, read)
                        total += read
                        onProgress(total, size)
                    }
                }
            }
        }
    }

    override suspend fun upload(localFile: File, remotePath: String, onProgress: (Long, Long) -> Unit) {
        download(localFile.absolutePath, File(remotePath), onProgress)
    }

    override fun close() {}
}
