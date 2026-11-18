package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.VaultUiState
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LockAccentGold
import com.example.ui.theme.LockCardSurface
import com.example.ui.theme.LockGradientBottom
import com.example.ui.theme.LockGradientMid
import com.example.ui.theme.LockGradientTop

private enum class LockScreenMode {
    LOCKED,
    RECOVERY,
    SETUP_NEW,
    CONNECT_REPO
}

@Composable
fun LockAndSetupScreen(
    uiState: VaultUiState,
    onToggleLanguage: () -> Unit,
    onUnlockWithPassword: (String) -> Unit,
    onConnectGitHubRepo: (String, String) -> Unit,
    onInitializeVault: (owner: String, repo: String, password: String, pat: String, publish: Boolean) -> Unit,
    onRecoverWithKey: (recoveryKey: String, newPassword: String, pat: String) -> Unit,
    onExportTextUri: (android.net.Uri, String, String) -> Unit,
    onDismissRecoveryCreatedDialog: () -> Unit
) {
    val s = uiState.strings
    val context = LocalContext.current
    val hasConfiguredVault = uiState.activeVaultConfig != null ||
        (uiState.settings.githubOwner.isNotBlank() && uiState.settings.githubRepo.isNotBlank())

    var mode by remember(hasConfiguredVault) {
        mutableStateOf(if (hasConfiguredVault) LockScreenMode.LOCKED else LockScreenMode.SETUP_NEW)
    }

    if (mode != LockScreenMode.LOCKED && hasConfiguredVault) {
        BackHandler { mode = LockScreenMode.LOCKED }
    }

    val darkFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color(0xFFE2E8F0),
        focusedBorderColor = LockAccentGold,
        unfocusedBorderColor = Color(0xFF334155),
        focusedLabelColor = LockAccentGold,
        unfocusedLabelColor = Color(0xFF94A3B8),
        cursorColor = LockAccentGold
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(LockGradientTop, LockGradientMid, LockGradientBottom)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Language & Status Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "AES-256-GCM",
                            tint = LockAccentGold,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "AES-256-GCM • Argon2id",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onToggleLanguage,
                    modifier = Modifier.testTag("lock_language_toggle")
                ) {
                    Text(
                        text = if (uiState.isBangla) "B/E • বাংলা" else "B/E • EN",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Distinct Vault Emblem
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .border(2.dp, LockAccentGold.copy(alpha = 0.65f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_vault_foreground),
                    contentDescription = s.appTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = s.appTitle,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = s.appSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )

            if (uiState.settings.githubOwner.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = Color(0xFF16273B),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "${uiState.settings.githubOwner}/${uiState.settings.githubRepo}" +
                            (uiState.activeVaultConfig?.let { " • v${it.configVersion}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = Color(0xFF7CC4FA),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                colors = CardDefaults.cardColors(containerColor = LockCardSurface),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    when (mode) {
                        LockScreenMode.LOCKED -> {
                            var password by remember { mutableStateOf("") }
                            var showPassword by remember { mutableStateOf(false) }

                            Text(
                                text = s.enterMasterPassword,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Spacer(Modifier.height(14.dp))

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(s.enterMasterPassword) },
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None
                                else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (!uiState.isBusy && password.isNotEmpty()) {
                                            onUnlockWithPassword(password)
                                        }
                                    }
                                ),
                                trailingIcon = {
                                    IconButton(
                                        onClick = { showPassword = !showPassword },
                                        modifier = Modifier.testTag("toggle_password_visibility")
                                    ) {
                                        Icon(
                                            imageVector = if (showPassword) Icons.Default.VisibilityOff
                                            else Icons.Default.Visibility,
                                            contentDescription = if (showPassword) s.hidePassword else s.showPassword,
                                            tint = Color(0xFFCBD5E1)
                                        )
                                    }
                                },
                                colors = darkFieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("master_password_input")
                            )

                            if (!uiState.authError.isNullOrBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Surface(
                                    color = Color(0xFF451212),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(
                                        text = uiState.authError,
                                        color = Color(0xFFFCA5A5),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(18.dp))

                            Button(
                                onClick = { onUnlockWithPassword(password) },
                                enabled = !uiState.isBusy && password.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LockAccentGold,
                                    contentColor = Color(0xFF1F1300)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("unlock_library_button")
                            ) {
                                if (uiState.isBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF1F1300)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(uiState.busyMessage ?: s.unlockingProgress)
                                } else {
                                    Icon(Icons.Default.LockOpen, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(s.unlockLibrary, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { mode = LockScreenMode.RECOVERY },
                                    modifier = Modifier.testTag("open_recovery_mode_button")
                                ) {
                                    Icon(
                                        Icons.Default.Key,
                                        contentDescription = null,
                                        tint = LockAccentGold,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.useRecoveryKey, color = LockAccentGold)
                                }

                                TextButton(
                                    onClick = { mode = LockScreenMode.CONNECT_REPO },
                                    modifier = Modifier.testTag("switch_repo_button")
                                ) {
                                    Text(s.connectExistingVault, color = Color(0xFF7CC4FA))
                                }
                            }

                            TextButton(
                                onClick = { mode = LockScreenMode.SETUP_NEW },
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .testTag("go_to_setup_vault_button")
                            ) {
                                Text(s.setupNewVault, color = Color(0xFF94A3B8))
                            }
                        }

                        LockScreenMode.CONNECT_REPO -> {
                            var owner by remember { mutableStateOf(uiState.settings.githubOwner) }
                            var repo by remember { mutableStateOf(uiState.settings.githubRepo) }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { mode = LockScreenMode.LOCKED }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = Color.White
                                    )
                                }
                                Text(
                                    text = s.connectExistingVault,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = owner,
                                onValueChange = { owner = it },
                                label = { Text(s.githubOwnerLabel) },
                                singleLine = true,
                                colors = darkFieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("connect_owner_input")
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = repo,
                                onValueChange = { repo = it },
                                label = { Text(s.githubRepoLabel) },
                                singleLine = true,
                                colors = darkFieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("connect_repo_input")
                            )
                            if (!uiState.authError.isNullOrBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(uiState.authError, color = Color(0xFFFCA5A5), style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { onConnectGitHubRepo(owner, repo) },
                                enabled = !uiState.isBusy && owner.isNotBlank() && repo.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = LockAccentGold, contentColor = Color(0xFF1F1300)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("connect_repo_submit")
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(s.connectExistingVault, fontWeight = FontWeight.Bold)
                            }
                        }

                        LockScreenMode.RECOVERY -> {
                            var recKey by remember { mutableStateOf("") }
                            var newPass by remember { mutableStateOf("") }
                            var confirmPass by remember { mutableStateOf("") }
                            var pat by remember { mutableStateOf("") }
                            var validationErr by remember { mutableStateOf<String?>(null) }

                            val recFilePicker = rememberLauncherForActivityResult(
                                ActivityResultContracts.OpenDocument()
                            ) { uri ->
                                if (uri != null) {
                                    runCatching {
                                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                                    }.getOrNull()?.let { text ->
                                        val keyLine = text.lines()
                                            .firstOrNull { it.contains("-") && it.trim().length >= 64 }
                                            ?: text
                                        recKey = keyLine.trim()
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { mode = LockScreenMode.LOCKED }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = Color.White
                                    )
                                }
                                Text(
                                    text = s.recoveryModeTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = s.lostBothKeysWarning,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFCD34D)
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = recKey,
                                onValueChange = { recKey = it },
                                label = { Text(s.enterRecoveryKeyLabel) },
                                colors = darkFieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("recovery_key_input")
                            )
                            TextButton(
                                onClick = { recFilePicker.launch(arrayOf("text/plain", "*/*")) },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Import .txt", color = Color(0xFF7CC4FA))
                            }
                            OutlinedTextField(
                                value = newPass,
                                onValueChange = { newPass = it },
                                label = { Text(s.newMasterPasswordLabel) },
                                visualTransformation = PasswordVisualTransformation(),
                                colors = darkFieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("recovery_new_password_input")
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = confirmPass,
                                onValueChange = { confirmPass = it },
                                label = { Text(s.confirmMasterPasswordLabel) },
                                visualTransformation = PasswordVisualTransformation(),
                                colors = darkFieldColors,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = pat,
                                onValueChange = { pat = it },
                                label = { Text(s.optionalPatForPublish) },
                                visualTransformation = PasswordVisualTransformation(),
                                colors = darkFieldColors,
                                modifier = Modifier.fillMaxWidth()
                            )
                            val err = validationErr ?: uiState.authError
                            if (!err.isNullOrBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(err, color = Color(0xFFFCA5A5), style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    when {
                                        newPass.length < 10 -> validationErr = s.passwordTooShortError
                                        newPass != confirmPass -> validationErr = s.passwordMismatchError
                                        else -> {
                                            validationErr = null
                                            onRecoverWithKey(recKey, newPass, pat)
                                        }
                                    }
                                },
                                enabled = !uiState.isBusy && recKey.isNotBlank() && newPass.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = LockAccentGold, contentColor = Color(0xFF1F1300)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("recovery_submit_button")
                            ) {
                                Text(s.recoverAndResetPassword, fontWeight = FontWeight.Bold)
                            }
                        }

                        LockScreenMode.SETUP_NEW -> {
                            var owner by remember { mutableStateOf(uiState.settings.githubOwner) }
                            var repo by remember { mutableStateOf(uiState.settings.githubRepo.ifBlank { "personal-pdf-vault" }) }
                            var pass by remember { mutableStateOf("") }
                            var confirm by remember { mutableStateOf("") }
                            var pat by remember { mutableStateOf("") }
                            var publishDirect by remember { mutableStateOf(false) }
                            var showPass by remember { mutableStateOf(false) }
                            var localError by remember { mutableStateOf<String?>(null) }

                            Text(
                                text = s.createYourVaultTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = owner,
                                onValueChange = { owner = it },
                                label = { Text(s.githubOwnerLabel) },
                                singleLine = true,
                                colors = darkFieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("setup_owner_input")
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = repo,
                                onValueChange = { repo = it },
                                label = { Text(s.githubRepoLabel) },
                                singleLine = true,
                                colors = darkFieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("setup_repo_input")
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = pass,
                                onValueChange = { pass = it },
                                label = { Text(s.createMasterPasswordLabel) },
                                singleLine = true,
                                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showPass = !showPass }) {
                                        Icon(
                                            if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = s.showPassword,
                                            tint = Color(0xFFCBD5E1)
                                        )
                                    }
                                },
                                colors = darkFieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("setup_password_input")
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = confirm,
                                onValueChange = { confirm = it },
                                label = { Text(s.confirmMasterPasswordLabel) },
                                singleLine = true,
                                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                                colors = darkFieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("setup_confirm_password_input")
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = pat,
                                onValueChange = {
                                    pat = it
                                    if (it.isNotBlank()) publishDirect = true
                                },
                                label = { Text(s.optionalPatForPublish) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                colors = darkFieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("setup_pat_input")
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = s.patSecurityWarning,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8)
                            )
                            if (pat.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 6.dp)
                                ) {
                                    Checkbox(
                                        checked = publishDirect,
                                        onCheckedChange = { publishDirect = it }
                                    )
                                    Text(
                                        text = "vault-control",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White
                                    )
                                }
                            }
                            val err = localError ?: uiState.authError
                            if (!err.isNullOrBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(err, color = Color(0xFFFCA5A5), style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    when {
                                        pass.length < 10 -> localError = s.passwordTooShortError
                                        pass != confirm -> localError = s.passwordMismatchError
                                        else -> {
                                            localError = null
                                            onInitializeVault(owner, repo, pass, pat, publishDirect)
                                        }
                                    }
                                },
                                enabled = !uiState.isBusy,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LockAccentGold,
                                    contentColor = Color(0xFF1F1300)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("create_vault_submit_button")
                            ) {
                                if (uiState.isBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF1F1300)
                                    )
                                } else {
                                    Icon(Icons.Default.Lock, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(s.generateAndCreateVault, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TextButton(onClick = { mode = LockScreenMode.CONNECT_REPO }) {
                                    Text(s.connectExistingVault, color = Color(0xFF7CC4FA))
                                }
                                if (hasConfiguredVault) {
                                    TextButton(onClick = { mode = LockScreenMode.LOCKED }) {
                                        Text(s.unlockLibrary, color = LockAccentGold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // One-time Recovery Key Modal (if created/rotated while on lock flow)
    if (!uiState.oneTimeRecoveryKey.isNullOrBlank()) {
        OneTimeRecoveryKeyDialog(
            recoveryKey = uiState.oneTimeRecoveryKey,
            pendingConfigJson = uiState.pendingExportConfigJson,
            uiState = uiState,
            onExportTextUri = onExportTextUri,
            onConfirmSaved = onDismissRecoveryCreatedDialog
        )
    }
}

@Composable
fun OneTimeRecoveryKeyDialog(
    recoveryKey: String,
    pendingConfigJson: String?,
    uiState: VaultUiState,
    onExportTextUri: (android.net.Uri, String, String) -> Unit,
    onConfirmSaved: () -> Unit
) {
    val s = uiState.strings
    val context = LocalContext.current

    val saveRecoveryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val payload = "PERSONAL PDF VAULT - RECOVERY KEY (KEEP OFFLINE)\n" +
                "Vault ID: ${uiState.activeVaultConfig?.vaultId.orEmpty()}\n" +
                "Recovery Key:\n$recoveryKey\n"
            onExportTextUri(uri, payload, s.downloadRecoveryKeyFile)
        }
    }

    val saveConfigLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && !pendingConfigJson.isNullOrBlank()) {
            onExportTextUri(uri, pendingConfigJson, s.downloadVaultConfigFile)
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        icon = {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        },
        title = { Text(s.saveRecoveryKeyTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(s.saveRecoveryKeyWarning, style = MaterialTheme.typography.bodySmall)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = recoveryKey,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Recovery Key", recoveryKey))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s.copyRecoveryKey, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { saveRecoveryLauncher.launch("pdf-vault-recovery-key.txt") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("TXT", maxLines = 1)
                    }
                }
                if (!pendingConfigJson.isNullOrBlank()) {
                    val ver = uiState.activeVaultConfig?.configVersion ?: 1
                    val filename = "vault-config-v%04d.json".format(ver)
                    FilledTonalButton(
                        onClick = { saveConfigLauncher.launch(filename) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(filename)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmSaved,
                modifier = Modifier.testTag("confirm_recovery_saved_button")
            ) {
                Text(s.confirmRecoverySaved)
            }
        }
    )
}
