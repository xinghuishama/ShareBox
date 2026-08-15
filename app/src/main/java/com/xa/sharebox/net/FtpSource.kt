package com.xa.sharebox.net

import com.xa.sharebox.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class FtpFileSource(
    private val host: String,
    private val port: Int = 21,
    private val user: String,
    private val pass: String
) : FileSource {
    override val displayName: String = "FTP: $host"
    override var connectError: String = ""
        private set

    private var ftp = FTPClient()

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            ftp = FTPClient()
            ftp.setDefaultTimeout(15000)
            ftp.connectTimeout = 15000
            ftp.setDataTimeout(30000)
            ftp.controlEncoding = "UTF-8"
            ftp.setAutodetectUTF8(true)
            ftp.connect(host, port)
            val ok = if (user.isBlank()) ftp.login("anonymous", "anonymous@")
                     else ftp.login(user, pass)
            if (!ok) {
                connectError = "FTP登录失败: ${ftp.replyString}"
                false
            } else {
                ftp.setFileType(FTPClient.BINARY_FILE_TYPE)
                ftp.enterLocalPassiveMode()
                true
            }
        } catch (e: Throwable) {
            connectError = "${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    private fun reconnect(): Boolean {
        try { close() } catch (e: Throwable) {}
        return try {
            ftp = FTPClient()
            ftp.setDefaultTimeout(15000)
            ftp.connectTimeout = 15000
            ftp.setDataTimeout(30000)
            ftp.controlEncoding = "UTF-8"
            ftp.setAutodetectUTF8(true)
            ftp.connect(host, port)
            val ok = if (user.isBlank()) ftp.login("anonymous", "anonymous@")
                     else ftp.login(user, pass)
            if (ok) {
                ftp.setFileType(FTPClient.BINARY_FILE_TYPE)
                ftp.enterLocalPassiveMode()
            }
            ok
        } catch (e: Throwable) { false }
    }

    private fun isConnectionError(e: Throwable): Boolean {
        val msg = e.message ?: ""
        return msg.contains("closed", ignoreCase = true) ||
               msg.contains("connection", ignoreCase = true) ||
               msg.contains("timeout", ignoreCase = true) ||
               msg.contains("530", ignoreCase = true) ||
               msg.contains("421", ignoreCase = true) ||
               msg.contains("not logged in", ignoreCase = true) ||
               msg.contains("broken pipe", ignoreCase = true) ||
               e is java.io.IOException
    }

    private fun <T> withRetry(block: () -> T): T {
        try { return block() }
        catch (e: Throwable) {
            if (isConnectionError(e) && reconnect()) return block()
            throw e
        }
    }

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        withRetry {
            val files = ftp.listFiles(path)
            files.map { f ->
                FileEntry(
                    name = f.name,
                    path = if (path.endsWith("/")) "$path${f.name}" else "$path/${f.name}",
                    isDirectory = f.isDirectory,
                    size = f.size,
                    lastModified = f.timestamp?.timeInMillis ?: 0
                )
            }.sorted()
        }
    }

    override suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) {
        withRetry { ftp.makeDirectory(path) }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        // Try as file first
        if (withRetry { ftp.deleteFile(path) }) return@withContext true
        // Try as directory (recursive delete)
        try {
            deleteDirRecursive(path)
            withRetry { ftp.removeDirectory(path) }
        } catch (e: Throwable) { false }
    }

    private fun deleteDirRecursive(path: String) {
        val files = withRetry { ftp.listFiles(path) }
        for (f in files) {
            if (f.name == "." || f.name == "..") continue
            val childPath = if (path.endsWith("/")) "$path${f.name}" else "$path/${f.name}"
            if (f.isDirectory) {
                deleteDirRecursive(childPath)
                withRetry { ftp.removeDirectory(childPath) }
            } else {
                withRetry { ftp.deleteFile(childPath) }
            }
        }
    }

    override suspend fun rename(from: String, to: String): Boolean = withContext(Dispatchers.IO) {
        withRetry { ftp.rename(from, to) }
    }

    override suspend fun download(remotePath: String, localFile: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            withRetry {
                localFile.parentFile?.mkdirs()
                // Get file size via listFiles (FTPClient has no getSize method)
                val total = try {
                    ftp.listFiles(remotePath).firstOrNull()?.size ?: 0L
                } catch (e: Exception) { 0L }

                val input = ftp.retrieveFileStream(remotePath)
                    ?: throw RuntimeException("下载失败: ${ftp.replyString}")
                input.use {
                    FileOutputStream(localFile).use { output ->
                        val buf = ByteArray(8192)
                        var transferred = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            transferred += n
                            onProgress(transferred, total)
                        }
                    }
                    if (!ftp.completePendingCommand())
                        throw RuntimeException("下载完成但FTP命令未完成: ${ftp.replyString}")
                }
                onProgress(localFile.length(), localFile.length().coerceAtLeast(total))
            }
        }
    }

    override suspend fun upload(localFile: File, remotePath: String, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            withRetry {
                val total = localFile.length()
                val output = ftp.storeFileStream(remotePath)
                    ?: throw RuntimeException("上传失败: ${ftp.replyString}")
                output.use {
                    FileInputStream(localFile).use { input ->
                        val buf = ByteArray(8192)
                        var transferred = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            transferred += n
                            onProgress(transferred, total)
                        }
                    }
                    if (!ftp.completePendingCommand())
                        throw RuntimeException("上传完成但FTP命令未完成: ${ftp.replyString}")
                }
                onProgress(total, total)
            }
        }
    }

    override fun close() {
        try {
            if (ftp.isConnected) {
                ftp.logout()
                ftp.disconnect()
            }
        } catch (e: Throwable) { }
    }
}
