package com.xa.sharebox.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xa.sharebox.model.FtpServerConfig
import com.xa.sharebox.util.FileUtils
import com.xa.sharebox.vm.MainVM
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(vm: MainVM, state: MainVM.UiState) {
    val context = LocalContext.current
    val config = state.ftpConfig
    var port by remember(config) { mutableStateOf(config.port.toString()) }
    var user by remember(config) { mutableStateOf(config.username) }
    var pass by remember(config) { mutableStateOf(config.password) }
    var path by remember(config) { mutableStateOf(config.sharedPath) }
    var showLogDialog by remember { mutableStateOf(false) }

    val requestNotifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startFtpServer()
    }

    fun startServer() {
        if (pass.isBlank()) {
            pass = com.xa.sharebox.data.ServerStore.generateRandomPassword()
        }
        vm.saveFtpConfig(
            FtpServerConfig(
                port = port.toIntOrNull() ?: 2211,
                username = user,
                password = pass,
                sharedPath = path
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startFtpServer()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    if (state.serverRunning) "FTP 服务运行中" else "FTP 服务未启动",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.serverRunning) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.serverRunning) {
                    Spacer(Modifier.padding(4.dp))
                    val ips = state.serverIpList.ifEmpty { listOf("unknown") }
                    ips.forEach { ip ->
                        Text(
                            "ftp://$ip:${config.port}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        "共享目录: ${config.sharedPath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Config inputs
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("配置", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("端口") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("用户名") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    label = { Text("密码") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = path, onValueChange = { path = it },
                    label = { Text("共享目录") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!state.serverRunning) {
                Button(
                    onClick = { startServer() },
                    modifier = Modifier.weight(1f)
                ) { Text("启动服务") }
            } else {
                Button(
                    onClick = { vm.stopFtpServer() },
                    modifier = Modifier.weight(1f)
                ) { Text("停止服务") }
            }
            OutlinedButton(
                onClick = {
                    if (pass.isBlank()) {
                        pass = com.xa.sharebox.data.ServerStore.generateRandomPassword()
                    }
                    vm.saveFtpConfig(
                        FtpServerConfig(
                            port = port.toIntOrNull() ?: 2211,
                            username = user,
                            password = pass,
                            sharedPath = path
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text("保存配置") }
        }

        // Help text
        Spacer(Modifier.padding(4.dp))
        Text(
            "使用方法：\n" +
            "1. 确保手机和电脑在同一WiFi\n" +
            "2. 设置共享目录（默认内部存储根目录）\n" +
            "3. 点击「启动服务」\n" +
            "4. 在电脑文件管理器地址栏输入上面的 ftp:// 地址\n" +
            "5. 输入用户名和密码即可访问",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // View logs button
        OutlinedButton(
            onClick = { showLogDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("查看日志") }
    }

    if (showLogDialog) {
        LogViewerDialog(
            context = context,
            onDismiss = { showLogDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LogViewerDialog(
    context: android.content.Context,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var refreshTrigger by remember { mutableStateOf(0) }

    val logFiles = remember {
        listOf(
            File(context.filesDir, "ftp_debug.log"),
            File(context.filesDir, "ftp_slf4j.log")
        )
    }
    val logNames = remember { listOf("调试日志", "slf4j日志") }

    // Read log content — refresh when tab changes or refreshTrigger changes
    val logLines = remember(selectedTab, refreshTrigger) {
        val file = logFiles[selectedTab]
        if (file.exists() && file.length() > 0) {
            try {
                file.readText()
                    .split("\n")
                    .filter { it.isNotBlank() }
                    .let { if (it.size > 2000) it.takeLast(2000) else it }  // Keep last 2000 lines
            } catch (e: Exception) {
                listOf("读取日志失败: ${e.message}")
            }
        } else {
            emptyList()
        }
    }

    val logSize = remember(selectedTab, refreshTrigger) {
        val f = logFiles[selectedTab]
        if (f.exists()) FileUtils.formatSize(f.length()) else "0 B"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Tab bar
                TabRow(selectedTabIndex = selectedTab) {
                    logNames.forEachIndexed { index, name ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(name) }
                        )
                    }
                }

                // Log content
                if (logLines.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无日志",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        items(logLines) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = {
                                            copyToClipboard(context, line)
                                        }
                                    )
                                    .padding(vertical = 1.dp)
                            )
                        }
                    }
                }

                // Bottom bar: size info + actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${logLines.size} 行 · $logSize",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row {
                        TextButton(
                            onClick = {
                                copyToClipboard(context, logLines.joinToString("\n"))
                            }
                        ) { Text("复制") }

                        TextButton(
                            onClick = {
                                logFiles[selectedTab].let { if (it.exists()) it.writeText("") }
                                refreshTrigger++
                            }
                        ) { Text("清除") }

                        TextButton(onClick = { refreshTrigger++ }) { Text("刷新") }

                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("log", text))
    Toast.makeText(context, "已复制 ${text.lineSequence().count()} 行", Toast.LENGTH_SHORT).show()
}
