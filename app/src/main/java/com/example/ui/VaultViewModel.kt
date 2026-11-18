package com.example.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.crypto.EncryptedPdfPairResult
import com.example.crypto.KdfAlgorithm
import com.example.crypto.VaultConfig
import com.example.crypto.VaultCryptoEngine
import com.example.crypto.VaultCryptoException
import com.example.crypto.VaultFormatConstants
import com.example.data.PublicVaultSettings
import com.example.data.VaultDatabase
import com.example.data.VaultPreferences
import com.example.data.VaultRepository
import com.example.github.GitHubApiClient
import com.example.github.GitHubApiException
import com.example.github.SyncReport
import com.example.github.VaultDocumentRecord
import com.example.i18n.AppStrings
import com.example.i18n.BnStrings
import com.example.i18n.EnStrings
import com.example.pdf.PdfReaderHelper
import com.example.pdf.PdfSearchHit
import com.example.session.VaultSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class NavSection {
    DASHBOARD,
    LIBRARY,
    UPLOAD,
    PREPARE,
    FAVORITES,
    RECENT,
    SYNC,
    BACKUP,
    SECURITY,
    HELP
}

enum class LibrarySortOrder {
    NEWEST,
    TITLE,
    SIZE_DESC,
    RECENT_OPENED
}

enum class UploadJobStatus {
    QUEUED,
    ENCRYPTING,
    UPLOADING_PVAULT,
    UPLOADING_PVMETA,
    COMPLETED,
    FAILED
}

data class UploadQueueItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val originalFilename: String,
    val originalSizeBytes: Long,
    val title: String,
    val description: String,
    val category: String,
    val tags: List<String>,
    val collection: String,
    val sha256: String,
    val duplicateTitle: String? = null,
    val status: UploadJobStatus = UploadJobStatus.QUEUED,
    val progress: Float = 0f,
    val errorMessage: String? = null
)

data class ReaderUiState(
    val activeDocument: VaultDocumentRecord? = null,
    val decryptedPdfFile: File? = null,
    val isLoading: Boolean = false,
    val loadingStage: String = "",
    val loadingProgress: Float = 0f,
    val errorMessage: String? = null,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val pageBitmap: Bitmap? = null,
    val zoomScale: Float = 1f,
    val isFullscreen: Boolean = false,
    val bookmarks: List<Int> = emptyList(),
    val searchQuery: String = "",
    val searchHits: List<PdfSearchHit> = emptyList(),
    val isSearching: Boolean = false
)

data class VaultUiState(
    val settings: PublicVaultSettings = PublicVaultSettings(),
    val activeVaultConfig: VaultConfig? = null,
    val isUnlocked: Boolean = false,
    val hasGitHubToken: Boolean = false,
    val remainingAutoLockSeconds: Long? = null,
    val isBusy: Boolean = false,
    val busyMessage: String? = null,
    val authError: String? = null,
    val snackbarMessage: String? = null,
    val oneTimeRecoveryKey: String? = null,
    val pendingExportConfigJson: String? = null,
    val currentSection: NavSection = NavSection.DASHBOARD,
    val documents: List<VaultDocumentRecord> = emptyList(),
    val syncReport: SyncReport? = null,
    val uploadQueue: List<UploadQueueItem> = emptyList(),
    val preparedPair: EncryptedPdfPairResult? = null,
    val searchQuery: String = "",
    val selectedCategory: String? = null,
    val selectedTag: String? = null,
    val selectedCollection: String? = null,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.NEWEST,
    val isGridView: Boolean = true,
    val readerState: ReaderUiState = ReaderUiState()
) {
    val strings: AppStrings
        get() = if (settings.languageCode == "en") EnStrings else BnStrings

    val isBangla: Boolean
        get() = settings.languageCode != "en"

    val usedEncryptedBytes: Long
        get() = syncReport?.totalEncryptedBytes ?: documents.sumOf { it.totalEncryptedBytes }

    val customCategories: List<String>
        get() = settings.customCategoriesCsv.split("|").filter { it.isNotBlank() }

    val allAvailableCategories: List<String>
        get() = (strings.defaultCategories + customCategories + documents.map { it.descriptor.category })
            .filter { it.isNotBlank() }
            .distinct()
}

class VaultViewModel(application: Application) : AndroidViewModel(application) {

    private val crypto = VaultCryptoEngine()
    private val github = GitHubApiClient()
    private val session = VaultSessionManager()
    private val preferences = VaultPreferences(application)
    private val database = VaultDatabase.getInstance(application)
    private val repository = VaultRepository(application, crypto, github, session, preferences, database)
    val pdfHelper = PdfReaderHelper()

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    init {
        session.setOnAutoLockedListener {
            repository.wipeDecryptedTempFiles()
            _uiState.update {
                it.copy(
                    isUnlocked = false,
                    documents = emptyList(),
                    readerState = ReaderUiState(),
                    snackbarMessage = if (it.isBangla) "নিষ্ক্রিয়তার কারণে ভল্ট স্বয়ংক্রিয়ভাবে লক হয়েছে।"
                    else "Vault auto-locked due to inactivity."
                )
            }
        }

        viewModelScope.launch {
            preferences.settingsFlow.collect { prefs ->
                session.updateAutoLockMinutes(prefs.autoLockMinutes)
                val cachedCfg = repository.parseCachedConfigOrNull(prefs.cachedVaultConfigJson)
                _uiState.update {
                    it.copy(
                        settings = prefs,
                        activeVaultConfig = cachedCfg ?: it.activeVaultConfig
                    )
                }
            }
        }

        viewModelScope.launch {
            session.isUnlocked.collect { unlocked ->
                _uiState.update { it.copy(isUnlocked = unlocked) }
            }
        }

        viewModelScope.launch {
            session.hasGitHubToken.collect { hasToken ->
                _uiState.update { it.copy(hasGitHubToken = hasToken) }
            }
        }

        viewModelScope.launch {
            session.remainingAutoLockSeconds.collect { secs ->
                _uiState.update { it.copy(remainingAutoLockSeconds = secs) }
            }
        }
    }

