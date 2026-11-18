package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.NavSection
import com.example.ui.VaultUiState
import com.example.ui.VaultViewModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.LockAndSetupScreen
import com.example.ui.screens.PdfReaderScreen
import com.example.ui.screens.UploadAndPrepareScreen
import com.example.ui.screens.VaultToolsScreen
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.PdfVaultTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: VaultViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(uiState.settings.flagSecureEnabled) {
                if (uiState.settings.flagSecureEnabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            PdfVaultTheme(
                themeMode = uiState.settings.themeMode,
                isBangla = uiState.isBangla
            ) {
                PersonalPdfVaultRoot(viewModel = viewModel, uiState = uiState)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalPdfVaultRoot(
    viewModel: VaultViewModel,
    uiState: VaultUiState
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val s = uiState.strings

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    if (!uiState.isUnlocked) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                LockAndSetupScreen(
                    uiState = uiState,
                    onToggleLanguage = viewModel::toggleLanguage,
                    onUnlockWithPassword = viewModel::unlockWithMasterPassword,
                    onConnectGitHubRepo = viewModel::connectGitHubRepository,
                    onInitializeVault = viewModel::initializeNewVault,
                    onRecoverWithKey = viewModel::recoverWithKeyAndResetPassword,
                    onExportTextUri = viewModel::writeTextToUri,
                    onDismissRecoveryCreatedDialog = viewModel::dismissOneTimeRecoveryDialog
                )
            }
        }
        return
    }

    // Active PDF Reader overrides the shell cleanly
    if (uiState.readerState.activeDocument != null) {
        PdfReaderScreen(
            uiState = uiState,
            onClose = viewModel::closePdfReader,
            onNavigatePage = viewModel::navigateReaderPage,
            onZoomChange = viewModel::setReaderZoom,
            onToggleFullscreen = viewModel::toggleReaderFullscreen,
            onToggleBookmark = viewModel::toggleReaderBookmark,
            onSearchInPdf = viewModel::searchInActivePdf,
            onExportDecryptedPdf = viewModel::exportDecryptedPdfToUri,
            onPrintDocument = viewModel::printDocument
        )
        return
    }

    if (uiState.currentSection != NavSection.DASHBOARD) {
        BackHandler { viewModel.navigateTo(NavSection.DASHBOARD) }
    }

    val allNavItems = listOf(
        Triple(NavSection.DASHBOARD, s.navDashboard, Icons.Default.Dashboard),
        Triple(NavSection.LIBRARY, s.navLibrary, Icons.Default.Book),
        Triple(NavSection.UPLOAD, s.navUpload, Icons.Default.CloudUpload),
        Triple(NavSection.PREPARE, s.navPrepare, Icons.Default.FolderZip),
        Triple(NavSection.FAVORITES, s.navFavorites, Icons.Default.Favorite),
        Triple(NavSection.RECENT, s.navRecent, Icons.Default.History),
        Triple(NavSection.SYNC, s.navSync, Icons.Default.Sync),
        Triple(NavSection.BACKUP, s.navBackup, Icons.Default.Backup),
        Triple(NavSection.SECURITY, s.navSecurity, Icons.Default.Security),
        Triple(NavSection.HELP, s.navHelp, Icons.Default.Help)
    )

    val primaryBarItems = listOf(
        Triple(NavSection.DASHBOARD, s.navDashboard, Icons.Default.Dashboard),
        Triple(NavSection.LIBRARY, s.navLibrary, Icons.Default.Book),
        Triple(NavSection.UPLOAD, s.navUpload, Icons.Default.CloudUpload),
        Triple(NavSection.SECURITY, s.navSecurity, Icons.Default.Security)
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp)
                ) {
                    Text(
                        text = s.appTitle,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    if (uiState.settings.githubOwner.isNotBlank()) {
                        Text(
                            text = "${uiState.settings.githubOwner}/${uiState.settings.githubRepo}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    allNavItems.forEach { (section, label, icon) ->
                        NavigationDrawerItem(
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) },
                            selected = uiState.currentSection == section,
                            onClick = {
                                viewModel.navigateTo(section)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                                .testTag("drawer_nav_${section.name.lowercase()}")
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        label = { Text(s.lockNow) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            viewModel.lockVaultNow(clearTokenToo = false)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideScreen = maxWidth >= 600.dp

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            val titleText = allNavItems.firstOrNull {
                                it.first == uiState.currentSection
                            }?.second ?: s.appTitle
                            Text(titleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("open_drawer_button")
                            ) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = viewModel::toggleLanguage,
                                modifier = Modifier.testTag("topbar_language_toggle")
                            ) {
                                Text(if (uiState.isBangla) "B/E" else "E/B")
                            }
                            IconButton(
                                onClick = { viewModel.syncWithGitHub(silent = false) },
                                modifier = Modifier.testTag("topbar_sync_button")
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = s.syncNow)
                            }
                            IconButton(
                                onClick = { viewModel.lockVaultNow(clearTokenToo = false) },
                                modifier = Modifier.testTag("topbar_lock_button")
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = s.lockNow)
                            }
                        }
                    )
                },
                bottomBar = {
                    if (!isWideScreen) {
                        NavigationBar(windowInsets = WindowInsets.navigationBars) {
                            primaryBarItems.forEach { (section, label, icon) ->
                                NavigationBarItem(
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    selected = isPrimarySelected(uiState.currentSection, section),
                                    onClick = { viewModel.navigateTo(section) },
                                    modifier = Modifier.testTag("bottom_nav_${section.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isWideScreen) {
                        NavigationRail {
                            primaryBarItems.forEach { (section, label, icon) ->
                                NavigationRailItem(
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label, maxLines = 1) },
                                    selected = isPrimarySelected(uiState.currentSection, section),
                                    onClick = { viewModel.navigateTo(section) }
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (uiState.isBusy) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            VaultSectionContent(uiState = uiState, viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}

private fun isPrimarySelected(current: NavSection, item: NavSection): Boolean {
    return when (item) {
        NavSection.LIBRARY -> current == NavSection.LIBRARY ||
            current == NavSection.FAVORITES ||
            current == NavSection.RECENT
        NavSection.UPLOAD -> current == NavSection.UPLOAD || current == NavSection.PREPARE
        NavSection.SECURITY -> current == NavSection.SECURITY ||
            current == NavSection.SYNC ||
            current == NavSection.BACKUP ||
            current == NavSection.HELP
        else -> current == item
    }
}

@Composable
private fun VaultSectionContent(
    uiState: VaultUiState,
    viewModel: VaultViewModel
) {
    when (uiState.currentSection) {
        NavSection.DASHBOARD -> DashboardScreen(
            uiState = uiState,
            onNavigate = viewModel::navigateTo,
            onSyncNow = { viewModel.syncWithGitHub(silent = false) },
            onOpenDocument = viewModel::openDocumentInReader,
            onImportSamplePdf = viewModel::importBundledSamplePdf
        )

        NavSection.LIBRARY, NavSection.FAVORITES, NavSection.RECENT -> LibraryScreen(
            uiState = uiState,
            favoritesOnly = uiState.currentSection == NavSection.FAVORITES,
            recentOnly = uiState.currentSection == NavSection.RECENT,
            onSearchChange = viewModel::updateSearchQuery,
            onCategorySelect = viewModel::updateCategoryFilter,
            onTagSelect = viewModel::updateTagFilter,
            onSortChange = viewModel::updateSortOrder,
            onToggleGridView = viewModel::toggleGridView,
            onAddCustomCategory = viewModel::addCustomCategory,
            onOpenDocument = viewModel::openDocumentInReader,
            onToggleFavorite = viewModel::toggleFavorite,
            onToggleOfflineCache = viewModel::toggleOfflineCache,
            onExportDecryptedPdf = viewModel::exportDecryptedPdfToUri,
            onPrintDocument = viewModel::printDocument,
            onUpdateMetadata = { doc, title, desc, cat, tags, col ->
                viewModel.updateDocumentMetadata(doc, title, desc, cat, tags, col, doc.descriptor.isFavorite)
            },
            onDeleteDocument = viewModel::deleteDocument,
            onShowMessage = viewModel::showMessage,
            onImportSamplePdf = viewModel::importBundledSamplePdf,
            onUploadLocalToGitHub = viewModel::uploadLocalDocumentToGitHub
        )

        NavSection.UPLOAD, NavSection.PREPARE -> UploadAndPrepareScreen(
            uiState = uiState,
            initialTab = if (uiState.currentSection == NavSection.PREPARE) 1 else 0,
            onEnqueuePdfs = viewModel::inspectAndEnqueuePdfs,
            onStartUploadQueue = viewModel::startUploadQueue,
            onRetryQueueItem = viewModel::retryQueueItem,
            onClearCompleted = viewModel::clearCompletedQueueItems,
            onPrepareManualPair = viewModel::prepareEncryptedPairForManualUpload,
            onSavePreparedFileToUri = viewModel::writeFileToUri,
            onSavePreparedTextToUri = viewModel::writeTextToUri,
            onOpenReleasesBrowser = viewModel::openGitHubReleasesInBrowser,
            onSyncWithGitHub = { viewModel.syncWithGitHub(silent = false) },
            onImportPair = viewModel::importEncryptedPair,
            onImportSampleToVault = viewModel::importBundledSamplePdf,
            onEnqueueSamplePdf = viewModel::enqueueBundledSamplePdf,
            onPrepareSamplePair = viewModel::prepareBundledSamplePair
        )

        NavSection.SYNC, NavSection.BACKUP, NavSection.SECURITY, NavSection.HELP -> VaultToolsScreen(
            uiState = uiState,
            initialTab = when (uiState.currentSection) {
                NavSection.SYNC -> 0
                NavSection.BACKUP -> 1
                NavSection.SECURITY -> 2
                else -> 3
            },
            onSyncWithGitHub = { viewModel.syncWithGitHub(silent = false) },
            onRestoreSidecar = viewModel::restoreSidecarFromCache,
            onDeleteDocPermanently = { doc -> viewModel.deleteDocument(doc, true) },
            onSetSessionToken = viewModel::setSessionGitHubToken,
            onClearSessionToken = viewModel::clearSessionGitHubToken,
            onSetAutoLockMinutes = viewModel::setAutoLockMinutes,
            onLockNow = viewModel::lockVaultNow,
            onChangePassword = viewModel::changeMasterPassword,
            onRotateVaultKey = viewModel::rotateEntireVaultKey,
            onSetKdfSettings = viewModel::setKdfSettings,
            onSetThemeMode = viewModel::setThemeMode,
            onSetFlagSecure = viewModel::setFlagSecureEnabled,
            onExportConfigToUri = viewModel::writeTextToUri,
            onDismissOneTimeRecovery = viewModel::dismissOneTimeRecoveryDialog
        )
    }
}
