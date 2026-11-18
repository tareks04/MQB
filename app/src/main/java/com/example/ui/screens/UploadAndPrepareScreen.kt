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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.example.ui.UploadJobStatus
import com.example.ui.VaultUiState
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun UploadAndPrepareScreen(
    uiState: VaultUiState,
    initialTab: Int = 0,
    onEnqueuePdfs: (
        uris: List<Uri>,
        title: String,
        desc: String,
        cat: String,
        tagsCsv: String,
        collection: String
    ) -> Unit,
    onStartUploadQueue: () -> Unit,
    onRetryQueueItem: (String) -> Unit,
    onClearCompleted: () -> Unit,
    onPrepareManualPair: (
        uri: Uri,
        title: String,
        desc: String,
        cat: String,
        tagsCsv: String,
        collection: String
    ) -> Unit,
    onSavePreparedFileToUri: (Uri, String, String) -> Unit,
    onSavePreparedTextToUri: (Uri, String, String) -> Unit,
    onOpenReleasesBrowser: () -> Unit,
    onSyncWithGitHub: () -> Unit,
    onImportPair: (pvaultUri: Uri, pvmetaUri: Uri) -> Unit,
    onImportSampleToVault: () -> Unit,
    onEnqueueSamplePdf: (
        title: String,
        desc: String,
        cat: String,
        tagsCsv: String,
        collection: String
    ) -> Unit,
    onPrepareSamplePair: (
        title: String,
        desc: String,
        cat: String,
        tagsCsv: String,
        collection: String
    ) -> Unit
) {
    val s = uiState.strings
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(s.defaultCategories.firstOrNull() ?: "Other") }
    var tagsCsv by remember { mutableStateOf("") }
    var collection by remember { mutableStateOf("") }

    var selectedManualPdfUri by remember { mutableStateOf<Uri?>(null) }
    var importPvaultUri by remember { mutableStateOf<Uri?>(null) }
    var importPvmetaUri by remember { mutableStateOf<Uri?>(null) }

    val multiPdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onEnqueuePdfs(uris, title, description, category, tagsCsv, collection)
        }
    }

    val singlePdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        selectedManualPdfUri = uri
    }

    val savePvaultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pair = uiState.preparedPair
        if (uri != null && pair != null) {
            onSavePreparedFileToUri(uri, pair.pvaultTempFilePath, s.savePvaultButton)
        }
    }

    val savePvmetaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val pair = uiState.preparedPair
        if (uri != null && pair != null) {
            onSavePreparedTextToUri(uri, pair.pvmetaJson, s.savePvmetaButton)
        }
    }

    val pickImportPvault = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        importPvaultUri = it
    }
    val pickImportPvmeta = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        importPvmetaUri = it
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            val tabLabels = listOf(s.methodADirectUpload, s.methodBPrepareManual, s.methodCImportPair)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                tabLabels.forEachIndexed { idx, label ->
                    SegmentedButton(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabLabels.size),
                        modifier = Modifier.testTag("upload_tab_$idx")
                    ) {
                        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (selectedTab == 0 || selectedTab == 1) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text(s.pdfTitleLabel) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("upload_title_input")
                        )
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text(s.pdfDescriptionLabel) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(s.pdfCategoryLabel, style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(uiState.allAvailableCategories) { cat ->
                                FilterChip(
                                    selected = category == cat,
                                    onClick = { category = cat },
                                    label = { Text(cat) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = tagsCsv,
                            onValueChange = { tagsCsv = it },
                            label = { Text(s.pdfTagsLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = collection,
                            onValueChange = { collection = it },
                            label = { Text(s.pdfCollectionLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        when (selectedTab) {
            0 -> {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (!uiState.hasGitHubToken) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Warning, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = if (uiState.isBangla) {
                                                "সরাসরি আপলোডের জন্য নিরাপত্তা সেটিংসে সেশন টোকেন (PAT) দিন অথবা পদ্ধতি B ব্যবহার করুন।"
                                            } else {
                                                "Set a Fine-Grained PAT in Security Settings for direct upload, or use Method B."
                                            },
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = { multiPdfPicker.launch(arrayOf("application/pdf")) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("pick_pdfs_for_upload_button")
                                ) {
                                    Icon(Icons.Default.FileOpen, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.selectPdfButton)
                                }

                                Button(
                                    onClick = onStartUploadQueue,
                                    enabled = uiState.uploadQueue.any {
                                        it.status == UploadJobStatus.QUEUED || it.status == UploadJobStatus.FAILED
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("start_upload_queue_button")
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.encryptAndUploadButton, maxLines = 1)
                                }
                            }

                            FilledTonalButton(
                                onClick = onImportSampleToVault,
                                enabled = !uiState.isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("upload_tab_add_sample_to_vault_button")
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.addSamplePdfAction)
                            }

                            OutlinedButton(
                                onClick = {
                                    onEnqueueSamplePdf(title, description, category, tagsCsv, collection)
                                },
                                enabled = !uiState.isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("upload_sample_pdf_button")
                            ) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.uploadSampleToGitHubAction)
                            }
                        }
                    }
                }

                if (uiState.uploadQueue.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(s.uploadQueueTitle, style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = onClearCompleted) {
                                Text(if (uiState.isBangla) "সম্পন্ন মুছুন" else "Clear Done")
                            }
                        }
                    }
                    items(uiState.uploadQueue, key = { it.id }) { item ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    when (item.status) {
                                        UploadJobStatus.COMPLETED -> Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Done",
                                            tint = MaterialTheme.colorScheme.tertiary
                                        )
                                        UploadJobStatus.FAILED -> Icon(
                                            Icons.Default.Error,
                                            contentDescription = "Failed",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        else -> Text(
                                            item.status.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = JetBrainsMonoFontFamily
                                        )
                                    }
                                }
                                if (item.duplicateTitle != null) {
                                    Text(
                                        "${s.duplicateDetectedWarning}: ${item.duplicateTitle}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { item.progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (item.errorMessage != null) {
                                    Text(item.errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    OutlinedButton(onClick = { onRetryQueueItem(item.id) }) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(s.retryButton)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { singlePdfPicker.launch(arrayOf("application/pdf")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.FileOpen, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(selectedManualPdfUri?.lastPathSegment ?: s.selectPdfButton)
                            }
                            Button(
                                onClick = {
                                    selectedManualPdfUri?.let {
                                        onPrepareManualPair(it, title, description, category, tagsCsv, collection)
                                    }
                                },
                                enabled = selectedManualPdfUri != null && !uiState.isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("prepare_encrypted_pair_button")
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(s.encryptAndPrepareButton)
                            }

                            FilledTonalButton(
                                onClick = {
                                    onPrepareSamplePair(title, description, category, tagsCsv, collection)
                                },
                                enabled = !uiState.isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("prepare_sample_pdf_button")
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(s.prepareSamplePairAction)
                            }

                            uiState.preparedPair?.let { pair ->
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "${pair.pvaultFilename} (${formatFileSize(pair.pvaultSizeBytes)})\n${pair.pvmetaFilename}",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilledTonalButton(
                                                onClick = { savePvaultLauncher.launch(pair.pvaultFilename) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(s.savePvaultButton)
                                            }
                                            FilledTonalButton(
                                                onClick = { savePvmetaLauncher.launch(pair.pvmetaFilename) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(s.savePvmetaButton)
                                            }
                                        }
                                        Text(s.manualUploadSteps, style = MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = onOpenReleasesBrowser,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(s.openReleasesPageButton, maxLines = 1)
                                            }
                                            Button(
                                                onClick = onSyncWithGitHub,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(s.syncNow, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(s.methodCImportPair, style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(
                                onClick = { pickImportPvault.launch(arrayOf("*/*")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(importPvaultUri?.lastPathSegment ?: s.selectPvaultFile)
                            }
                            OutlinedButton(
                                onClick = { pickImportPvmeta.launch(arrayOf("*/*")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.AddCircleOutline, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(importPvmetaUri?.lastPathSegment ?: s.selectPvmetaFile)
                            }
                            Button(
                                onClick = {
                                    val v = importPvaultUri
                                    val m = importPvmetaUri
                                    if (v != null && m != null) onImportPair(v, m)
                                },
                                enabled = importPvaultUri != null && importPvmetaUri != null && !uiState.isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("import_pair_submit_button")
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(s.importPairButton)
                            }
                        }
                    }
                }
            }
        }
    }
}
