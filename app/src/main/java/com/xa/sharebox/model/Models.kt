package com.xa.sharebox.model

/** A file or directory entry, used for local, FTP, and SMB listings. */
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long = 0,
    val permission: String? = null
) : Comparable<FileEntry> {
    override fun compareTo(other: FileEntry): Int {
        // Directories first, then alphabetical
        return if (isDirectory != other.isDirectory) {
            if (isDirectory) -1 else 1
        } else {
            name.lowercase().compareTo(other.name.lowercase())
        }
    }
}

/** Type of remote server. */
enum class ServerType { FTP, SMB }

/** Saved connection configuration for FTP or SMB. */
data class ServerConfig(
    val name: String,
    val type: ServerType,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val share: String = ""  // SMB share name, ignored for FTP
)

/** FTP server (running on phone) configuration. */
data class FtpServerConfig(
    val port: Int = 2211,
    val username: String = "share",
    val password: String = "1234",
    val sharedPath: String = "/storage/emulated/0"
)

/** Storage volume info for local file browser. */
data class StorageVolume(
    val name: String,
    val path: String,
    val isRemovable: Boolean
)

/** File transfer progress. */
data class TransferInfo(
    val name: String,
    val transferred: Long,
    val total: Long,
    val isUpload: Boolean
)

/** A device discovered on the local network. */
data class DiscoveredDevice(
    val ip: String,
    val port: Int,
    val type: ServerType
)
