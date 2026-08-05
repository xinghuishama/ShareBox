package com.xa.sharebox.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.xa.sharebox.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * SMB client implementation of FileSource using smbj.
 * Path separator is backslash (\).
 */
class SmbFileSource(
    private val host: String,
    private val share: String,
    private val username: String,
    private val password: String,
    private val port: Int
) : FileSource {

    private var client: SMBClient? = null
    private var diskShare: DiskShare? = null
    private var _connectError = ""

    override val displayName: String
        get() = "SMB $host/$share"

    override val connectError: String
        get() = _connectError

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val smbClient = SMBClient()
            val connection = smbClient.connect(host, port)
            val auth = if (username.isEmpty()) {
                AuthenticationContext(null, null, null)
            } else {
                AuthenticationContext(
                    username,
                    if (password.isNotEmpty()) password.toCharArray() else null,
                    null
                )
            }
            val session = connection.authenticate(auth)
            @Suppress("UNCHECKED_CAST")
            diskShare = session.connectShare(share) as DiskShare
            client = smbClient
            _connectError = ""
            true
        } catch (e: Exception) {
            _connectError = e.message ?: "连接失败"
            false
        }
    }

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        try {
            val ds = diskShare ?: throw IOException("未连接")
            // smbj uses empty string for share root
            val listPath = path.trimStart('\\')
            val items = ds.list(listPath)
            items.filter { it.name != "." && it.name != ".." }
                .map { f ->
                    val childPath = if (listPath.isEmpty()) "\\${f.name}" else "$path\\${f.name}"
                    val isDir = f.isDirectory
                    FileEntry(
                        name = f.name,
                        path = childPath,
                        isDirectory = isDir,
                        size = if (!isDir) f.endOfFile else 0L,
                        lastModified = 0L
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
        val ds = diskShare ?: throw IOException("未连接")
        val smbPath = remotePath.trimStart('\\')
        val totalSize = try {
            ds.list(smbPath.substringBeforeLast('\\', ""))
                .find { it.name == smbPath.substringAfterLast('\\') }
                ?.endOfFile ?: 0L
        } catch (e: Exception) { 0L }

        val file = ds.openFile(
            smbPath,
            setOf(AccessMask.GENERIC_READ),
            null,
            setOf(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        file.use { smbFile ->
            smbFile.inputStream.use { ins ->
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
        }
    }

    override suspend fun upload(
        localFile: File,
        remotePath: String,
        progress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val ds = diskShare ?: throw IOException("未连接")
        val smbPath = remotePath.trimStart('\\')
        val totalSize = localFile.length()

        val file = ds.openFile(
            smbPath,
            setOf(AccessMask.GENERIC_WRITE),
            null,
            setOf(SMB2ShareAccess.FILE_SHARE_WRITE),
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null
        )
        file.use { smbFile ->
            smbFile.outputStream.use { outs ->
                localFile.inputStream().use { ins ->
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
        }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val ds = diskShare ?: return@withContext false
            val smbPath = path.trimStart('\\')
            // Try as file first
            try {
                ds.rm(smbPath)
                return@withContext true
            } catch (e: Exception) {
                // Not a file — try directory
            }
            try {
                ds.rmdir(smbPath, true)
                true
            } catch (e: Exception) {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val ds = diskShare ?: return@withContext false
            ds.mkdir(path.trimStart('\\'))
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        try { diskShare?.close() } catch (e: Exception) {}
        try { client?.close() } catch (e: Exception) {}
        diskShare = null
        client = null
    }
}
