package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.crypto.VaultFormatConstants
import com.example.github.VaultDocumentRecord
import com.example.ui.NavSection
import com.example.ui.VaultUiState
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.util.Locale

@Composable
fun DashboardScreen(
    uiState: VaultUiState,
    onNavigate: (NavSection) -> Unit,
    onSyncNow: () -> Unit,
    onOpenDocument: (VaultDocumentRecord) -> Unit,
    onImportSamplePdf: () -> Unit
) {
    val s = uiState.strings
    val docs = uiState.documents
    val usedBytes = uiState.usedEncryptedBytes
    val usedMiB = usedBytes / (1024.0 * 1024.0)
    val remainingMiB = (VaultFormatConstants.STORAGE_LIMIT_MIB - usedMiB).coerceAtLeast(0.0)
    val progress = (usedBytes.toFloat() / VaultFormatConstants.STORAGE_LIMIT_BYTES.toFloat()).coerceIn(0f, 1f)
    val pct = (progress * 100f).toInt()
    val categoriesCount = docs.map { it.descriptor.category }.distinct().size
    val favorites = docs.filter { it.descriptor.isFavorite }
    val recentlyAdded = docs.sortedByDescending { it.descriptor.uploadedAt }.take(6)
    val recentlyOpened = docs.filter { it.lastOpenedAtEpochMs > 0L }
        .sortedByDescending { it.lastOpenedAtEpochMs }
        .take(6)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Vault Banner
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_vault_hero),
                        contentDescription = s.appTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(0xE6081423),
                                        Color(0x99081423)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Surface(
                                color = Color(0x33F5B041),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "${uiState.settings.githubOwner.ifBlank { "local" }}/${uiState.settings.githubRepo}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = Color(0xFFFDE68A),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = s.appTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White
                            )
                            Text(
                                text = s.appSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onNavigate(NavSection.UPLOAD) },
                                modifier = Modifier.testTag("dashboard_upload_button")
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.navUpload)
                            }
                            FilledTonalButton(
                                onClick = onSyncNow,
                                modifier = Modifier.testTag("dashboard_sync_button")
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.syncNow)
                            }
                        }
                    }
                }
            }
        }

        // Warnings if approaching 900 MiB or 900 assets
        if (usedMiB >= VaultFormatConstants.STORAGE_WARN_MIB) {
            item {
                WarningBannerCard(text = s.storageNearLimitWarning)
            }
        }
        val nearAssetLimit = uiState.syncReport?.releaseAssetCounts?.values?.any {
            it >= VaultFormatConstants.RELEASE_ASSET_WARN_COUNT
        } == true
        if (nearAssetLimit) {
            item {
                WarningBannerCard(text = s.releaseNearLimitWarning)
            }
        }

        // Storage Target Card (950 MiB)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Storage,
                                contentDescription = s.storageUsed,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(s.storageUsed, style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = "$pct%",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = if (usedMiB >= VaultFormatConstants.STORAGE_WARN_MIB)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${formatMiB(usedMiB)} / ${VaultFormatConstants.STORAGE_LIMIT_MIB} MiB",
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = JetBrainsMonoFontFamily
                        )
                        Text(
                            text = "${s.storageRemaining}: ${formatMiB(remainingMiB)} MiB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = s.storageTargetNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // KPI Stat Grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DashboardStatCard(
                    icon = Icons.Default.PictureAsPdf,
                    label = s.totalPdfs,
                    value = docs.size.toString(),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigate(NavSection.LIBRARY) }
                )
                DashboardStatCard(
                    icon = Icons.Default.FolderSpecial,
                    label = s.totalCategories,
                    value = categoriesCount.toString(),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigate(NavSection.LIBRARY) }
                )
                DashboardStatCard(
                    icon = Icons.Default.Favorite,
                    label = s.navFavorites,
                    value = favorites.size.toString(),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigate(NavSection.FAVORITES) }
                )
            }
        }

        // Empty State or Library Highlights
        if (docs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_empty_library),
                            contentDescription = s.emptyLibraryTitle,
                            modifier = Modifier
                                .size(140.dp)
                                .clip(RoundedCornerShape(18.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(s.emptyLibraryTitle, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = s.emptyLibrarySubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        FilledTonalButton(
                            onClick = onImportSamplePdf,
                            enabled = !uiState.isBusy,
                            modifier = Modifier.testTag("dashboard_add_sample_pdf_button")
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s.addSamplePdfAction)
                        }
                    }
                }
            }
        } else {
            if (recentlyOpened.isNotEmpty()) {
                item {
                    HorizontalDocRow(
                        title = s.recentlyOpened,
                        icon = Icons.Default.History,
                        docs = recentlyOpened,
                        onOpenDocument = onOpenDocument
                    )
                }
            }
            if (favorites.isNotEmpty()) {
                item {
                    HorizontalDocRow(
                        title = s.favoritesTitle,
                        icon = Icons.Default.Favorite,
                        docs = favorites,
                        onOpenDocument = onOpenDocument
                    )
                }
            }
            item {
                HorizontalDocRow(
                    title = s.recentlyAdded,
                    icon = Icons.Default.PictureAsPdf,
                    docs = recentlyAdded,
                    onOpenDocument = onOpenDocument
                )
            }
        }
    }
}

@Composable
private fun WarningBannerCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun DashboardStatCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HorizontalDocRow(
    title: String,
    icon: ImageVector,
    docs: List<VaultDocumentRecord>,
    onOpenDocument: (VaultDocumentRecord) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(docs, key = { it.assetId }) { doc ->
                Card(
                    modifier = Modifier
                        .width(210.dp)
                        .clickable { onOpenDocument(doc) }
                        .testTag("dashboard_doc_${doc.assetId}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
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
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = doc.descriptor.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = formatFileSize(doc.descriptor.originalSizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

fun formatMiB(mib: Double): String = String.format(Locale.US, "%.1f", mib)

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f MB", mb)
}
