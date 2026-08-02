package com.xa.sharebox.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return if (unitIndex == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", size, units[unitIndex])
    }

    fun formatDate(timestamp: Long): String {
        if (timestamp <= 0) return ""
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    /** Get all storage volumes including internal, SD card, and USB OTG (FAT32/exFAT/NTFS). */
    fun getStorageVolumes(context: Context): List<com.xa.sharebox.model.StorageVolume> {
        val result = mutableListOf<com.xa.sharebox.model.StorageVolume>()
        val seenPaths = mutableSetOf<String>()

        // Internal storage
        val internalPath = Environment.getExternalStorageDirectory().absolutePath
        result.add(com.xa.sharebox.model.StorageVolume("内部存储", internalPath, false))
        seenPaths.add(internalPath)

        // Use StorageManager for additional volumes (SD card, USB OTG)
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val volumes = sm.storageVolumes
            for (vol in volumes) {
                val dir = vol.directory ?: continue
                val path = dir.absolutePath
                if (path in seenPaths) continue
                seenPaths.add(path)
                val name = vol.getDescription(context)
                result.add(com.xa.sharebox.model.StorageVolume(name, path, vol.isRemovable))
            }
        } catch (e: Exception) { /* ignore */ }

        // Scan common mount points for USB OTG drives (FAT32/exFAT/NTFS)
        val mountDirs = listOf("/storage", "/mnt/media_rw", "/mnt/usb", "/mnt/external_sd")
        for (mountDirPath in mountDirs) {
            try {
                val mountDir = File(mountDirPath)
                if (!mountDir.isDirectory || !mountDir.canRead()) continue
                mountDir.listFiles()?.forEach { dir ->
                    if (!dir.isDirectory) return@forEach
                    val path = dir.absolutePath
                    if (path in seenPaths) return@forEach
                    // Skip self-referencing or emulated paths
                    if (path.contains("/self") || path.contains("/emulated")) return@forEach
                    // Check if it's a real mount point with available space
                    try {
                        val stat = StatFs(path)
                        if (stat.blockCountLong > 0) {
                            seenPaths.add(path)
                            val label = when {
                                path.contains("usb", ignoreCase = true) -> "USB存储"
                                path.contains("sd", ignoreCase = true) -> "SD卡"
                                path.contains("media_rw", ignoreCase = true) -> "外部存储"
                                else -> dir.name
                            }
                            result.add(com.xa.sharebox.model.StorageVolume(label, path, true))
                        }
                    } catch (e: Exception) { /* not a valid mount point */ }
                }
            } catch (e: Exception) { /* ignore */ }
        }

        return result
    }

    /** Get available space at path. */
    fun getAvailableSpace(path: String): Long {
        return try {
            val stat = StatFs(path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            0
        }
    }

    /** Open a file using the system default app. */
    fun openFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, getMimeType(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // No app to handle this file type
        }
    }

    private fun getMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "txt", "log" -> "text/plain"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "mp3", "flac", "wav", "aac", "ogg" -> "audio/*"
            "mp4", "avi", "mkv", "mov", "3gp" -> "video/*"
            "zip", "rar", "7z", "tar", "gz" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }

    /** Check if the app has All Files Access permission. */
    fun hasStoragePermission(): Boolean {
        return Environment.isExternalStorageManager()
    }

    /** Recursively copy a directory. */
    fun copyDirectory(source: File, target: File): Boolean {
        if (!source.exists()) return false
        if (source.isDirectory) {
            target.mkdirs()
            val children = source.listFiles() ?: return false
            for (child in children) {
                copyDirectory(child, File(target, child.name))
            }
            return true
        } else {
            target.parentFile?.mkdirs()
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return true
        }
    }

    /** Recursively delete a file or directory. */
    fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        return file.delete()
    }

    /** Get the phone's IP addresses on WiFi/network. */
    fun getLocalIpAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        result.add(addr.hostAddress ?: "")
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return result
    }
}
