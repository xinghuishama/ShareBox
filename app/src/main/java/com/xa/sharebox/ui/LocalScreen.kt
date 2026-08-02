package com.xa.sharebox.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xa.sharebox.model.FileEntry
import com.xa.sharebox.model.StorageVolume
import com.xa.sharebox.util.FileUtils
import com.xa.sharebox.vm.MainVM

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalScreen(vm: MainVM, state: MainVM.UiState) {
    val context = LocalContext.current
    var showNewFolder by remember { mutableStateOf(false) }
    var showStoragePicker by remember { mutableStateOf(false) }

    if (!state.hasStoragePermission) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "需要存储访问权限",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.padding(8.dp))
            Text(
                "ShareBox 需要「所有文件访问权限」才能浏览和管理本地文件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.padding(16.dp))
            Button(onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
            }) {
                Text("授予权限")
            }
        }
        return
    }

    androidx.compose.material3.Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewFolder = true }) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "新建文件夹")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Storage volume selector
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.storageVolumes.take(4).forEach { vol ->
                    AssistChip(
                        onClick = { vm.navigateLocal(vol.path) },
                        label = { Text(vol.name, maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.padding(2.dp)) }
                    )
                }
            }

            // Path bar with back
            PathBar(
                path = state.localPath,
                onBack = { vm.goBackLocal() }
            )

            // File list
            if (state.localLoading) {
                LoadingBox()
            } else if (state.localFiles.isEmpty()) {
                EmptyState("空文件夹")
            } else {
                FileList(
                    files = state.localFiles,
                    onFileClick = { file ->
                        if (file.isDirectory) {
                            vm.navigateLocal(file.path)
                        } else {
                            FileUtils.openFile(context, java.io.File(file.path))
                        }
                    },
                    onMenuClick = { file -> vm.deleteLocal(file.path) }
                )
            }
        }
    }

    if (showNewFolder) {
        NewFolderDialog(
            onCreate = { name -> vm.mkdirLocal(name); showNewFolder = false },
            onDismiss = { showNewFolder = false }
        )
    }
}
