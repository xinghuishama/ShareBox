package com.xa.sharebox.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xa.sharebox.model.FtpServerConfig
import com.xa.sharebox.util.FileUtils
import com.xa.sharebox.vm.MainVM

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(vm: MainVM, state: MainVM.UiState) {
    val context = LocalContext.current
    val config = state.ftpConfig
    var port by remember(config) { mutableStateOf(config.port.toString()) }
    var user by remember(config) { mutableStateOf(config.username) }
    var pass by remember(config) { mutableStateOf(config.password) }
    var path by remember(config) { mutableStateOf(config.sharedPath) }

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
        modifier = Modifier.fillMaxSize().padding(16.dp),
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
    }
}
