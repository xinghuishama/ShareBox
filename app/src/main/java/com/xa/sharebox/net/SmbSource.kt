package com.xa.sharebox.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.xa.sharebox.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbFileSource(
    private val host: String,
    private val shareName: String,
    private val username: String,
    private val password: String,
    private val port: Int = 445
) : FileSource {

    override val displayName: String
        get() = "SMB: $host/$shareName"

    override var connectError: String = ""
        private set

    private var client: SMBClient? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null

    private val smbConfig: SmbConfig by lazy {
        SmbConfig.builder()
            .withDialects(EnumSet.of(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_3_1_1))
            .withMultiProtocolNegotiate(true)
            .withTimeout(60, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .withReadBufferSize(65536)
            .withWriteBufferSize(65536)
            .build()
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            client = SMBClient(smbConfig)
            val connection = client!!.connect(host, port)
            val auth = if (username.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(username, password.toCharArray(), null)
            }
            session = connection.authenticate(auth)
            diskShare = session?.connectShare(shareName) as? DiskShare
            if (diskShare == null) {
                connectError = "无法连接到共享: $shareName"
                false
            } else {
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
            client = SMBClient(smbConfig)
            val connection = client!!.connect(host, port)
            val auth = if (username.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(username, password.toCharArray(), null)
            }
            session = connection.authenticate(auth)
            diskShare = session?.connectShare(shareName) as? DiskShare
            diskShare != null
        } catch (e: Throwable) {
            false
        }
    }

    private fun isConnectionError(e: Throwable): Boolean {
        val msg = e.message ?: ""
        return msg.contains("closed", ignoreCase = true) ||
               msg.contains("connection", ignoreCase = true) ||
               msg.contains("transport", ignoreCase = true) ||
               msg.contains("session", ignoreCase = true) ||
               e is java.io.IOException
    }

    private fun <T> withRetry(block: () -> T): T {
        try {
            return block()
        } catch (e: Throwable) {
            if (isConnectionError(e) && reconnect()) {
                return block()
            }
            throw e
        }
    }

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        withRetry {
            val s = diskShare ?: throw RuntimeException("Not connected")
            val result = mutableListOf<FileEntry>()
            for (info in s.list(path.ifEmpty { "" })) {
                val name = info.fileName
                if (name == "." || name == "..") continue
                result.add(FileEntry(
                    name = name,
                    path = joinPath(path, name),
                    isDirectory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                    size = info.endOfFile,
                    lastModified = info.lastWriteTime.toEpochMillis()
                ))
            }
            result.sorted()
        }
    }

    override suspend fun download(remotePath: String, localFile: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            withRetry {
                val s = diskShare ?: throw RuntimeException("Not connected")
                val rf = s.openFile(
                    remotePath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                rf.use {
                    FileOutputStream(localFile).use { out ->
                        rf.inputStream.use { input ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            var transferred = 0L
                            val total = rf.fileInformation.standardInformation.endOfFile
                            while (input.read(buffer).also { read = it } > 0) {
                                out.write(buffer, 0, read)
                                transferred += read
                                onProgress(transferred, total)
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun upload(localFile: File, remotePath: String, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            withRetry {
                val s = diskShare ?: throw RuntimeException("Not connected")
                val rf = s.openFile(
                    remotePath,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE),
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null
                )
                rf.use {
                    FileInputStream(localFile).use { inp ->
                        rf.outputStream.use { out ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            var transferred = 0L
                            val total = localFile.length()
                            while (inp.read(buffer).also { read = it } > 0) {
                                out.write(buffer, 0, read)
                                transferred += read
                                onProgress(transferred, total)
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            withRetry {
                val s = diskShare ?: throw RuntimeException("Not connected")
                s.mkdir(path)
                true
            }
        } catch (e: Throwable) { false }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            withRetry {
                val s = diskShare ?: throw RuntimeException("Not connected")
                s.rm(path)
                true
            }
        } catch (e: Throwable) {
            try {
                withRetry {
                    val s = diskShare ?: throw RuntimeException("Not connected")
                    s.rmdir(path, true)
                    true
                }
            } catch (e2: Throwable) { false }
        }
    }

    override suspend fun rename(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            withRetry {
                val s = diskShare ?: throw RuntimeException("Not connected")
                // Try as file first
                val rf = s.openFile(
                    oldPath,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                rf.use { it.rename(newPath, false) }
                true
            }
        } catch (e: Throwable) {
            try {
                withRetry {
                    val s = diskShare ?: throw RuntimeException("Not connected")
                    // Try as directory
                    val dir = s.openDirectory(
                        oldPath,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        null,
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
                    )
                    dir.use { it.rename(newPath, false) }
                    true
                }
            } catch (e2: Throwable) { false }
        }
    }

    override fun close() {
        try { diskShare?.close() } catch (e: Throwable) {}
        try { session?.close() } catch (e: Throwable) {}
        try { client?.close() } catch (e: Throwable) {}
        diskShare = null
        session = null
        client = null
    }

    private fun joinPath(base: String, name: String): String {
        val sep = "\\\\"
        return if (base.isEmpty()) name
        else if (base.endsWith(sep)) "$base$name"
        else "$base$sep$name"
    }
}
