package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.github.VaultDocumentRecord
import com.example.ui.VaultUiState
import com.example.ui.theme.JetBrainsMonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    uiState: VaultUiState,
    onClose: () -> Unit,
    onNavigatePage: (Int) -> Unit,
    onZoomChange: (Float) -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleBookmark: () -> Unit,
    onSearchInPdf: (String) -> Unit,
    onExportDecryptedPdf: (VaultDocumentRecord, Uri) -> Unit,
    onPrintDocument: (VaultDocumentRecord) -> Unit
) {
    val s = uiState.strings
    val reader = uiState.readerState
    val doc = reader.activeDocument

    BackHandler { onClose() }

    DisposableEffect(Unit) {
        onDispose {
            // Ensure temporary decrypted PDF files are cleaned when leaving reader
            onClose()
        }
    }

    var showSearchSheet by remember { mutableStateOf(false) }
    var showDownloadWarning by remember { mutableStateOf(false) }
    var showPrintWarning by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null && doc != null) {
            onExportDecryptedPdf(doc, uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (!reader.isFullscreen) {
            TopAppBar(
                title = {
                    Text(
                        text = doc?.descriptor?.title ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("reader_close_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                },
                actions = {
                    IconButton(onClick = { showSearchSheet = true }) {
                        Icon(Icons.Default.Search, contentDescription = s.readerSearchPlaceholder)
                    }
                    IconButton(onClick = onToggleBookmark) {
                        val bookmarked = reader.currentPage in reader.bookmarks
                        Icon(
                            if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = s.readerBookmarkPage,
                            tint = if (bookmarked) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { showDownloadWarning = true }) {
                        Icon(Icons.Default.Download, contentDescription = s.downloadDecryptedAction)
                    }
                    IconButton(onClick = { showPrintWarning = true }) {
                        Icon(Icons.Default.Print, contentDescription = s.printAction)
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when {
                reader.isLoading -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(14.dp))
                        Text(reader.loadingStage, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { reader.loadingProgress },
                            modifier = Modifier.width(220.dp)
                        )
                    }
                }

                reader.errorMessage != null -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(reader.errorMessage, color = MaterialTheme.colorScheme.onErrorContainer)
                            Button(onClick = onClose) { Text(s.cancelLabel) }
                        }
                    }
                }

                reader.pageBitmap != null -> {
                    var panOffsetX by remember(reader.currentPage) { mutableStateOf(0f) }
                    var panOffsetY by remember(reader.currentPage) { mutableStateOf(0f) }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .pointerInput(reader.currentPage) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    if (zoom != 1f) {
                                        onZoomChange(reader.zoomScale * zoom)
                                    }
                                    if (reader.zoomScale > 1.05f) {
                                        panOffsetX += pan.x
                                        panOffsetY += pan.y
                                    } else {
                                        panOffsetX = 0f
                                        panOffsetY = 0f
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = reader.pageBitmap.asImageBitmap(),
                            contentDescription = "${s.readerPageLabel} ${reader.currentPage + 1}",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 12.dp)
                                .graphicsLayer(
                                    scaleX = reader.zoomScale,
                                    scaleY = reader.zoomScale,
                                    translationX = panOffsetX,
                                    translationY = panOffsetY
                                )
                        )
                    }
                }
            }

            if (reader.isFullscreen) {
                Surface(
                    color = Color(0xAA000000),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = s.readerFullscreen, tint = Color.White)
                    }
                }
            }
        }

        // Bottom Reader Controls
        if (!reader.isFullscreen && reader.totalPages > 0) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (reader.totalPages > 1) {
                        Slider(
                            value = reader.currentPage.toFloat(),
                            onValueChange = { onNavigatePage(it.toInt()) },
                            valueRange = 0f..(reader.totalPages - 1).toFloat()
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onNavigatePage(reader.currentPage - 1) },
                                enabled = reader.currentPage > 0
                            ) {
                                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous page")
                            }
                            Text(
                                text = "${reader.currentPage + 1} / ${reader.totalPages}",
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelLarge
                            )
                            IconButton(
                                onClick = { onNavigatePage(reader.currentPage + 1) },
                                enabled = reader.currentPage < reader.totalPages - 1
                            ) {
                                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next page")
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onZoomChange(reader.zoomScale - 0.25f) }) {
                                Icon(Icons.Default.ZoomOut, contentDescription = s.readerZoomOut)
                            }
                            IconButton(onClick = { onZoomChange(1f) }) {
                                Icon(Icons.Default.FitScreen, contentDescription = s.readerFitWidth)
                            }
                            IconButton(onClick = { onZoomChange(reader.zoomScale + 0.25f) }) {
                                Icon(Icons.Default.ZoomIn, contentDescription = s.readerZoomIn)
                            }
                            IconButton(onClick = onToggleFullscreen) {
                                Icon(Icons.Default.Fullscreen, contentDescription = s.readerFullscreen)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSearchSheet) {
        var q by remember { mutableStateOf(reader.searchQuery) }
        ModalBottomSheet(onDismissRequest = { showSearchSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = q,
                        onValueChange = { q = it },
                        label = { Text(s.readerSearchPlaceholder) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { onSearchInPdf(q) }) { Text("Go") }
                }
                if (reader.isSearching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(220.dp)) {
                    items(reader.searchHits) { hit ->
                        Card(
                            onClick = {
                                onNavigatePage(hit.pageIndex)
                                showSearchSheet = false
                            }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "${s.readerPageLabel} ${hit.pageIndex + 1}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDownloadWarning && doc != null) {
        AlertDialog(
            onDismissRequest = { showDownloadWarning = false },
            title = { Text(s.unencryptedDownloadWarningTitle) },
            text = { Text(s.unencryptedDownloadWarningBody) },
            confirmButton = {
                Button(onClick = {
                    showDownloadWarning = false
                    exportLauncher.launch(doc.descriptor.originalFilename.ifBlank { "${doc.descriptor.title}.pdf" })
                }) { Text(s.proceedLabel) }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadWarning = false }) { Text(s.cancelLabel) }
            }
        )
    }

    if (showPrintWarning && doc != null) {
        AlertDialog(
            onDismissRequest = { showPrintWarning = false },
            title = { Text(s.unencryptedDownloadWarningTitle) },
            text = { Text(s.unencryptedDownloadWarningBody) },
            confirmButton = {
                Button(onClick = {
                    showPrintWarning = false
                    onPrintDocument(doc)
                }) { Text(s.proceedLabel) }
            },
            dismissButton = {
                TextButton(onClick = { showPrintWarning = false }) { Text(s.cancelLabel) }
            }
        )
    }
}
