package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.crypto.KdfAlgorithm
import com.example.crypto.VaultFormatConstants
import com.example.github.IncompleteAssetIssue
import com.example.github.IncompleteIssueType
import com.example.github.VaultDocumentRecord
import com.example.ui.VaultUiState
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun VaultToolsScreen(
    uiState: VaultUiState,
    initialTab: Int = 0, // 0=Sync, 1=Backup, 2=Security, 3=Help
    onSyncWithGitHub: () -> Unit,
    onRestoreSidecar: (IncompleteAssetIssue) -> Unit,
    onDeleteDocPermanently: (VaultDocumentRecord) -> Unit,
    onSetSessionToken: (String) -> Unit,
    onClearSessionToken: () -> Unit,
    onSetAutoLockMinutes: (Int) -> Unit,
    onLockNow: (Boolean) -> Unit,
    onChangePassword: (String, String) -> Unit,
    onRotateVaultKey: (String, String) -> Unit,
    onSetKdfSettings: (String, Int, Int) -> Unit,
    onSetThemeMode: (String) -> Unit,
    onSetFlagSecure: (Boolean) -> Unit,
    onExportConfigToUri: (Uri, String, String) -> Unit,
    onDismissOneTimeRecovery: () -> Unit
) {
    val s = uiState.strings
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }

    val exportConfigLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = uiState.pendingExportConfigJson ?: uiState.settings.cachedVaultConfigJson
        if (uri != null && !json.isNullOrBlank()) {
            onExportConfigToUri(uri, json, s.exportPublicConfigBackup)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            val labels = listOf(s.navSync, s.navBackup, s.navSecurity, s.navHelp)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                labels.forEachIndexed { idx, label ->
                    SegmentedButton(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = labels.size),
                        modifier = Modifier.testTag("tools_tab_$idx")
                    ) {
                        Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        when (selectedTab) {
            0 -> {
                // Sync & Storage Diagnostics
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(s.syncReportTitle, style = MaterialTheme.typography.titleMedium)
                                Button(
                                    onClick = onSyncWithGitHub,
                                    enabled = !uiState.isBusy,
                                    modifier = Modifier.testTag("tools_sync_button")
                                ) {
                                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.syncNow)
                                }
                            }
                            uiState.syncReport?.let { rep ->
                                Text("${s.newDocsCount}: ${rep.newCount}")
                                Text("${s.existingDocsCount}: ${rep.existingCount}")
                                Text("${s.incompletePairsCount}: ${rep.incompleteIssues.size}")
                                Text("${s.duplicatesCount}: ${rep.duplicateCount}")
                                Text("${s.invalidFilesCount}: ${rep.invalidCount}")
                                rep.releaseAssetCounts.forEach { (tag, count) ->
                                    Text(
                                        text = "$tag: $count / 1000 assets",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }

                val issues = uiState.syncReport?.incompleteIssues.orEmpty()
                if (issues.isNotEmpty()) {
                    item {
                        Text(s.incompleteAssetWarning, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    }
                    items(issues, key = { "${it.assetId}_${it.filename}" }) { issue ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${issue.filename} (${issue.releaseTag})",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "${issue.issueType.name} • ${formatFileSize(issue.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (issue.issueType == IncompleteIssueType.MISSING_SIDECAR) {
                                    OutlinedButton(onClick = { onRestoreSidecar(issue) }) {
                                        Icon(Icons.Default.Restore, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(s.restoreSidecarFromCache)
                                    }
                                }
                            }
                        }
                    }
                }

                // Storage breakdown sorted by size
                if (uiState.documents.isNotEmpty()) {
                    item {
                        Text(s.storageUsed, style = MaterialTheme.typography.titleMedium)
                    }
                    items(
                        uiState.documents.sortedByDescending { it.totalEncryptedBytes },
                        key = { "size_${it.assetId}" }
                    ) { doc ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(doc.descriptor.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${doc.releaseTag} • ${formatFileSize(doc.totalEncryptedBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (uiState.hasGitHubToken && !doc.isLocalOnlyImport) {
                                    OutlinedButton(
                                        onClick = { onDeleteDocPermanently(doc) },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = s.deleteAction, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                // Backup & Recovery
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(s.navBackup, style = MaterialTheme.typography.titleMedium)
                            }
                            Text(s.saveRecoveryKeyWarning, style = MaterialTheme.typography.bodySmall)
                            uiState.activeVaultConfig?.let { cfg ->
                                Text(
                                    text = "Vault ID: ${cfg.vaultId}\nConfig Version: v${cfg.configVersion}\nKDF: ${cfg.kdf.algorithm}",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Button(
                                onClick = {
                                    val ver = uiState.activeVaultConfig?.configVersion ?: 1
                                    exportConfigLauncher.launch("vault-config-v%04d.json".format(ver))
                                },
                                enabled = !uiState.settings.cachedVaultConfigJson.isNullOrBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(s.exportPublicConfigBackup)
                            }
                        }
                    }
                }
            }

            2 -> {
                // Security Settings
                item {
                    var tokenInput by remember { mutableStateOf("") }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(s.sessionTokenTitle, style = MaterialTheme.typography.titleMedium)
                                Surface(
                                    color = if (uiState.hasGitHubToken) MaterialTheme.colorScheme.tertiaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = if (uiState.hasGitHubToken) s.tokenActiveBadge else s.tokenAbsentBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Text(s.patSecurityWarning, style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = tokenInput,
                                onValueChange = { tokenInput = it },
                                label = { Text("github_pat_...") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("security_pat_input")
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (tokenInput.isNotBlank()) {
                                            onSetSessionToken(tokenInput)
                                            tokenInput = ""
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.saveTokenInSession)
                                }
                                OutlinedButton(
                                    onClick = onClearSessionToken,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(s.clearSessionToken)
                                }
                            }
                        }
                    }
                }

                // Auto-Lock & Lock Controls
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(s.autoLockTitle, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    5 to s.autoLock5Min,
                                    10 to s.autoLock10Min,
                                    15 to s.autoLock15Min,
                                    0 to s.autoLockManual
                                ).forEach { (mins, label) ->
                                    FilterChip(
                                        selected = uiState.settings.autoLockMinutes == mins,
                                        onClick = { onSetAutoLockMinutes(mins) },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { onLockNow(false) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.lockNow)
                                }
                                Button(
                                    onClick = { onLockNow(true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(s.clearSessionToken)
                                }
                            }
                        }
                    }
                }

                // Change Password & Rotate Master Key
                item {
                    var currentPwd by remember { mutableStateOf("") }
                    var newPwd by remember { mutableStateOf("") }
                    var confirmPwd by remember { mutableStateOf("") }
                    var showRotateConfirm by remember { mutableStateOf(false) }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(s.changePasswordTitle, style = MaterialTheme.typography.titleMedium)
                            Text(s.rotateVaultKeyWarning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

                            OutlinedTextField(
                                value = currentPwd,
                                onValueChange = { currentPwd = it },
                                label = { Text(s.enterMasterPassword) },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newPwd,
                                onValueChange = { newPwd = it },
                                label = { Text(s.newMasterPasswordLabel) },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = confirmPwd,
                                onValueChange = { confirmPwd = it },
                                label = { Text(s.confirmMasterPasswordLabel) },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    onChangePassword(currentPwd, newPwd)
                                    currentPwd = ""; newPwd = ""; confirmPwd = ""
                                },
                                enabled = currentPwd.isNotBlank() && newPwd.length >= 10 && newPwd == confirmPwd,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(s.changePasswordTitle)
                            }
                            OutlinedButton(
                                onClick = { showRotateConfirm = true },
                                enabled = currentPwd.isNotBlank() && newPwd.length >= 10 && newPwd == confirmPwd,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Security, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(s.rotateVaultKeyTitle)
                            }
                        }
                    }

                    if (showRotateConfirm) {
                        AlertDialog(
                            onDismissRequest = { showRotateConfirm = false },
                            title = { Text(s.rotateVaultKeyTitle) },
                            text = { Text(s.rotateVaultKeyWarning) },
                            confirmButton = {
                                Button(onClick = {
                                    showRotateConfirm = false
                                    onRotateVaultKey(currentPwd, newPwd)
                                    currentPwd = ""; newPwd = ""; confirmPwd = ""
                                }) {
                                    Text(s.proceedLabel)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRotateConfirm = false }) { Text(s.cancelLabel) }
                            }
                        )
                    }
                }

                // KDF, Theme & Screen Shield
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(s.kdfSettingsTitle, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = uiState.settings.kdfAlgorithm == KdfAlgorithm.ARGON2ID.id &&
                                        uiState.settings.argon2MemoryKiB == VaultFormatConstants.ARGON2_MIN_MEMORY_KIB,
                                    onClick = {
                                        onSetKdfSettings(
                                            KdfAlgorithm.ARGON2ID.id,
                                            VaultFormatConstants.ARGON2_MIN_MEMORY_KIB,
                                            2
                                        )
                                    },
                                    label = { Text("Argon2id (19 MiB)") }
                                )
                                FilterChip(
                                    selected = uiState.settings.kdfAlgorithm == KdfAlgorithm.ARGON2ID.id &&
                                        uiState.settings.argon2MemoryKiB > VaultFormatConstants.ARGON2_MIN_MEMORY_KIB,
                                    onClick = { onSetKdfSettings(KdfAlgorithm.ARGON2ID.id, 32768, 3) },
                                    label = { Text("Argon2id (32 MiB)") }
                                )
                                FilterChip(
                                    selected = uiState.settings.kdfAlgorithm == KdfAlgorithm.PBKDF2_SHA256.id,
                                    onClick = {
                                        onSetKdfSettings(
                                            KdfAlgorithm.PBKDF2_SHA256.id,
                                            VaultFormatConstants.ARGON2_MIN_MEMORY_KIB,
                                            VaultFormatConstants.PBKDF2_MIN_ITERATIONS
                                        )
                                    },
                                    label = { Text("PBKDF2 (600k)") }
                                )
                            }

                            Text(s.themeTitle, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    "system" to s.themeSystem,
                                    "light" to s.themeLight,
                                    "dark" to s.themeDark
                                ).forEach { (mode, label) ->
                                    FilterChip(
                                        selected = uiState.settings.themeMode == mode,
                                        onClick = { onSetThemeMode(mode) },
                                        label = { Text(label) }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(s.screenPrivacyToggle, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        s.screenPrivacySubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = uiState.settings.flagSecureEnabled,
                                    onCheckedChange = onSetFlagSecure
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                // Help & Guides
                item {
                    HelpGuideCard(s.helpGitHubSetupTitle, s.helpGitHubSetupBody)
                }
                item {
                    HelpGuideCard(s.helpTokenGuideTitle, s.helpTokenGuideBody)
                }
                item {
                    HelpGuideCard(s.helpRecoveryTitle, s.helpRecoveryBody)
                }
                item {
                    HelpGuideCard(s.helpThreatModelTitle, s.helpThreatModelBody)
                }
                item {
                    HelpGuideCard(s.helpTroubleshootingTitle, s.helpTroubleshootingBody)
                }
            }
        }
    }

    if (!uiState.oneTimeRecoveryKey.isNullOrBlank()) {
        OneTimeRecoveryKeyDialog(
            recoveryKey = uiState.oneTimeRecoveryKey,
            pendingConfigJson = uiState.pendingExportConfigJson,
            uiState = uiState,
            onExportTextUri = onExportConfigToUri,
            onConfirmSaved = onDismissOneTimeRecovery
        )
    }
}

@Composable
private fun HelpGuideCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Help, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
