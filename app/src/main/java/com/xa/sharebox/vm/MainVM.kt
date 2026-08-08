package com.xa.sharebox.vm

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xa.sharebox.data.ServerStore
import com.xa.sharebox.model.DiscoveredDevice
import com.xa.sharebox.model.FileEntry
import com.xa.sharebox.model.FtpServerConfig
import com.xa.sharebox.model.ServerConfig
import com.xa.sharebox.model.ServerType
import com.xa.sharebox.model.StorageVolume
import com.xa.sharebox.model.TransferInfo
import com.xa.sharebox.net.FileSource
import com.xa.sharebox.net.FtpFileSource
import com.xa.sharebox.net.LocalFileSource
import com.xa.sharebox.net.SmbFileSource
import com.xa.sharebox.net.SmbShareLister
import com.xa.sharebox.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class MainVM(app: Application) : AndroidViewModel(app) {
    private val store = ServerStore(app)

    // --- UI State ---
    data class UiState(
        // Local files
        val localPath: String = "/storage/emulated/0",
        val localFiles: List<FileEntry> = emptyList(),
        val localLoading: Boolean = false,
        val storageVolumes: List<StorageVolume> = emptyList(),
        val hasStoragePermission: Boolean = false,

        // Remote (FTP/SMB) shared state
        val remoteServers: List<ServerConfig> = emptyList(),
        val remoteConnected: Boolean = false,
        val remoteConnectedType: ServerType? = null,
        val remoteSource: FileSource? = null,
        val remotePath: String = "",
        val remoteFiles: List<FileEntry> = emptyList(),
        val remoteLoading: Boolean = false,

        // FTP Server
        val serverRunning: Boolean = false,
        val ftpConfig: FtpServerConfig = FtpServerConfig(),
        val serverIpList: List<String> = emptyList(),

        // Messages
        val message: String? = null,
        val transferProgress: TransferInfo? = null,
        val showAddServerDialog: Boolean = false,
        val scanPort: String = "",
        val editingServerType: ServerType = ServerType.FTP,
        // File open/edit
        val openFileCache: OpenFileCache? = null,
        val showSaveBackDialog: Boolean = false,
        // LAN scanning
        val discoveredServers: List<DiscoveredDevice> = emptyList(),
        val isScanning: Boolean = false,
        val scanProgress: String = "",
        // Pre-filled add server dialog (from discovered device)
        val prefillHost: String = "",
        // SMB share listing
        val smbShareList: List<SmbShareLister.ShareInfo> = emptyList(),
        val smbShareLoading: Boolean = false,
        val smbShareHost: String = "",
        val smbSharePort: Int = 445,
        val smbShareUser: String = "",
        val smbSharePass: String = "",
        val showSmbShareDialog: Boolean = false
    )

    /** Tracks a file downloaded to cache for viewing/editing. */
    data class OpenFileCache(
        val tempFile: File,
        val remotePath: String,
        val name: String,
        val originalModifiedTime: Long
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var localSource: LocalFileSource? = null
    private val localSourceByPath = mutableMapOf<String, LocalFileSource>()

    init {
        refreshAll()
    }

    fun refreshAll() {
        checkPermissions()
        loadStorageVolumes()
        loadLocalFiles()
        loadServers()
        loadFtpConfig()
        updateIpList()
    }

    fun checkPermissions() {
        _state.value = _state.value.copy(hasStoragePermission = FileUtils.hasStoragePermission())
    }

    private fun loadStorageVolumes() {
        val vols = FileUtils.getStorageVolumes(getApplication())
        _state.value = _state.value.copy(storageVolumes = vols)
    }

    // ===== Local Files =====

    fun loadLocalFiles() {
        val path = _state.value.localPath
        _state.value = _state.value.copy(localLoading = true)
        viewModelScope.launch {
            val source = localSourceByPath.getOrPut(path) { LocalFileSource(path) }
            val files = source.list(path)
            _state.value = _state.value.copy(localFiles = files, localLoading = false)
        }
    }

    fun navigateLocal(path: String) {
        _state.value = _state.value.copy(localPath = path)
        loadLocalFiles()
    }

    fun goBackLocal(): Boolean {
        val current = _state.value.localPath
        val parent = File(current).parent
        if (parent != null && parent != "/" && File(parent).canRead()) {
            _state.value = _state.value.copy(localPath = parent)
            loadLocalFiles()
            return true
        }
        return false
    }

    fun deleteLocal(path: String) {
        viewModelScope.launch {
            val ok = FileUtils.deleteRecursive(File(path))
            if (ok) {
                _state.value = _state.value.copy(message = "已删除")
                loadLocalFiles()
            } else {
                _state.value = _state.value.copy(message = "删除失败")
            }
        }
    }

    fun mkdirLocal(name: String) {
        val newPath = File(_state.value.localPath, name).absolutePath
        viewModelScope.launch {
            val ok = File(newPath).mkdirs()
            if (ok) loadLocalFiles()
            _state.value = _state.value.copy(message = if (ok) "已创建" else "创建失败")
        }
    }

    // ===== Remote Servers =====

    private fun loadServers() {
        _state.value = _state.value.copy(remoteServers = store.getServers())
    }

    fun showAddServerDialog(type: ServerType) {
        _state.value = _state.value.copy(showAddServerDialog = true, editingServerType = type, prefillHost = "")
    }

    fun dismissAddServerDialog() {
        _state.value = _state.value.copy(showAddServerDialog = false, prefillHost = "")
    }

    fun addServer(config: ServerConfig) {
        store.addServer(config)
        loadServers()
        _state.value = _state.value.copy(showAddServerDialog = false, message = "已添加 ${config.name}")
    }

    fun removeServer(index: Int) {
        store.removeServer(index)
        loadServers()
        _state.value = _state.value.copy(message = "已删除")
    }

    fun connectServer(config: ServerConfig) {
        viewModelScope.launch {
            _state.value = _state.value.copy(remoteLoading = true)
            try {
                val source: FileSource = when (config.type) {
                    ServerType.FTP -> {
                        val ftp = FtpFileSource(config.host, config.port, config.username, config.password)
                        val ok = ftp.connect()
                        if (!ok) {
                            _state.value = _state.value.copy(remoteLoading = false, message = "FTP连接失败: ${ftp.connectError}")
                            return@launch
                        }
                        ftp
                    }
                    ServerType.SMB -> {
                        val smb = SmbFileSource(config.host, config.share, config.username, config.password, config.port)
                        val ok = smb.connect()
                        if (!ok) {
                            _state.value = _state.value.copy(remoteLoading = false, message = "SMB连接失败: ${smb.connectError}")
                            return@launch
                        }
                        smb
                    }
                }
                _state.value = _state.value.copy(
                    remoteConnected = true,
                    remoteConnectedType = config.type,
                    remoteSource = source,
                    remotePath = if (config.type == ServerType.FTP) "/" else "",
                    remoteLoading = false
                )
                loadRemoteFiles()
            } catch (e: Throwable) {
                _state.value = _state.value.copy(remoteLoading = false, message = "连接失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun loadRemoteFiles() {
        val path = _state.value.remotePath
        val source = _state.value.remoteSource ?: return
        _state.value = _state.value.copy(remoteLoading = true)
        viewModelScope.launch {
            try {
                val files = source.list(path)
                _state.value = _state.value.copy(remoteFiles = files, remoteLoading = false)
            } catch (e: Throwable) {
                _state.value = _state.value.copy(remoteLoading = false, message = "读取失败: ${e.message}")
            }
        }
    }

    fun navigateRemote(path: String) {
        _state.value = _state.value.copy(remotePath = path)
        loadRemoteFiles()
    }

    fun goBackRemote(): Boolean {
        val current = _state.value.remotePath
        val source = _state.value.remoteSource ?: return false

        val sep = if (source is SmbFileSource) "\\" else "/"
        if (current == sep || current.isEmpty()) return false

        val parent = current.substringBeforeLast(sep)
        val newPath = if (parent.isEmpty()) sep else parent
        _state.value = _state.value.copy(remotePath = newPath)
        loadRemoteFiles()
        return true
    }

    fun disconnectRemote() {
        _state.value.remoteSource?.close()
        _state.value = _state.value.copy(
            remoteConnected = false,
            remoteConnectedType = null,
            remoteSource = null,
            remoteFiles = emptyList(),
            remotePath = ""
        )
    }

    fun downloadRemoteFile(file: FileEntry) {
        val source = _state.value.remoteSource ?: return
        val localDir = File(_state.value.localPath)
        val localFile = File(localDir, file.name)
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(transferProgress = TransferInfo(file.name, 0, file.size, false))
                source.download(file.path, localFile) { transferred, total ->
                    _state.value = _state.value.copy(transferProgress = TransferInfo(file.name, transferred, total, false))
                }
                _state.value = _state.value.copy(transferProgress = null, message = "已下载到 ${localFile.absolutePath}")
                loadLocalFiles()
            } catch (e: Throwable) {
                _state.value = _state.value.copy(transferProgress = null, message = "下载失败: ${e.message}")
            }
        }
    }

    fun uploadToRemote(file: FileEntry) {
        val source = _state.value.remoteSource ?: return
        val localFile = File(file.path)
        val remotePath = if (_state.value.remotePath.endsWith("\\")) {
            _state.value.remotePath + file.name
        } else if (_state.value.remotePath.endsWith("/")) {
            _state.value.remotePath + file.name
        } else {
            val sep = if (source is SmbFileSource) "\\" else "/"
            _state.value.remotePath + sep + file.name
        }
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(transferProgress = TransferInfo(file.name, 0, file.size, true))
                source.upload(localFile, remotePath) { transferred, total ->
                    _state.value = _state.value.copy(transferProgress = TransferInfo(file.name, transferred, total, true))
                }
                _state.value = _state.value.copy(transferProgress = null, message = "已上传 ${file.name}")
                loadRemoteFiles()
            } catch (e: Throwable) {
                _state.value = _state.value.copy(transferProgress = null, message = "上传失败: ${e.message}")
            }
        }
    }

    /** Upload a file from a content URI (picked via SAF file picker) to the current remote directory. */
    fun uploadUriToRemote(uri: Uri, context: Context) {
        val source = _state.value.remoteSource ?: return
        viewModelScope.launch {
            try {
                // Get file name from URI
                val fileName = try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        cursor.moveToFirst()
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    }
                } catch (e: Exception) { null } ?: "file"

                // Copy URI content to temp file
                val tempFile = File(getApplication<Application>().cacheDir, "upload_$fileName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw java.io.IOException("无法读取文件")

                // Build remote path
                val currentPath = _state.value.remotePath
                val sep = if (source is SmbFileSource) "\\" else "/"
                val remotePath = if (currentPath.isEmpty() || currentPath == sep) {
                    fileName
                } else {
                    currentPath.trimEnd(sep.toCharArray().first()) + sep + fileName
                }

                _state.value = _state.value.copy(transferProgress = TransferInfo(fileName, 0, tempFile.length(), true))
                source.upload(tempFile, remotePath) { transferred, total ->
                    _state.value = _state.value.copy(transferProgress = TransferInfo(fileName, transferred, total, true))
                }
                _state.value = _state.value.copy(transferProgress = null, message = "已上传 $fileName")
                loadRemoteFiles()
                tempFile.delete()
            } catch (e: Throwable) {
                _state.value = _state.value.copy(transferProgress = null, message = "上传失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun deleteRemoteFile(file: FileEntry) {
        val source = _state.value.remoteSource ?: return
        viewModelScope.launch {
            val ok = source.delete(file.path)
            if (ok) {
                _state.value = _state.value.copy(message = "已删除")
                loadRemoteFiles()
            } else {
                _state.value = _state.value.copy(message = "删除失败")
            }
        }
    }

    fun mkdirRemote(name: String) {
        val source = _state.value.remoteSource ?: return
        val sep = if (source is SmbFileSource) "\\" else "/"
        val newPath = _state.value.remotePath.trimEnd(sep.toCharArray().first()) + sep + name
        viewModelScope.launch {
            val ok = source.mkdir(newPath)
            if (ok) loadRemoteFiles()
            _state.value = _state.value.copy(message = if (ok) "已创建" else "创建失败")
        }
    }

    // ===== Open / Edit remote file =====

    /** Download a remote file to cache and open it with an external app. */
    fun openRemoteFile(file: FileEntry, context: Context) {
        val source = _state.value.remoteSource ?: return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(transferProgress = TransferInfo(file.name, 0, file.size, false))
                val tempFile = File(getApplication<Application>().cacheDir, file.name)
                source.download(file.path, tempFile) { transferred, total ->
                    _state.value = _state.value.copy(transferProgress = TransferInfo(file.name, transferred, total, false))
                }
                _state.value = _state.value.copy(transferProgress = null)

                // Open with external app
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", tempFile
                )
                val mime = getMimeType(file.name)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "打开 ${file.name}"))

                // Cache for save-back check
                _state.value = _state.value.copy(
                    openFileCache = OpenFileCache(tempFile, file.path, file.name, tempFile.lastModified())
                )
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    transferProgress = null,
                    message = "打开失败: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    /** Called when app returns to foreground — check if the opened file was modified. */
    fun checkOpenFileModified() {
        val cache = _state.value.openFileCache ?: return
        val tempFile = cache.tempFile
        if (!tempFile.exists()) {
            _state.value = _state.value.copy(openFileCache = null)
            return
        }
        if (tempFile.lastModified() > cache.originalModifiedTime) {
            _state.value = _state.value.copy(showSaveBackDialog = true)
        }
    }

    /** Upload the modified file back to the remote server. */
    fun saveBackRemoteFile() {
        val cache = _state.value.openFileCache ?: return
        val source = _state.value.remoteSource ?: return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(
                    showSaveBackDialog = false,
                    transferProgress = TransferInfo(cache.name, 0, cache.tempFile.length(), true)
                )
                source.upload(cache.tempFile, cache.remotePath) { transferred, total ->
                    _state.value = _state.value.copy(transferProgress = TransferInfo(cache.name, transferred, total, true))
                }
                _state.value = _state.value.copy(
                    transferProgress = null,
                    message = "已保存回 ${cache.name}",
                    openFileCache = null
                )
                loadRemoteFiles()
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    transferProgress = null,
                    message = "保存失败: ${e.message}"
                )
            }
        }
    }

    fun dismissSaveBackDialog() {
        _state.value = _state.value.copy(showSaveBackDialog = false, openFileCache = null)
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return try {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        } catch (e: Exception) { "*/*" }
    }

    // ===== LAN Scanning =====

    /** Scan the local network for SMB (port 445) and FTP (port 21) servers. */
    fun scanLan(type: ServerType) {
        if (_state.value.isScanning) return
        val ports = if (type == ServerType.SMB) listOf(445) else listOf(21, 2211)
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isScanning = true,
                discoveredServers = emptyList(),
                scanProgress = "扫描中..."
            )
            try {
                val localIp = getLocalIpv4()
                if (localIp == null) {
                    _state.value = _state.value.copy(
                        isScanning = false,
                        message = "无法获取本机IP，请检查WiFi连接"
                    )
                    return@launch
                }
                val base = localIp.substringBeforeLast('.')
                _state.value = _state.value.copy(scanProgress = "扫描 $base.1-$base.254 ${ports.joinToString("/")}")

                val results = mutableListOf<DiscoveredDevice>()
                val semaphore = java.util.concurrent.Semaphore(30)

                withContext(Dispatchers.IO) {
                    val jobs = ports.flatMap { port -> (1..254).map { i -> port to i } }.map { (port, i) ->
                        async {
                            semaphore.acquire()
                            try {
                                val ip = "$base.$i"
                                if (isPortOpen(ip, port, 600)) {
                                    synchronized(results) {
                                        results.add(DiscoveredDevice(ip, port, type))
                                    }
                                    // Update UI as we find devices
                                    _state.value = _state.value.copy(
                                        discoveredServers = results.sortedBy { it.ip },
                                        scanProgress = "扫描中... 已发现 ${results.size} 台"
                                    )
                                }
                            } finally {
                                semaphore.release()
                            }
                        }
                    }
                    jobs.awaitAll()
                }

                _state.value = _state.value.copy(
                    isScanning = false,
                    scanProgress = "扫描完成，发现 ${results.size} 台设备",
                    message = if (results.isEmpty()) "未发现设备" else "发现 ${results.size} 台设备"
                )
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    isScanning = false,
                    scanProgress = "",
                    message = "扫描失败: ${e.message}"
                )
            }
        }
    }

    /** Start adding a server from a discovered device — pre-fills the dialog. */
    fun connectDiscovered(device: DiscoveredDevice) {
        if (device.type == ServerType.SMB) {
            // For SMB, list shares first
            _state.value = _state.value.copy(
                showSmbShareDialog = true,
                smbShareHost = device.ip,
                smbSharePort = device.port,
                smbShareUser = "",
                smbSharePass = "",
                smbShareList = emptyList(),
                smbShareLoading = false
            )
        } else {
            _state.value = _state.value.copy(
                showAddServerDialog = true,
                editingServerType = device.type,
                prefillHost = device.ip
            )
        }
    }

    /** Enumerate SMB shares on a host. */
    fun listSmbShares(host: String, port: Int, user: String, pass: String) {
        _state.value = _state.value.copy(smbShareLoading = true, smbShareList = emptyList())
        viewModelScope.launch {
            try {
                val shares = withContext(Dispatchers.IO) {
                    SmbShareLister.listShares(host, port, user, pass)
                }
                _state.value = _state.value.copy(
                    smbShareLoading = false,
                    smbShareList = shares,
                    smbShareUser = user,
                    smbSharePass = pass
                )
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    smbShareLoading = false,
                    message = "枚举共享失败: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    /** Connect to a specific SMB share from the list. */
    fun connectSmbShare(shareName: String) {
        val s = _state.value
        val config = ServerConfig(
            name = "${s.smbShareHost}\\$shareName",
            type = ServerType.SMB,
            host = s.smbShareHost,
            port = s.smbSharePort,
            username = s.smbShareUser,
            password = s.smbSharePass,
            share = shareName
        )
        _state.value = _state.value.copy(showSmbShareDialog = false)
        connectRemote(config)
    }

    fun dismissSmbShareDialog() {
        _state.value = _state.value.copy(showSmbShareDialog = false)
    }

    private fun getLocalIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) { null }
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.close()
            true
        } catch (e: Exception) { false }
    }

    // ===== FTP Server =====

    private fun loadFtpConfig() {
        _state.value = _state.value.copy(ftpConfig = store.getFtpServerConfig())
    }

    fun saveFtpConfig(config: FtpServerConfig) {
        store.saveFtpServerConfig(config)
        _state.value = _state.value.copy(ftpConfig = config, message = "已保存配置")
    }

    fun updateFtpConfig(config: FtpServerConfig) {
        _state.value = _state.value.copy(ftpConfig = config)
    }

    fun startFtpServer() {
        val config = _state.value.ftpConfig
        val ok = com.xa.sharebox.net.FtpServerService.start(getApplication(), config)
        if (ok) _state.value = _state.value.copy(serverRunning = true, message = "FTP服务已启动") else _state.value = _state.value.copy(message = "需要通知权限，请在系统设置中开启")
        updateIpList()
    }

    fun stopFtpServer() {
        com.xa.sharebox.net.FtpServerService.stop(getApplication())
        _state.value = _state.value.copy(serverRunning = false)
    }

    private fun updateIpList() {
        _state.value = _state.value.copy(serverIpList = FileUtils.getLocalIpAddresses())
    }

    // ===== Messages =====

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.remoteSource?.close()
    }
}
