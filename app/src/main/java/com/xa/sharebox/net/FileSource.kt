package com.xa.sharebox.net

import com.xa.sharebox.model.FileEntry
import java.io.File

/**
 * Common interface for remote file sources (FTP, SMB).
 * Used by MainVM for all remote file operations.
 */
interface FileSource {
    /** Short label shown in the top bar, e.g. "FTP 192.168.1.100:21". */
    val displayName: String

    /** Last connection error message (empty if none). */
    val connectError: String

    /** Connect / login to the remote server. Returns true on success. */
    suspend fun connect(): Boolean

    /** List files in the given remote directory path. */
    suspend fun list(path: String): List<FileEntry>

    /** Download a remote file to a local path, reporting progress. */
    suspend fun download(remotePath: String, localFile: File, progress: (Long, Long) -> Unit)

    /** Upload a local file to a remote path, reporting progress. */
    suspend fun upload(localFile: File, remotePath: String, progress: (Long, Long) -> Unit)

    /** Delete a remote file or directory. Returns true on success. */
    suspend fun delete(path: String): Boolean

    /** Create a remote directory. Returns true on success. */
    suspend fun mkdir(path: String): Boolean

    /** Close the connection and release resources. */
    fun close()
}
