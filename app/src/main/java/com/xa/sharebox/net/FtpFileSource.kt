package com.xa.sharebox.net

import com.xa.sharebox.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.io.IOException

/**
 * FTP client implementation of FileSource using Apache Commons Net.
 */
class FtpFileSource(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String
) : FileSource {

    private val ftp = FTPClient()
    private var _connectError = ""

    override val displayName: String
        get() = "FTP $host:$port"

    override val connectError: String
        get() = _connectError

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            ftp.connect(host, port)
            val ok = ftp.login(username, password)
            if (!ok) {
                _connectError = "登录失败"
                return@withContext false
            }
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            ftp.enterLocalPassiveMode()
            ftp.bufferSize = 8192
            _connectError = ""
            true
        } catch (e: Exception) {
            _connectError = e.message ?: "连接失败"
            false
        }
    }

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        try {
            val files = ftp.listFiles(path)
            files.filter { f -> f.name != "." && f.name != ".." }
                .map { f ->
                    val childPath = if (path.endsWith("/")) "$path${f.name}" else "$path/${f.name}"
                    FileEntry(
                        name = f.name,
                        path = childPath,
                        isDirectory = f.isDirectory,
                        size = if (f.isFile) f.size else 0L,
                        lastModified = f.timestamp?.timeInMillis ?: 0L
                    )
                }
                .sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun download(
        remotePath: String,
        localFile: File,
        progress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val totalSize = try { ftp.getSize(remotePath) } catch (e: Exception) { 0L }
        val input = ftp.retrieveFileStream(remotePath)
            ?: throw IOException("无法打开下载流")
        input.use { ins ->
            localFile.outputStream().use { outs ->
                val buf = ByteArray(8192)
                var transferred = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    outs.write(buf, 0, n)
                    transferred += n
                    progress(transferred, totalSize)
                }
            }
        }
        ftp.completePendingCommand()
    }

    override suspend fun upload(
        localFile: File,
        remotePath: String,
        progress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val totalSize = localFile.length()
        val output = ftp.storeFileStream(remotePath)
            ?: throw IOException("无法打开上传流")
        localFile.inputStream().use { ins ->
            output.use { outs ->
                val buf = ByteArray(8192)
                var transferred = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    outs.write(buf, 0, n)
                    transferred += n
                    progress(transferred, totalSize)
                }
            }
        }
        ftp.completePendingCommand()
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Try file deletion first
            if (ftp.deleteFile(path)) return@withContext true
            // If that fails, try recursive directory deletion
            deleteDirRecursive(path)
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun deleteDirRecursive(path: String): Boolean {
        return try {
            val children = ftp.listFiles(path)
            for (child in children) {
                if (child.name == "." || child.name == "..") continue
                val childPath = if (path.endsWith("/")) "$path${child.name}" else "$path/${child.name}"
                if (child.isDirectory) {
                    deleteDirRecursive(childPath)
                } else {
                    ftp.deleteFile(childPath)
                }
            }
            ftp.removeDirectory(path)
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            ftp.makeDirectory(path)
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        try { if (ftp.isConnected) ftp.logout() } catch (e: Exception) {}
        try { if (ftp.isConnected) ftp.disconnect() } catch (e: Exception) {}
    }
}
