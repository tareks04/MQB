package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.github.VaultDocumentRecord
import com.example.ui.LibrarySortOrder
import com.example.ui.VaultUiState
import com.example.ui.theme.JetBrainsMonoFontFamily

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    uiState: VaultUiState,
    favoritesOnly: Boolean = false,
    recentOnly: Boolean = false,
    onSearchChange: (String) -> Unit,
    onCategorySelect: (String?) -> Unit,
    onTagSelect: (String?) -> Unit,
    onSortChange: (LibrarySortOrder) -> Unit,
    onToggleGridView: () -> Unit,
    onAddCustomCategory: (String) -> Unit,
    onOpenDocument: (VaultDocumentRecord) -> Unit,
    onToggleFavorite: (VaultDocumentRecord) -> Unit,
    onToggleOfflineCache: (VaultDocumentRecord) -> Unit,
    onExportDecryptedPdf: (VaultDocumentRecord, Uri) -> Unit,
    onPrintDocument: (VaultDocumentRecord) -> Unit,
    onUpdateMetadata: (
        record: VaultDocumentRecord,
        title: String,
        desc: String,
        cat: String,
        tagsCsv: String,
        collection: String
    ) -> Unit,
    onDeleteDocument: (VaultDocumentRecord, Boolean) -> Unit,
    onShowMessage: (String) -> Unit,
    onImportSamplePdf: () -> Unit,
    onUploadLocalToGitHub: (VaultDocumentRecord) -> Unit
) {
    val s = uiState.strings
    val context = LocalContext.current

    var selectedDocForSheet by remember { mutableStateOf<VaultDocumentRecord?>(null) }
    var docForDownloadWarning by remember { mutableStateOf<VaultDocumentRecord?>(null) }
    var docForPrintWarning by remember { mutableStateOf<VaultDocumentRecord?>(null) }
    var docPendingSafExport by remember { mutableStateOf<VaultDocumentRecord?>(null) }
    var docForEdit by remember { mutableStateOf<VaultDocumentRecord?>(null) }
    var docForDelete by remember { mutableStateOf<VaultDocumentRecord?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }

    val exportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val doc = docPendingSafExport
        if (uri != null && doc != null) {
            onExportDecryptedPdf(doc, uri)
        }
        docPendingSafExport = null
    }

    val filteredDocs = remember(
        uiState.documents,
        uiState.searchQuery,
        uiState.selectedCategory,
        uiState.selectedTag,
        uiState.sortOrder,
        favoritesOnly,
        recentOnly
    ) {
        uiState.documents
            .filter { doc ->
                if (favoritesOnly && !doc.descriptor.isFavorite) return@filter false
                if (recentOnly && doc.lastOpenedAtEpochMs <= 0L) return@filter false
                if (uiState.selectedCategory != null &&
                    !doc.descriptor.category.equals(uiState.selectedCategory, ignoreCase = true)
                ) return@filter false
                if (uiState.selectedTag != null &&
                    uiState.selectedTag !in doc.descriptor.tags
                ) return@filter false
                val q = uiState.searchQuery.trim()
                if (q.isNotEmpty()) {
                    val match = doc.descriptor.title.contains(q, ignoreCase = true) ||
                        doc.descriptor.description.contains(q, ignoreCase = true) ||
                        doc.descriptor.category.contains(q, ignoreCase = true) ||
                        doc.descriptor.collection.contains(q, ignoreCase = true) ||
                        doc.descriptor.tags.any { it.contains(q, ignoreCase = true) }
                    if (!match) return@filter false
                }
                true
            }
            .let { list ->
                when {
                    recentOnly -> list.sortedByDescending { it.lastOpenedAtEpochMs }
                    uiState.sortOrder == LibrarySortOrder.NEWEST ->
                        list.sortedByDescending { it.descriptor.uploadedAt }
                    uiState.sortOrder == LibrarySortOrder.TITLE ->
                        list.sortedBy { it.descriptor.title.lowercase() }
                    uiState.sortOrder == LibrarySortOrder.SIZE_DESC ->
                        list.sortedByDescending { it.descriptor.originalSizeBytes }
                    else -> list.sortedByDescending { it.lastOpenedAtEpochMs }
                }
            }
    }

    val allTags = remember(uiState.documents) {
        uiState.documents.flatMap { it.descriptor.tags }.distinct().sorted()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search + View & Sort toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text(s.searchPlaceholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("library_search_input")
            )

            Box {
                IconButton(
                    onClick = { showSortMenu = true },
                    modifier = Modifier.testTag("library_sort_button")
                ) {
                    Icon(Icons.Default.Sort, contentDescription = "Sort")
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(s.sortNewest) },
                        onClick = { onSortChange(LibrarySortOrder.NEWEST); showSortMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text(s.sortTitle) },
                        onClick = { onSortChange(LibrarySortOrder.TITLE); showSortMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text(s.sortSize) },
                        onClick = { onSortChange(LibrarySortOrder.SIZE_DESC); showSortMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text(s.sortRecent) },
                        onClick = { onSortChange(LibrarySortOrder.RECENT_OPENED); showSortMenu = false }
                    )
                }
            }

            IconButton(
                onClick = onToggleGridView,
                modifier = Modifier.testTag("library_view_toggle")
            ) {
                Icon(
                    if (uiState.isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                    contentDescription = if (uiState.isGridView) s.listView else s.gridView
                )
            }
        }

        // Category filter chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = uiState.selectedCategory == null,
                    onClick = { onCategorySelect(null) },
                    label = { Text(s.allCategories) }
                )
            }
            items(uiState.allAvailableCategories) { cat ->
                FilterChip(
                    selected = uiState.selectedCategory == cat,
                    onClick = {
                        onCategorySelect(if (uiState.selectedCategory == cat) null else cat)
                    },
                    label = { Text(cat) }
                )
            }
            item {
                FilterChip(
                    selected = false,
                    onClick = { showAddCategoryDialog = true },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, contentDescription = "Add category", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (uiState.isBangla) "নতুন" else "New")
                        }
                    }
                )
            }
        }

        if (allTags.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(allTags) { tag ->
                    FilterChip(
                        selected = uiState.selectedTag == tag,
                        onClick = { onTagSelect(if (uiState.selectedTag == tag) null else tag) },
                        label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        if (filteredDocs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(id = R.drawable.img_empty_library),
                        contentDescription = s.emptyLibraryTitle,
                        modifier = Modifier
                            .size(130.dp)
                            .clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(s.emptyLibraryTitle, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = s.emptyLibrarySubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!favoritesOnly && !recentOnly && uiState.documents.isEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = onImportSamplePdf,
                            enabled = !uiState.isBusy,
                            modifier = Modifier.testTag("library_add_sample_pdf_button")
                        ) {
                            Text(s.addSamplePdfAction)
                        }
                    }
                }
            }
        } else if (uiState.isGridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 168.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredDocs, key = { it.assetId }) { doc ->
                    LibraryDocCard(
                        doc = doc,
                        offlineBadge = s.offlineAvailableBadge,
                        onClick = { onOpenDocument(doc) },
                        onToggleFavorite = { onToggleFavorite(doc) },
                        onMoreClick = { selectedDocForSheet = doc }
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredDocs, key = { it.assetId }) { doc ->
                    LibraryDocListRow(
                        doc = doc,
                        offlineBadge = s.offlineAvailableBadge,
                        onClick = { onOpenDocument(doc) },
                        onToggleFavorite = { onToggleFavorite(doc) },
                        onMoreClick = { selectedDocForSheet = doc }
                    )
                }
            }
        }
    }

    // Document Actions Bottom Sheet
    selectedDocForSheet?.let { doc ->
        ModalBottomSheet(onDismissRequest = { selectedDocForSheet = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(doc.descriptor.title, style = MaterialTheme.typography.titleLarge)
                if (doc.descriptor.description.isNotBlank()) {
                    Text(
                        doc.descriptor.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistBadge(doc.descriptor.category)
                    AssistBadge(formatFileSize(doc.descriptor.originalSizeBytes))
                    if (doc.isOfflineCached) AssistBadge(s.offlineAvailableBadge)
                    doc.descriptor.tags.forEach { AssistBadge("#$it") }
                }
                Spacer(Modifier.height(4.dp))

                Button(
                    onClick = {
                        selectedDocForSheet = null
                        onOpenDocument(doc)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Book, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(s.readAction)
                }

                OutlinedButton(
                    onClick = {
                        selectedDocForSheet = null
                        docForDownloadWarning = doc
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(s.downloadDecryptedAction)
                }

                OutlinedButton(
                    onClick = {
                        selectedDocForSheet = null
                        docForPrintWarning = doc
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(s.printAction)
                }

                if (doc.isLocalOnlyImport) {
                    OutlinedButton(
                        onClick = {
                            selectedDocForSheet = null
                            onUploadLocalToGitHub(doc)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(s.uploadToGitHubReleaseAction)
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            selectedDocForSheet = null
                            onToggleOfflineCache(doc)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            if (doc.isOfflineCached) Icons.Default.CloudOff else Icons.Default.OfflinePin,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (doc.isOfflineCached) s.removeOfflineAction else s.cacheOfflineAction)
                    }

                    if (doc.pvaultDownloadUrl.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Encrypted .pvault URL", doc.pvaultDownloadUrl))
                                selectedDocForSheet = null
                                onShowMessage(s.encryptedUrlNote)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(s.copyEncryptedLinkAction)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            selectedDocForSheet = null
                            docForEdit = doc
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s.editMetadataAction, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            selectedDocForSheet = null
                            docForDelete = doc
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s.deleteAction, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Unencrypted Download Warning Dialog
    docForDownloadWarning?.let { doc ->
        AlertDialog(
            onDismissRequest = { docForDownloadWarning = null },
            title = { Text(s.unencryptedDownloadWarningTitle) },
            text = { Text(s.unencryptedDownloadWarningBody) },
            confirmButton = {
                Button(onClick = {
                    docPendingSafExport = doc
                    docForDownloadWarning = null
                    exportPdfLauncher.launch(doc.descriptor.originalFilename.ifBlank { "${doc.descriptor.title}.pdf" })
                }) {
                    Text(s.proceedLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { docForDownloadWarning = null }) { Text(s.cancelLabel) }
            }
        )
    }

    // Unencrypted Print Warning Dialog
    docForPrintWarning?.let { doc ->
        AlertDialog(
            onDismissRequest = { docForPrintWarning = null },
            title = { Text(s.unencryptedDownloadWarningTitle) },
            text = { Text(s.unencryptedDownloadWarningBody) },
            confirmButton = {
                Button(onClick = {
                    docForPrintWarning = null
                    onPrintDocument(doc)
                }) {
                    Text(s.proceedLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { docForPrintWarning = null }) { Text(s.cancelLabel) }
            }
        )
    }

    // Edit Metadata Dialog
    docForEdit?.let { doc ->
        var title by remember { mutableStateOf(doc.descriptor.title) }
        var desc by remember { mutableStateOf(doc.descriptor.description) }
        var cat by remember { mutableStateOf(doc.descriptor.category) }
        var tags by remember { mutableStateOf(doc.descriptor.tags.joinToString(", ")) }
        var collection by remember { mutableStateOf(doc.descriptor.collection) }

        AlertDialog(
            onDismissRequest = { docForEdit = null },
            title = { Text(s.editMetadataAction) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(s.pdfTitleLabel) })
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text(s.pdfDescriptionLabel) })
                    OutlinedTextField(value = cat, onValueChange = { cat = it }, label = { Text(s.pdfCategoryLabel) })
                    OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text(s.pdfTagsLabel) })
                    OutlinedTextField(value = collection, onValueChange = { collection = it }, label = { Text(s.pdfCollectionLabel) })
                }
            },
            confirmButton = {
                Button(onClick = {
                    docForEdit = null
                    onUpdateMetadata(doc, title, desc, cat, tags, collection)
                }) {
                    Text(s.proceedLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { docForEdit = null }) { Text(s.cancelLabel) }
            }
        )
    }

    // Delete Scope Dialog
    docForDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { docForDelete = null },
            title = { Text(s.deleteScopeTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(doc.descriptor.title, style = MaterialTheme.typography.titleSmall)
                    Text(s.deleteFromGitHubWarning, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = {
                            docForDelete = null
                            onDeleteDocument(doc, false)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(s.removeLocalOnly)
                    }
                    Button(
                        onClick = {
                            docForDelete = null
                            onDeleteDocument(doc, true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(s.deleteFromGitHubPermanently)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { docForDelete = null }) { Text(s.cancelLabel) }
            }
        )
    }

    // Add Custom Category Dialog
    if (showAddCategoryDialog) {
        var newCat by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = { Text(s.pdfCategoryLabel) },
            text = {
                OutlinedTextField(
                    value = newCat,
                    onValueChange = { newCat = it },
                    label = { Text(s.pdfCategoryLabel) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCat.isNotBlank()) onAddCustomCategory(newCat.trim())
                        showAddCategoryDialog = false
                    }
                ) { Text(s.proceedLabel) }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategoryDialog = false }) { Text(s.cancelLabel) }
            }
        )
    }
}

@Composable
private fun LibraryDocCard(
    doc: VaultDocumentRecord,
    offlineBadge: String,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMoreClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("doc_card_${doc.assetId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = doc.descriptor.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row {
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (doc.descriptor.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (doc.descriptor.isFavorite) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onMoreClick, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More actions", modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = doc.descriptor.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (doc.descriptor.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = doc.descriptor.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatFileSize(doc.descriptor.originalSizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    if (doc.isOfflineCached) Icons.Default.OfflinePin else Icons.Default.CloudDone,
                    contentDescription = if (doc.isOfflineCached) offlineBadge else "Cloud",
                    tint = if (doc.isOfflineCached) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun LibraryDocListRow(
    doc: VaultDocumentRecord,
    offlineBadge: String,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMoreClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("doc_row_${doc.assetId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = doc.descriptor.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${doc.descriptor.category} • ${formatFileSize(doc.descriptor.originalSizeBytes)}" +
                        if (doc.isOfflineCached) " • $offlineBadge" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (doc.descriptor.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (doc.descriptor.isFavorite) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onMoreClick) {
                Icon(Icons.Default.MoreVert, contentDescription = "More actions")
            }
        }
    }
}

@Composable
private fun AssistBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