    fun toggleLanguage() {
        val next = if (_uiState.value.isBangla) "en" else "bn"
        viewModelScope.launch { preferences.setLanguage(next) }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun setAutoLockMinutes(minutes: Int) {
        viewModelScope.launch { preferences.setAutoLockMinutes(minutes) }
    }

    fun setFlagSecureEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setFlagSecureEnabled(enabled) }
    }

    fun setKdfSettings(algorithm: String, memoryKiB: Int, iterations: Int) {
        viewModelScope.launch { preferences.setKdfSettings(algorithm, memoryKiB, iterations) }
    }

    fun navigateTo(section: NavSection) {
        session.touchActivity()
        _uiState.update { it.copy(currentSection = section) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun clearAuthError() {
        _uiState.update { it.copy(authError = null) }
    }

    fun dismissOneTimeRecoveryDialog() {
        _uiState.update { it.copy(oneTimeRecoveryKey = null, pendingExportConfigJson = null) }
    }

    fun setSessionGitHubToken(token: String) {
        session.setGitHubToken(token)
        val msg = if (_uiState.value.isBangla) {
            "সেশন টোকেন কেবল মেমরিতে (RAM) সংযুক্ত হয়েছে।"
        } else {
            "GitHub session token set in memory (RAM only)."
        }
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    fun clearSessionGitHubToken() {
        session.clearGitHubToken()
        val msg = if (_uiState.value.isBangla) {
            "সেশন টোকেন মেমরি থেকে মুছে ফেলা হয়েছে।"
        } else {
            "GitHub session credentials cleared from RAM."
        }
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    fun lockVaultNow(clearTokenToo: Boolean = false) {
        closePdfReader()
        repository.wipeDecryptedTempFiles()
        repository.wipeWorkTempFiles()
        session.lockVault(clearTokenToo = clearTokenToo)
        _uiState.update {
            it.copy(
                documents = emptyList(),
                uploadQueue = emptyList(),
                preparedPair = null,
                readerState = ReaderUiState(),
                currentSection = NavSection.DASHBOARD
            )
        }
    }

    fun connectGitHubRepository(owner: String, repo: String) {
        val cleanOwner = owner.trim()
        val cleanRepo = repo.trim().ifEmpty { "personal-pdf-vault" }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, authError = null, busyMessage = "GitHub...") }
            try {
                preferences.setGitHubRepoConfig(cleanOwner, cleanRepo)
                val (cfg, _) = repository.fetchRemoteVaultConfig(cleanOwner, cleanRepo)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = null,
                        activeVaultConfig = cfg,
                        snackbarMessage = if (it.isBangla) {
                            "ভল্ট কনফিগারেশন পাওয়া গেছে (v${cfg.configVersion})। এখন পাসওয়ার্ড দিয়ে আনলক করুন।"
                        } else {
                            "Vault configuration found (v${cfg.configVersion}). Unlock with your Master Password."
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = null,
                        authError = mapFriendlyError(e)
                    )
                }
            }
        }
    }

    fun initializeNewVault(
        owner: String,
        repo: String,
        password: String,
        optionalPat: String,
        publishToGitHub: Boolean
    ) {
        val cleanOwner = owner.trim().ifEmpty { "local-owner" }
        val cleanRepo = repo.trim().ifEmpty { "personal-pdf-vault" }
        val patTrimmed = optionalPat.trim()
        if (patTrimmed.isNotEmpty()) {
            session.setGitHubToken(patTrimmed)
        }
        val settings = _uiState.value.settings
        val kdfAlg = KdfAlgorithm.fromId(settings.kdfAlgorithm)

        viewModelScope.launch {
            val s = _uiState.value.strings
            _uiState.update {
                it.copy(isBusy = true, authError = null, busyMessage = s.unlockingProgress)
            }
            val pwdChars = password.toCharArray()
            try {
                val bundle = withContext(Dispatchers.Default) {
                    repository.initializeNewVault(
                        owner = cleanOwner,
                        repo = cleanRepo,
                        password = pwdChars,
                        kdfAlgorithm = kdfAlg,
                        memoryKiB = settings.argon2MemoryKiB,
                        iterations = if (kdfAlg == KdfAlgorithm.PBKDF2_SHA256)
                            VaultFormatConstants.PBKDF2_MIN_ITERATIONS else settings.argon2Iterations,
                        publishToGitHub = publishToGitHub && patTrimmed.isNotEmpty()
                    )
                }
                crypto.zeroBytes(bundle.masterKey)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = null,
                        activeVaultConfig = bundle.config,
                        oneTimeRecoveryKey = bundle.recoveryKeyFormatted,
                        pendingExportConfigJson = bundle.configJson,
                        documents = emptyList(),
                        snackbarMessage = if (publishToGitHub && patTrimmed.isNotEmpty()) {
                            if (it.isBangla) "ভল্ট তৈরি ও গিটহাব vault-control-এ প্রকাশিত হয়েছে।"
                            else "Vault created and published to GitHub vault-control."
                        } else {
                            if (it.isBangla) "ভল্ট তৈরি হয়েছে। config ফাইলটি ডাউনলোড করে vault-control রিলিজে আপলোড করুন।"
                            else "Vault created. Export vault-config-v0001.json and upload to vault-control."
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, busyMessage = null, authError = mapFriendlyError(e))
                }
            } finally {
                pwdChars.fill('\u0000')
            }
        }
    }

    fun unlockWithMasterPassword(password: String) {
        if (password.isEmpty()) return
        val settings = _uiState.value.settings
        val s = _uiState.value.strings
        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, authError = null, busyMessage = s.unlockingProgress)
            }
            val chars = password.toCharArray()
            try {
                val cfg = withContext(Dispatchers.Default) {
                    repository.unlockWithPassword(
                        password = chars,
                        owner = settings.githubOwner,
                        repo = settings.githubRepo,
                        cachedConfigJson = settings.cachedVaultConfigJson
                    )
                }
                val cachedDocs = repository.loadDecryptedLibraryFromCache(cfg.vaultId)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = null,
                        activeVaultConfig = cfg,
                        documents = cachedDocs,
                        authError = null
                    )
                }
                if (settings.githubOwner.isNotBlank() && settings.githubRepo.isNotBlank()) {
                    syncWithGitHub(silent = true)
                }
            } catch (e: VaultCryptoException.InvalidCredentials) {
                _uiState.update {
                    it.copy(isBusy = false, busyMessage = null, authError = s.wrongPasswordError)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, busyMessage = null, authError = mapFriendlyError(e))
                }
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun recoverWithKeyAndResetPassword(
        recoveryKey: String,
        newPassword: String,
        optionalPat: String
    ) {
        val settings = _uiState.value.settings
        val s = _uiState.value.strings
        if (optionalPat.isNotBlank()) {
            session.setGitHubToken(optionalPat.trim())
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, authError = null, busyMessage = s.unlockingProgress)
            }
            val newChars = newPassword.toCharArray()
            try {
                val cfg = withContext(Dispatchers.Default) {
                    repository.unlockWithRecoveryKey(
                        recoveryKey = recoveryKey,
                        owner = settings.githubOwner,
                        repo = settings.githubRepo,
                        cachedConfigJson = settings.cachedVaultConfigJson
                    )
                }
                val canPublish = session.getGitHubToken() != null && settings.githubOwner.isNotBlank()
                val (updatedCfg, updatedJson) = withContext(Dispatchers.Default) {
                    repository.changeMasterPassword(
                        owner = settings.githubOwner,
                        repo = settings.githubRepo,
                        currentConfig = cfg,
                        newPassword = newChars,
                        kdfAlgorithm = KdfAlgorithm.fromId(cfg.kdf.algorithm),
                        memoryKiB = cfg.kdf.parameters.memoryKiB,
                        iterations = cfg.kdf.parameters.iterations,
                        publishToGitHub = canPublish
                    )
                }
                val cachedDocs = repository.loadDecryptedLibraryFromCache(updatedCfg.vaultId)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = null,
                        activeVaultConfig = updatedCfg,
                        documents = cachedDocs,
                        pendingExportConfigJson = if (!canPublish) updatedJson else null,
                        snackbarMessage = if (canPublish) {
                            if (it.isBangla) "রিকভারি সফল! নতুন পাসওয়ার্ড ও কনফিগ গিটহাবে প্রকাশিত হয়েছে।"
                            else "Recovered! New password and config published to GitHub."
                        } else {
                            if (it.isBangla) "রিকভারি সফল! নতুন কনফিগ (v${updatedCfg.configVersion}) গিটহাবে আপলোড করুন।"
                            else "Recovered! Please export and upload the updated config (v${updatedCfg.configVersion})."
                        }
                    )
                }
            } catch (e: VaultCryptoException.InvalidRecoveryKey) {
                _uiState.update {
                    it.copy(isBusy = false, busyMessage = null, authError = s.invalidRecoveryKeyError)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, busyMessage = null, authError = mapFriendlyError(e))
                }
            } finally {
                newChars.fill('\u0000')
            }
        }
    }

    fun syncWithGitHub(silent: Boolean = false) {
        val settings = _uiState.value.settings
        val vaultId = _uiState.value.activeVaultConfig?.vaultId ?: return
        if (settings.githubOwner.isBlank() || settings.githubRepo.isBlank()) {
            if (!silent) {
                _uiState.update {
                    it.copy(
                        snackbarMessage = if (it.isBangla) "প্রথমে গিটহাব রিপোজিটরি কনফিগার করুন।"
                        else "Please configure GitHub repository first."
                    )
                }
            }
            return
        }

        viewModelScope.launch {
            if (!silent) {
                _uiState.update {
                    it.copy(
                        isBusy = true,
                        busyMessage = if (it.isBangla) "গিটহাব রিলিজ সিঙ্ক হচ্ছে..." else "Syncing GitHub Releases..."
                    )
                }
            }
            try {
                val (records, report) = repository.syncWithGitHub(
                    owner = settings.githubOwner,
                    repo = settings.githubRepo,
                    vaultId = vaultId
                )
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = null,
                        documents = records,
                        syncReport = report,
                        snackbarMessage = if (!silent) {
                            if (it.isBangla) {
                                "সিঙ্ক সম্পন্ন: ${records.size}টি PDF (${report.newCount}টি নতুন, ${report.incompleteIssues.size}টি অসম্পূর্ণ)।"
                            } else {
                                "Synced: ${records.size} PDFs (${report.newCount} new, ${report.incompleteIssues.size} incomplete)."
                            }
                        } else it.snackbarMessage
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = null,
                        snackbarMessage = if (!silent) mapFriendlyError(e) else it.snackbarMessage
                    )
                }
            }
        }
    }

    fun inspectAndEnqueuePdfs(
        uris: List<Uri>,
        customTitle: String,
        description: String,
        category: String,
        tagsCsv: String,
        collection: String
    ) {
        if (uris.isEmpty()) return
        val docs = _uiState.value.documents
        val parsedTags = tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        viewModelScope.launch {
            val newItems = mutableListOf<UploadQueueItem>()
            for (uri in uris) {
                try {
                    val info = repository.checkPdfBeforeEncryption(uri, docs)
                    val title = if (uris.size == 1 && customTitle.isNotBlank()) customTitle.trim()
                    else info.filename.removeSuffix(".pdf").removeSuffix(".PDF")
                    newItems.add(
                        UploadQueueItem(
                            uri = uri,
                            originalFilename = info.filename,
                            originalSizeBytes = info.sizeBytes,
                            title = title,
                            description = description.trim(),
                            category = category,
                            tags = parsedTags,
                            collection = collection.trim(),
                            sha256 = info.sha256,
                            duplicateTitle = info.duplicateTitle
                        )
                    )
                } catch (e: Exception) {
                    _uiState.update { it.copy(snackbarMessage = mapFriendlyError(e)) }
                }
            }
            if (newItems.isNotEmpty()) {
                _uiState.update { it.copy(uploadQueue = it.uploadQueue + newItems) }
            }
        }
    }

    fun startUploadQueue() {
        val settings = _uiState.value.settings
        val vaultId = _uiState.value.activeVaultConfig?.vaultId ?: return
        if (session.getGitHubToken().isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    snackbarMessage = if (it.isBangla) "সরাসরি আপলোডের জন্য সেশন টোকেন (Fine-Grained PAT) প্রয়োজন।"
                    else "A Fine-Grained GitHub PAT in session is required for direct upload."
                )
            }
            return
        }

        viewModelScope.launch {
            val pending = _uiState.value.uploadQueue.filter {
                it.status == UploadJobStatus.QUEUED || it.status == UploadJobStatus.FAILED
            }
            for (item in pending) {
                val projectedUsed = _uiState.value.usedEncryptedBytes + item.originalSizeBytes
                if (projectedUsed > VaultFormatConstants.STORAGE_LIMIT_BYTES) {
                    updateQueueItem(item.id) {
                        it.copy(
                            status = UploadJobStatus.FAILED,
                            errorMessage = if (_uiState.value.isBangla) "৯৫০ MiB স্টোরেজ সীমা অতিক্রম করবে।"
                            else "Exceeds 950 MiB personal storage target."
                        )
                    }
                    continue
                }

                try {
                    val record = repository.encryptAndUploadPdf(
                        uri = item.uri,
                        owner = settings.githubOwner,
                        repo = settings.githubRepo,
                        vaultId = vaultId,
                        originalFilename = item.originalFilename,
                        originalSizeBytes = item.originalSizeBytes,
                        title = item.title,
                        description = item.description,
                        category = item.category,
                        tags = item.tags,
                        collection = item.collection
                    ) { stage, progress ->
                        val st = when (stage) {
                            "encrypting" -> UploadJobStatus.ENCRYPTING
                            "uploading_pvault" -> UploadJobStatus.UPLOADING_PVAULT
                            else -> UploadJobStatus.UPLOADING_PVMETA
                        }
                        updateQueueItem(item.id) { it.copy(status = st, progress = progress, errorMessage = null) }
                    }
                    updateQueueItem(item.id) { it.copy(status = UploadJobStatus.COMPLETED, progress = 1f) }
                    _uiState.update { state ->
                        state.copy(documents = listOf(record) + state.documents.filter { it.assetId != record.assetId })
                    }
                } catch (e: Exception) {
                    updateQueueItem(item.id) {
                        it.copy(status = UploadJobStatus.FAILED, errorMessage = mapFriendlyError(e))
                    }
                }
            }
        }
    }

    fun retryQueueItem(itemId: String) {
        updateQueueItem(itemId) { it.copy(status = UploadJobStatus.QUEUED, progress = 0f, errorMessage = null) }
        startUploadQueue()
    }

    fun clearCompletedQueueItems() {
        _uiState.update { state ->
            state.copy(uploadQueue = state.uploadQueue.filter { it.status != UploadJobStatus.COMPLETED })
        }
    }

    private fun updateQueueItem(id: String, transform: (UploadQueueItem) -> UploadQueueItem) {
        _uiState.update { state ->
            state.copy(uploadQueue = state.uploadQueue.map { if (it.id == id) transform(it) else it })
        }
    }

    fun prepareEncryptedPairForManualUpload(
        uri: Uri,
        title: String,
        description: String,
        category: String,
        tagsCsv: String,
        collection: String
    ) {
        val vaultId = _uiState.value.activeVaultConfig?.vaultId ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyMessage = if (it.isBangla) "পিডিএফ লোকালে এনক্রিপ্ট হচ্ছে..." else "Encrypting PDF locally..."
                )
            }
            try {
                val info = repository.checkPdfBeforeEncryption(uri, _uiState.value.documents)
                val tags = tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                val pair = repository.prepareEncryptedPairOnly(
                    uri = uri,
                    vaultId = vaultId,
                    originalFilename = info.filename,
                    originalSizeBytes = info.sizeBytes,
                    title = title.ifBlank { info.filename.removeSuffix(".pdf") },
                    description = description,
                    category = category,
                    tags = tags,
                    collection = collection
                ) {}
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = null,
                        preparedPair = pair,
                        snackbarMessage = if (it.isBangla) {
                            "এনক্রিপ্টেড পেয়ার প্রস্তুত! এখন দুটি ফাইল সেভ করে গিটহাব রিলিজে আপলোড করুন।"
                        } else {
                            "Encrypted pair ready! Save both files and upload to GitHub Release."
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, busyMessage = null, snackbarMessage = mapFriendlyError(e))
                }
            }
        }
    }

    fun importEncryptedPair(pvaultUri: Uri, pvmetaUri: Uri) {
        val vaultId = _uiState.value.activeVaultConfig?.vaultId ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyMessage = if (it.isBangla) "এনক্রিপ্টেড পেয়ার যাচাই হচ্ছে..." else "Verifying encrypted pair..."
                )
            }
            try {
                val record = repository.importEncryptedPairFromUris(pvaultUri, pvmetaUri, vaultId)
                _uiState.update { state ->
                    state.copy(
                        isBusy = false,
                        busyMessage = null,
                        documents = listOf(record) + state.documents.filter { it.assetId != record.assetId },
                        snackbarMessage = if (state.isBangla) "পেয়ার সফলভাবে যাচাই ও লাইব্রেরিতে যুক্ত হয়েছে।"
                        else "Encrypted pair verified and added to library."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, busyMessage = null, snackbarMessage = mapFriendlyError(e))
                }
            }
        }
    }

    fun openDocumentInReader(record: VaultDocumentRecord) {
        session.touchActivity()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    readerState = ReaderUiState(
                        activeDocument = record,
                        isLoading = true,
                        loadingStage = it.strings.readerDecrypting,
                        currentPage = record.lastReadPage,
                        bookmarks = record.bookmarks
                    )
                )
            }
            try {
                val decryptedFile = repository.decryptDocumentToTempPdf(record) { stage, prog ->
                    _uiState.update { st ->
                        val label = if (stage == "downloading") {
                            if (st.isBangla) "এনক্রিপ্টেড পিডিএফ ডাউনলোড হচ্ছে..." else "Downloading encrypted .pvault..."
                        } else {
                            st.strings.readerDecrypting
                        }
                        st.copy(
                            readerState = st.readerState.copy(
                                loadingStage = label,
                                loadingProgress = prog
                            )
                        )
                    }
                }
                val totalPages = pdfHelper.getPageCount(decryptedFile)
                val safePage = record.lastReadPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                val bitmap = pdfHelper.renderPageBitmap(decryptedFile, safePage, 1200)

                repository.saveEncryptedReadingState(record.assetId, safePage, totalPages, record.bookmarks)
                val now = System.currentTimeMillis()
                _uiState.update { state ->
                    state.copy(
                        documents = state.documents.map {
                            if (it.assetId == record.assetId) it.copy(
                                lastOpenedAtEpochMs = now,
                                lastReadPage = safePage,
                                totalPages = totalPages
                            ) else it
                        },
                        readerState = state.readerState.copy(
                            decryptedPdfFile = decryptedFile,
                            isLoading = false,
                            currentPage = safePage,
                            totalPages = totalPages,
                            pageBitmap = bitmap
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        readerState = state.readerState.copy(
                            isLoading = false,
                            errorMessage = mapFriendlyError(e)
                        )
                    )
                }
            }
        }
    }

    fun navigateReaderPage(newPage: Int) {
        session.touchActivity()
        val reader = _uiState.value.readerState
        val file = reader.decryptedPdfFile ?: return
        val doc = reader.activeDocument ?: return
        val safePage = newPage.coerceIn(0, (reader.totalPages - 1).coerceAtLeast(0))
        viewModelScope.launch {
            try {
                val bitmap = pdfHelper.renderPageBitmap(file, safePage, (1200 * reader.zoomScale).toInt())
                _uiState.update {
                    it.copy(readerState = it.readerState.copy(currentPage = safePage, pageBitmap = bitmap))
                }
                repository.saveEncryptedReadingState(doc.assetId, safePage, reader.totalPages, reader.bookmarks)
            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = mapFriendlyError(e)) }
            }
        }
    }

    fun setReaderZoom(scale: Float) {
        val clamped = scale.coerceIn(0.8f, 3.0f)
        val reader = _uiState.value.readerState
        val file = reader.decryptedPdfFile
        _uiState.update { it.copy(readerState = it.readerState.copy(zoomScale = clamped)) }
        if (file != null) {
            viewModelScope.launch {
                val bitmap = pdfHelper.renderPageBitmap(file, reader.currentPage, (1200 * clamped).toInt())
                _uiState.update { it.copy(readerState = it.readerState.copy(pageBitmap = bitmap)) }
            }
        }
    }

    fun toggleReaderFullscreen() {
        _uiState.update {
            it.copy(readerState = it.readerState.copy(isFullscreen = !it.readerState.isFullscreen))
        }
    }

    fun toggleReaderBookmark() {
        val reader = _uiState.value.readerState
        val doc = reader.activeDocument ?: return
        val page = reader.currentPage
        val updated = if (page in reader.bookmarks) reader.bookmarks - page else (reader.bookmarks + page).sorted()
        _uiState.update { state ->
            state.copy(
                readerState = state.readerState.copy(bookmarks = updated),
                documents = state.documents.map {
                    if (it.assetId == doc.assetId) it.copy(bookmarks = updated) else it
                }
            )
        }
        viewModelScope.launch {
            repository.saveEncryptedReadingState(doc.assetId, page, reader.totalPages, updated)
        }
    }

    fun searchInActivePdf(query: String) {
        val file = _uiState.value.readerState.decryptedPdfFile ?: return
        _uiState.update {
            it.copy(readerState = it.readerState.copy(searchQuery = query, isSearching = true))
        }
        viewModelScope.launch {
            val hits = pdfHelper.searchTextInPdf(file, query)
            _uiState.update {
                it.copy(readerState = it.readerState.copy(searchHits = hits, isSearching = false))
            }
        }
    }

    fun closePdfReader() {
        repository.wipeDecryptedTempFiles()
        _uiState.update { it.copy(readerState = ReaderUiState()) }
    }

    fun exportDecryptedPdfToUri(record: VaultDocumentRecord, destinationUri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyMessage = if (it.isBangla) "ডিক্রিপ্ট ও সেভ হচ্ছে..." else "Decrypting & exporting PDF..."
                )
            }
            var tempDecrypted: File? = null
            try {
                val activeFile = _uiState.value.readerState.takeIf {
                    it.activeDocument?.assetId == record.assetId
                }?.decryptedPdfFile
                val sourceFile = activeFile ?: repository.decryptDocumentToTempPdf(record) { _, _ -> }.also {
                    tempDecrypted = it
                }
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(destinationUri)?.use { out ->
                        sourceFile.inputStream().use { inp -> inp.copyTo(out) }
                    }
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = null,
                        snackbarMessage = if (it.isBangla) "ডিক্রিপ্টেড পিডিএফ সেভ হয়েছে।" else "Decrypted PDF saved."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, busyMessage = null, snackbarMessage = mapFriendlyError(e))
                }
            } finally {
                tempDecrypted?.delete()
            }
        }
    }

    fun printDocument(record: VaultDocumentRecord) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyMessage = if (it.isBangla) "প্রিন্টের জন্য ডিক্রিপ্ট হচ্ছে..." else "Decrypting for print..."
                )
            }
            try {
                val activeFile = _uiState.value.readerState.takeIf {
                    it.activeDocument?.assetId == record.assetId
                }?.decryptedPdfFile
                val pdfFile = activeFile ?: repository.decryptDocumentToTempPdf(record) { _, _ -> }
                _uiState.update { it.copy(isBusy = false, busyMessage = null) }
                pdfHelper.printDecryptedPdf(getApplication(), pdfFile, record.descriptor.title)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, busyMessage = null, snackbarMessage = mapFriendlyError(e))
                }
            }
        }
    }

    fun toggleOfflineCache(record: VaultDocumentRecord) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyMessage = if (it.isBangla) "অফলাইন ক্যাশ আপডেট হচ্ছে..." else "Updating offline encrypted cache..."
                )
            }
            try {
                if (record.isOfflineCached) {
                    repository.removeDocumentOfflineCache(record)
                    _uiState.update { state ->
                        state.copy(
                            isBusy = false,
                            busyMessage = null,
                            documents = state.documents.map {
                                if (it.assetId == record.assetId) it.copy(isOfflineCached = false, localPvaultPath = null)
                                else it
                            }
                        )
                    }
                } else {
                    val path = repository.cacheDocumentOffline(record) {}
                    _uiState.update { state ->
                        state.copy(
                            isBusy = false,
                            busyMessage = null,
                            documents = state.documents.map {
                                if (it.assetId == record.assetId) it.copy(isOfflineCached = true, localPvaultPath = path)
                                else it
                            },
                            snackbarMessage = if (state.isBangla) "এনক্রিপ্টেড ফাইল অফলাইনের জন্য সংরক্ষিত হয়েছে।"
                            else "Encrypted file cached for offline unlocking & reading."
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, busyMessage = null, snackbarMessage = mapFriendlyError(e))
                }
            }
        }
    }

    fun toggleFavorite(record: VaultDocumentRecord) {
        updateDocumentMetadata(
            record = record,
            title = record.descriptor.title,
            description = record.descriptor.description,
            category = record.descriptor.category,
            tagsCsv = record.descriptor.tags.joinToString(", "),
            collection = record.descriptor.collection,
            isFavorite = !record.descriptor.isFavorite
        )
    }

    fun updateDocumentMetadata(
        record: VaultDocumentRecord,
        title: String,
        description: String,
        category: String,
        tagsCsv: String,
        collection: String,
        isFavorite: Boolean
    ) {
        val settings = _uiState.value.settings
        val tags = tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val updated = repository.updateDocumentMetadata(
                    owner = settings.githubOwner,
                    repo = settings.githubRepo,
                    record = record,
                    title = title,
                    description = description,
                    category = category,
                    tags = tags,
                    collection = collection,
                    isFavorite = isFavorite
                )
                _uiState.update { state ->
                    state.copy(
                        isBusy = false,
                        documents = state.documents.map { if (it.assetId == updated.assetId) updated else it },
                        snackbarMessage = if (state.isBangla) "মেটাডাটা আপডেট হয়েছে।" else "Metadata updated."
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, snackbarMessage = mapFriendlyError(e)) }
            }
        }
    }

    fun deleteDocument(record: VaultDocumentRecord, deleteFromGitHubPermanently: Boolean) {
        val settings = _uiState.value.settings
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                if (deleteFromGitHubPermanently) {
                    repository.deleteDocumentPermanentlyFromGitHub(settings.githubOwner, settings.githubRepo, record)
                } else {
                    repository.removeFromLocalLibraryViewOnly(record)
                }
                _uiState.update { state ->
                    state.copy(
                        isBusy = false,
                        documents = state.documents.filter { it.assetId != record.assetId },
                        snackbarMessage = if (state.isBangla) "ডকুমেন্ট সরানো হয়েছে।" else "Document removed."
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, snackbarMessage = mapFriendlyError(e)) }
            }
        }
    }

    fun changeMasterPassword(currentPassword: String, newPassword: String) {
        val settings = _uiState.value.settings
        val currentCfg = _uiState.value.activeVaultConfig ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val oldChars = currentPassword.toCharArray()
            val newChars = newPassword.toCharArray()
            try {
                // Verify current password before changing
                val verifiedKey = withContext(Dispatchers.Default) {
                    crypto.unlockWithPassword(oldChars, currentCfg)
                }
                crypto.zeroBytes(verifiedKey)

                val canPublish = session.getGitHubToken() != null && settings.githubOwner.isNotBlank()
                val kdfAlg = KdfAlgorithm.fromId(settings.kdfAlgorithm)
                val (newCfg, newJson) = withContext(Dispatchers.Default) {
                    repository.changeMasterPassword(
                        owner = settings.githubOwner,
                        repo = settings.githubRepo,
                        currentConfig = currentCfg,
                        newPassword = newChars,
                        kdfAlgorithm = kdfAlg,
                        memoryKiB = settings.argon2MemoryKiB,
                        iterations = if (kdfAlg == KdfAlgorithm.PBKDF2_SHA256)
                            VaultFormatConstants.PBKDF2_MIN_ITERATIONS else settings.argon2Iterations,
                        publishToGitHub = canPublish
                    )
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        activeVaultConfig = newCfg,
                        pendingExportConfigJson = if (!canPublish) newJson else null,
                        snackbarMessage = if (canPublish) {
                            if (it.isBangla) "মাস্টার পাসওয়ার্ড ও কনফিগ v${newCfg.configVersion} গিটহাবে প্রকাশিত হয়েছে।"
                            else "Master Password updated and config v${newCfg.configVersion} published."
                        } else {
                            if (it.isBangla) "নতুন কনফিগ (v${newCfg.configVersion}) তৈরি হয়েছে; ব্যাকআপ থেকে ডাউনলোড করে গিটহাবে আপলোড করুন।"
                            else "Updated config v${newCfg.configVersion} created; export and upload to vault-control."
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, snackbarMessage = mapFriendlyError(e)) }
            } finally {
                oldChars.fill('\u0000')
                newChars.fill('\u0000')
            }
        }
    }

    fun rotateEntireVaultKey(currentPassword: String, newPassword: String) {
        val settings = _uiState.value.settings
        val currentCfg = _uiState.value.activeVaultConfig ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyMessage = if (it.isBangla) "ভল্ট মাস্টার কী রোটেশন চলছে..." else "Rotating Vault Master Key & re-wrapping all DEKs..."
                )
            }
            val oldChars = currentPassword.toCharArray()
            val newChars = newPassword.toCharArray()
            try {
                val verified = withContext(Dispatchers.Default) {
                    crypto.unlockWithPassword(oldChars, currentCfg)
                }
                crypto.zeroBytes(verified)

                val canPublish = session.getGitHubToken() != null && settings.githubOwner.isNotBlank()
                val (newCfg, newJson, newRecKey) = withContext(Dispatchers.Default) {
                    repository.rotateEntireVaultKey(
                        owner = settings.githubOwner,
                        repo = settings.githubRepo,
                        currentConfig = currentCfg,
                        newPassword = newChars,
                        currentDocs = _uiState.value.documents,
                        publishToGitHub = canPublish
                    )
                }
                val reloadedDocs = repository.loadDecryptedLibraryFromCache(newCfg.vaultId)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = null,
                        activeVaultConfig = newCfg,
                        documents = reloadedDocs,
                        oneTimeRecoveryKey = newRecKey,
                        pendingExportConfigJson = if (!canPublish) newJson else null,
                        snackbarMessage = if (it.isBangla) "সম্পূর্ণ ভল্ট কী রোটেশন সম্পন্ন হয়েছে। নতুন রিকভারি কী সংরক্ষণ করুন।"
                        else "Full Vault Master Key rotation complete. Save your new Recovery Key!"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, busyMessage = null, snackbarMessage = mapFriendlyError(e))
                }
            } finally {
                oldChars.fill('\u0000')
                newChars.fill('\u0000')
            }
        }
    }

    fun restoreSidecarFromCache(issue: com.example.github.IncompleteAssetIssue) {
        val settings = _uiState.value.settings
        val vaultId = _uiState.value.activeVaultConfig?.vaultId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val ok = repository.restoreSidecarToGitHubFromLocalCache(
                    settings.githubOwner, settings.githubRepo, vaultId, issue
                )
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        snackbarMessage = if (ok) {
                            if (it.isBangla) "লোকাল ক্যাশ থেকে সাইডকার গিটহাবে পুনরুদ্ধার হয়েছে।"
                            else "Sidecar restored to GitHub from local encrypted cache."
                        } else {
                            if (it.isBangla) "লোকাল ক্যাশে মিল থাকা সাইডকার পাওয়া যায়নি।"
                            else "No matching encrypted sidecar found in local cache."
                        }
                    )
                }
                if (ok) syncWithGitHub(silent = true)
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, snackbarMessage = mapFriendlyError(e)) }
            }
        }
    }

    fun addCustomCategory(category: String) {
        viewModelScope.launch { preferences.addCustomCategory(category) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateCategoryFilter(category: String?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun updateTagFilter(tag: String?) {
        _uiState.update { it.copy(selectedTag = tag) }
    }

    fun updateSortOrder(order: LibrarySortOrder) {
        _uiState.update { it.copy(sortOrder = order) }
    }

    fun toggleGridView() {
        _uiState.update { it.copy(isGridView = !it.isGridView) }
    }

    fun writeTextToUri(uri: Uri, content: String, successMsg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                _uiState.update { it.copy(snackbarMessage = successMsg) }
            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = mapFriendlyError(e)) }
            }
        }
    }

    fun writeFileToUri(uri: Uri, sourceFilePath: String, successMsg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(sourceFilePath)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { inp -> inp.copyTo(out) }
                }
                _uiState.update { it.copy(snackbarMessage = successMsg) }
            } catch (e: Exception) {
                _uiState.update { it.copy(snackbarMessage = mapFriendlyError(e)) }
            }
        }
    }

    fun openGitHubReleasesInBrowser() {
        val s = _uiState.value.settings
        if (s.githubOwner.isBlank() || s.githubRepo.isBlank()) return
        val url = "https://github.com/${s.githubOwner}/${s.githubRepo}/releases"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { getApplication<Application>().startActivity(intent) }
    }

    private fun mapFriendlyError(e: Throwable): String {
        val bn = _uiState.value.isBangla
        return when (e) {
            is VaultCryptoException.InvalidCredentials -> _uiState.value.strings.wrongPasswordError
            is VaultCryptoException.InvalidRecoveryKey -> _uiState.value.strings.invalidRecoveryKeyError
            is VaultCryptoException.InvalidPdf -> if (bn) "অবৈধ বা নষ্ট পিডিএফ ফাইল (%PDF- হেডার নেই)।"
                else "Invalid PDF file (%PDF- signature missing)."
            is VaultCryptoException.AuthenticationFailed -> if (bn) "ক্রিপ্টোগ্রাফিক ট্যাগ যাচাই ব্যর্থ হয়েছে (ফাইল পরিবর্তিত বা ভুল কী)।"
                else "Cryptographic authentication tag mismatch."
            is VaultCryptoException.UnsupportedFormat -> if (bn) "অসমর্থিত ভল্ট বা ফাইল ফরম্যাট।"
                else "Unsupported vault or file format."
            is GitHubApiException.RepoNotFound -> if (bn) "গিটহাব রিপোজিটরি পাওয়া যায়নি।"
                else "GitHub repository not found."
            is GitHubApiException.ReleaseNotFound -> if (bn) "প্রয়োজনীয় গিটহাব রিলিজ বা কনফিগ পাওয়া যায়নি।"
                else "GitHub release or vault config not found."
            is GitHubApiException.Unauthorized -> if (bn) "গিটহাব টোকেন অবৈধ বা মেয়াদোত্তীর্ণ।"
                else "GitHub token invalid or expired (401)."
            is GitHubApiException.Forbidden -> if (bn) "রিপোজিটরিতে Contents Read/Write অনুমতি নেই।"
                else "Insufficient GitHub permissions (403)."
            is GitHubApiException.RateLimited -> if (bn) "গিটহাব API রেট লিমিট পূর্ণ হয়েছে; কিছুক্ষণ পর চেষ্টা করুন।"
                else "GitHub API rate limit exceeded. Please wait before retrying."
            is GitHubApiException.NetworkError -> if (bn) "নেটওয়ার্ক সংযোগ ব্যর্থ হয়েছে। ইন্টারনেট যাচাই করুন।"
                else "Network error while contacting GitHub."
            else -> e.message ?: (if (bn) "অপ্রত্যাশিত ত্রুটি ঘটেছে।" else "Unexpected error occurred.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.wipeDecryptedTempFiles()
        repository.wipeWorkTempFiles()
        session.clearAllSessionCredentials()
    }
}
