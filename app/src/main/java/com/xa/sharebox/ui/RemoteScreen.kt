package com.xa.sharebox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xa.sharebox.model.FileEntry
import com.xa.sharebox.model.ServerConfig
import com.xa.sharebox.model.ServerType
import com.xa.sharebox.util.FileUtils
import com.xa.sharebox.vm.MainVM
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: MainVM, state: MainVM.UiState, isSmb: Boolean) {
    val serverType = if (isSmb) ServerType.SMB else ServerType.FTP
    var showNewFolder by remember { mutableStateOf(false) }
    var clickedFile by remember { mutableStateOf<FileEntry?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Check if opened file was modified when returning to foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.checkOpenFileModified()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isConnected = state.remoteConnected && state.remoteConnectedType == serverType
    if (!isConnected) {
        // Server list view
        val servers = state.remoteServers.filter { it.type == serverType }
        val discovered = state.discoveredServers.filter { it.type == serverType }
        Scaffold(
            floatingActionButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Scan button
                    FloatingActionButton(
                        onClick = { vm.scanLan(serverType) },
                        containerColor = if (state.isScanning)
                            MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                    ) {
                        if (state.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Computer, contentDescription = "扫描局域网")
                        }
                    }
                    // Add button
                    FloatingActionButton(onClick = {
                        vm.showAddServerDialog(serverType)
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "添加服务器")
                    }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Scan progress bar
                if (state.isScanning) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = state.scanProgress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                } else if (state.scanProgress.isNotEmpty()) {
                    Text(
                        text = state.scanProgress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                if (servers.isEmpty() && discovered.isEmpty() && !state.isScanning) {
                    EmptyState(
                        if (isSmb) "还没有SMB服务器\n点左下角扫描局域网\n或点+手动添加"
                        else "还没有FTP服务器\n点左下角扫描局域网\n或点+手动添加"
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Discovered devices
                        if (discovered.isNotEmpty()) {
                            item {
                                Text(
                                    "局域网设备",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                                )
                            }
                            items(discovered) { device ->
                                DiscoveredDeviceCard(
                                    device = device,
                                    onConnect = { vm.connectDiscovered(device) }
                                )
                            }
                            item { Spacer(Modifier.height(4.dp)) }
                        }
                        // Saved servers
                        if (servers.isNotEmpty()) {
                            item {
                                Text(
                                    "已保存",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                            items(servers) { server ->
                                ServerCard(
                                    server = server,
                                    onConnect = { vm.connectServer(server) },
                                    onDelete = { vm.removeServer(state.remoteServers.indexOf(server)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        // File browser view
        val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                vm.uploadUriToRemote(uri, context)
            }
        }

        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { vm.disconnectRemote() }) {
                        Icon(Icons.Default.Close, contentDescription = "断开")
                    }
                    Text(
                        text = state.remoteSource?.displayName ?: "",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // New folder button
                    IconButton(onClick = { showNewFolder = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "新建文件夹")
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { pickFile.launch(arrayOf("*/*")) },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Upload, contentDescription = "上传文件")
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Path bar with back
                PathBar(
                    path = state.remotePath,
                    onBack = { vm.goBackRemote() }
                )

                // File list
                if (state.remoteLoading) {
                    LoadingBox()
                } else if (state.remoteFiles.isEmpty()) {
                    EmptyState("空目录 - 点右下角按钮上传文件")
                } else {
                    FileList(
                        files = state.remoteFiles,
                        onFileClick = { file ->
                            if (file.isDirectory) {
                                vm.navigateRemote(file.path)
                            } else {
                                clickedFile = file
                            }
                        },
                        onMenuClick = { file -> clickedFile = file }
                    )
                }
            }
        }
    }

    if (showNewFolder) {
        NewFolderDialog(
            onCreate = { name -> vm.mkdirRemote(name); showNewFolder = false },
            onDismiss = { showNewFolder = false }
        )
    }

    // File action dialog (open / download / delete)
    clickedFile?.let { file ->
        AlertDialog(
            onDismissRequest = { clickedFile = null },
            title = { Text(file.name) },
            text = {
                Column {
                    Text("大小: ${FileUtils.formatSize(file.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (file.permission != null) {
                        Text("权限: ${file.permission}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        clickedFile = null
                        vm.openRemoteFile(file, context)
                    }) { Text("打开") }
                    TextButton(onClick = {
                        clickedFile = null
                        vm.downloadRemoteFile(file)
                    }) { Text("下载") }
                    TextButton(onClick = {
                        clickedFile = null
                        vm.deleteRemoteFile(file)
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(onClick = { clickedFile = null }) { Text("取消") }
            }
        )
    }

    // Save back dialog (when file was edited externally)
    if (state.showSaveBackDialog) {
        AlertDialog(
            onDismissRequest = { vm.dismissSaveBackDialog() },
            title = { Text("文件已修改") },
            text = { Text("检测到文件已被修改，是否上传回服务器？") },
            confirmButton = {
                TextButton(onClick = { vm.saveBackRemoteFile() }) { Text("上传") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissSaveBackDialog() }) { Text("不保存") }
            }
        )
    }

    // Add server dialog
    if (state.showAddServerDialog && state.editingServerType == serverType) {
        AddServerDialog(
            isSmb = isSmb,
            prefillHost = state.prefillHost,
            onAdd = { config -> vm.addServer(config) },
            onDismiss = { vm.dismissAddServerDialog() }
        )
    }

    // SMB share picker dialog
    if (state.showSmbShareDialog && isSmb) {
        SmbSharePickerDialog(
            host = state.smbShareHost,
            port = state.smbSharePort,
            shares = state.smbShareList,
            loading = state.smbShareLoading,
            onListShares = { user, pass -> vm.listSmbShares(state.smbShareHost, state.smbSharePort, user, pass) },
            onPickShare = { shareName -> vm.connectSmbShare(shareName) },
            onDismiss = { vm.dismissSmbShareDialog() }
        )
    }
}

@Composable
fun SmbSharePickerDialog(
    host: String,
    port: Int,
    shares: List<com.xa.sharebox.net.SmbShareLister.ShareInfo>,
    loading: Boolean,
    onListShares: (String, String) -> Unit,
    onPickShare: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var hasLoaded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SMB 共享 — $host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = user, onValueChange = { user = it; hasLoaded = false },
                    label = { Text("用户名 (可空)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it; hasLoaded = false },
                    label = { Text("密码 (可空)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onListShares(user, pass); hasLoaded = true },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("列出共享")
                    }
                }
                if (shares.isNotEmpty()) {
                    Text("发现 ${shares.size} 个共享：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    shares.forEach { share ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickShare(share.name) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(share.name, style = MaterialTheme.typography.bodyMedium)
                                if (share.comment.isNotEmpty()) {
                                    Text(share.comment,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else if (hasLoaded && !loading) {
                    Text("未发现共享，或需要认证",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun ServerCard(
    server: ServerConfig,
    onConnect: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (server.type == ServerType.FTP) Icons.Default.Cloud else Icons.Default.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${server.host}:${server.port}" +
                        if (server.type == ServerType.SMB && server.share.isNotEmpty()) "/${server.share}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onConnect) { Text("连接") }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
fun DiscoveredDeviceCard(
    device: com.xa.sharebox.model.DiscoveredDevice,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.ip, style = MaterialTheme.typography.titleMedium)
                Text(
                    "端口 ${device.port} · 点击连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onConnect) { Text("连接") }
        }
    }
}

@Composable
fun AddServerDialog(
    isSmb: Boolean,
    prefillHost: String = "",
    onAdd: (ServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(prefillHost) }
    var host by remember { mutableStateOf(prefillHost) }
    var port by remember { mutableStateOf(if (isSmb) "445" else "21") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isSmb) "添加SMB服务器" else "添加FTP服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("主机地址") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("端口") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isSmb) {
                    OutlinedTextField(
                        value = share, onValueChange = { share = it },
                        label = { Text("共享名") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("用户名 (可空)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    label = { Text("密码 (可空)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && host.isNotBlank()) {
                    onAdd(
                        ServerConfig(
                            name = name,
                            type = if (isSmb) ServerType.SMB else ServerType.FTP,
                            host = host,
                            port = port.toIntOrNull() ?: if (isSmb) 445 else 21,
                            username = user,
                            password = pass,
                            share = share
                        )
                    )
                }
            }) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
