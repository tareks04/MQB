package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.crypto.CreatedVaultBundle
import com.example.crypto.EncryptedPdfPairResult
import com.example.crypto.KdfAlgorithm
import com.example.crypto.LocalEncryptedReadingState
import com.example.crypto.PdfDescriptor
import com.example.crypto.PvMetaEnvelope
import com.example.crypto.VaultConfig
import com.example.crypto.VaultCryptoEngine
import com.example.crypto.VaultCryptoException
import com.example.crypto.VaultFormatConstants
import com.example.github.GitHubApiClient
import com.example.github.GitHubApiException
import com.example.github.GitHubAssetDto
import com.example.github.GitHubReleaseDto
import com.example.github.IncompleteAssetIssue
import com.example.github.IncompleteIssueType
import com.example.github.SyncReport
import com.example.github.VaultDocumentRecord
import com.example.session.VaultSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class VaultRepository(
    private val context: Context,
    val crypto: VaultCryptoEngine,
    val github: GitHubApiClient,
    val session: VaultSessionManager,
    val preferences: VaultPreferences,
    private val database: VaultDatabase
) {
    private val dao = database.vaultDao()

    private val offlineVaultDir: File
        get() = File(context.filesDir, "offline_encrypted_vault").apply { mkdirs() }

    private val tempWorkDir: File
        get() = File(context.cacheDir, "vault_work_temp").apply { mkdirs() }

    private val decryptedTempDir: File
        get() = File(context.cacheDir, "decrypted_temp").apply { mkdirs() }

    val settingsFlow: Flow<PublicVaultSettings> = preferences.settingsFlow

    fun wipeDecryptedTempFiles() {
        decryptedTempDir.listFiles()?.forEach { file ->
            runCatching { file.delete() }
        }
    }

    fun wipeWorkTempFiles() {
        tempWorkDir.listFiles()?.forEach { file ->
            runCatching { file.delete() }
        }
    }

    suspend fun fetchRemoteVaultConfig(owner: String, repo: String): Pair<VaultConfig, String> {
        val controlRelease = github.getReleaseByTag(
            owner = owner,
            repo = repo,
            tag = VaultFormatConstants.CONTROL_RELEASE_TAG,
            token = session.getGitHubToken()
        ) ?: throw GitHubApiException.ReleaseNotFound("Control release '${VaultFormatConstants.CONTROL_RELEASE_TAG}' not found")

        val configAsset = selectHighestConfigAsset(controlRelease.assets)
            ?: throw GitHubApiException.ReleaseNotFound("No vault-config-v*.json found in ${VaultFormatConstants.CONTROL_RELEASE_TAG}")

        val json = github.downloadTextAsset(configAsset.browserDownloadUrl)
        val parsed = crypto.parseVaultConfig(json)
        preferences.saveCachedVaultConfigJson(json)
        return parsed to json
    }

    fun parseCachedConfigOrNull(json: String?): VaultConfig? {
        if (json.isNullOrBlank()) return null
        return runCatching { crypto.parseVaultConfig(json) }.getOrNull()
    }

    suspend fun initializeNewVault(
        owner: String,
        repo: String,
        password: CharArray,
        kdfAlgorithm: KdfAlgorithm,
        memoryKiB: Int,
        iterations: Int,
        publishToGitHub: Boolean
    ): CreatedVaultBundle = withContext(Dispatchers.IO) {
        val bundle = crypto.createNewVault(
            password = password,
            kdfAlgorithm = kdfAlgorithm,
            memoryKiB = memoryKiB,
            iterations = iterations
        )
        if (publishToGitHub) {
            val token = session.getGitHubToken()
                ?: throw GitHubApiException.Unauthorized("GitHub Fine-Grained PAT required to publish vault config")
            val existingControl = github.getReleaseByTag(
                owner, repo, VaultFormatConstants.CONTROL_RELEASE_TAG, token
            )
            if (existingControl != null && selectHighestConfigAsset(existingControl.assets) != null) {
                crypto.zeroBytes(bundle.masterKey)
                throw IllegalStateException("A vault already exists in this repository's vault-control release.")
            }
            val controlRelease = existingControl ?: github.createRelease(
                owner = owner,
                repo = repo,
                tag = VaultFormatConstants.CONTROL_RELEASE_TAG,
                name = "Vault Control Configuration",
                bodyText = "Encrypted Personal PDF Vault configuration releases (no plaintext secrets).",
                token = token
            )
            val filename = formatConfigFilename(bundle.config.configVersion)
            github.uploadReleaseAssetBytes(
                owner = owner,
                repo = repo,
                releaseId = controlRelease.id,
                assetName = filename,
                bytes = bundle.configJson.toByteArray(Charsets.UTF_8),
                token = token
            )
        }
        preferences.setGitHubRepoConfig(owner, repo)
        preferences.saveCachedVaultConfigJson(bundle.configJson)
        session.unlock(bundle.masterKey)
        bundle
    }

    suspend fun unlockWithPassword(
        password: CharArray,
        owner: String,
        repo: String,
        cachedConfigJson: String?
    ): VaultConfig {
        val (config, configJson) = resolveConfigForUnlock(owner, repo, cachedConfigJson)
        val masterKey = crypto.unlockWithPassword(password, config)
        try {
            verifyKeyAgainstCachedSidecars(config.vaultId, masterKey)
            preferences.saveCachedVaultConfigJson(configJson)
            session.unlock(masterKey)
            return config
        } finally {
            crypto.zeroBytes(masterKey)
        }
    }

    suspend fun unlockWithRecoveryKey(
        recoveryKey: String,
        owner: String,
        repo: String,
        cachedConfigJson: String?
    ): VaultConfig {
        val (config, configJson) = resolveConfigForUnlock(owner, repo, cachedConfigJson)
        val masterKey = crypto.unlockWithRecoveryKey(recoveryKey, config)
        try {
            verifyKeyAgainstCachedSidecars(config.vaultId, masterKey)
            preferences.saveCachedVaultConfigJson(configJson)
            session.unlock(masterKey)
            return config
        } finally {
            crypto.zeroBytes(masterKey)
        }
    }

    private suspend fun resolveConfigForUnlock(
        owner: String,
        repo: String,
        cachedConfigJson: String?
    ): Pair<VaultConfig, String> {
        if (owner.isNotBlank() && repo.isNotBlank()) {
            val remote = runCatching { fetchRemoteVaultConfig(owner, repo) }.getOrNull()
            if (remote != null) return remote
        }
        val local = cachedConfigJson?.let { json ->
            runCatching { crypto.parseVaultConfig(json) to json }.getOrNull()
        }
        return local ?: throw GitHubApiException.ReleaseNotFound(
            "No vault configuration found remotely or in local cache."
        )
    }

    private suspend fun verifyKeyAgainstCachedSidecars(vaultId: String, masterKey: ByteArray) {
        val cached = dao.getSidecarsForVault(vaultId)
        val sample = cached.firstOrNull() ?: return
        val env = crypto.parsePvMetaEnvelope(sample.encryptedMetaJson)
        crypto.decryptSidecarDescriptor(env, masterKey)
    }

    suspend fun loadDecryptedLibraryFromCache(vaultId: String): List<VaultDocumentRecord> =
        withContext(Dispatchers.IO) {
            val masterKey = session.getMasterKeyCopy() ?: return@withContext emptyList()
            try {
                val cachedList = dao.getSidecarsForVault(vaultId)
                val readingMap = dao.getAllReadingStates().associateBy { it.assetId }
                val records = mutableListOf<VaultDocumentRecord>()
                for (entity in cachedList) {
                    val env = runCatching { crypto.parsePvMetaEnvelope(entity.encryptedMetaJson) }.getOrNull()
                        ?: continue
                    val desc = runCatching { crypto.decryptSidecarDescriptor(env, masterKey) }.getOrNull()
                        ?: continue
                    val readingState = readingMap[entity.assetId]?.let { encState ->
                        runCatching {
                            crypto.decryptReadingState(entity.assetId, encState.encryptedStateBase64, masterKey)
                        }.getOrNull()
                    }
                    val offlineExists = entity.isOfflineCached &&
                        !entity.localPvaultPath.isNullOrBlank() &&
                        File(entity.localPvaultPath).exists()

                    records.add(
                        VaultDocumentRecord(
                            assetId = entity.assetId,
                            descriptor = desc,
                            releaseId = entity.releaseId,
                            releaseTag = entity.releaseTag,
                            pvaultAssetId = entity.pvaultAssetId,
                            pvmetaAssetId = entity.pvmetaAssetId,
                            pvaultDownloadUrl = entity.pvaultDownloadUrl,
                            pvmetaDownloadUrl = entity.pvmetaDownloadUrl,
                            pvaultSizeBytes = entity.pvaultSizeBytes,
                            pvmetaSizeBytes = entity.pvmetaSizeBytes,
                            isOfflineCached = offlineExists,
                            localPvaultPath = if (offlineExists) entity.localPvaultPath else null,
                            lastOpenedAtEpochMs = readingState?.lastOpenedAtEpochMs ?: 0L,
                            lastReadPage = readingState?.lastPage ?: 0,
                            totalPages = readingState?.totalPages ?: 0,
                            bookmarks = readingState?.bookmarks ?: emptyList(),
                            isLocalOnlyImport = entity.isLocalOnlyImport
                        )
                    )
                }
                records
            } finally {
                crypto.zeroBytes(masterKey)
            }
        }

    suspend fun syncWithGitHub(
        owner: String,
        repo: String,
        vaultId: String
    ): Pair<List<VaultDocumentRecord>, SyncReport> = withContext(Dispatchers.IO) {
        val masterKey = session.getMasterKeyCopy()
            ?: throw VaultCryptoException.InvalidCredentials("Vault is locked")
        try {
            val token = session.getGitHubToken()
            val allReleases = github.listAllReleasesWithAssets(owner, repo, token)

            // Refresh vault-control config if present
            val controlRelease = allReleases.firstOrNull {
                it.tagName == VaultFormatConstants.CONTROL_RELEASE_TAG
            }
            if (controlRelease != null) {
                selectHighestConfigAsset(controlRelease.assets)?.let { cfgAsset ->
                    runCatching {
                        val cfgJson = github.downloadTextAsset(cfgAsset.browserDownloadUrl)
                        crypto.parseVaultConfig(cfgJson)
                        preferences.saveCachedVaultConfigJson(cfgJson)
                    }
                }
            }

            val existingCacheByAssetId = dao.getSidecarsForVault(vaultId).associateBy { it.assetId }
            val dataReleases = allReleases.filter {
                it.tagName != VaultFormatConstants.CONTROL_RELEASE_TAG
            }

            val releaseAssetCounts = mutableMapOf<String, Int>()
            val pvaultByLogicalId = mutableMapOf<String, MutableList<Pair<GitHubReleaseDto, GitHubAssetDto>>>()
            val pvmetaByLogicalId = mutableMapOf<String, MutableList<Pair<GitHubReleaseDto, GitHubAssetDto>>>()

            for (release in dataReleases) {
                if (release.tagName.startsWith(VaultFormatConstants.DATA_RELEASE_PREFIX)) {
                    releaseAssetCounts[release.tagName] = release.assets.size
                }
                for (asset in release.assets) {
                    val lower = asset.name.lowercase()
                    when {
                        lower.endsWith(".pvault") -> {
                            val logical = extractLogicalId(asset.name, ".pvault")
                            pvaultByLogicalId.getOrPut(logical) { mutableListOf() }.add(release to asset)
                        }
                        lower.endsWith(".pvmeta") -> {
                            val logical = extractLogicalId(asset.name, ".pvmeta")
                            pvmetaByLogicalId.getOrPut(logical) { mutableListOf() }.add(release to asset)
                        }
                    }
                }
            }

            val incompleteIssues = mutableListOf<IncompleteAssetIssue>()
            var duplicateCount = 0
            var invalidCount = 0
            var newCount = 0
            var existingCount = 0
            var totalEncryptedBytes = 0L

            val allLogicalIds = (pvaultByLogicalId.keys + pvmetaByLogicalId.keys).toSortedSet()
            val seenSha256 = mutableSetOf<String>()
            val discoveredEntities = mutableListOf<CachedSidecarEntity>()

            for (logicalId in allLogicalIds) {
                val pvaultEntries = pvaultByLogicalId[logicalId].orEmpty()
                val pvmetaEntries = pvmetaByLogicalId[logicalId].orEmpty()

                if (pvaultEntries.isNotEmpty() && pvmetaEntries.isEmpty()) {
                    val (rel, ast) = pvaultEntries.first()
                    totalEncryptedBytes += ast.size
                    incompleteIssues.add(
                        IncompleteAssetIssue(
                            logicalId = logicalId,
                            filename = ast.name,
                            releaseTag = rel.tagName,
                            assetId = ast.id,
                            sizeBytes = ast.size,
                            downloadUrl = ast.browserDownloadUrl,
                            issueType = IncompleteIssueType.MISSING_SIDECAR
                        )
                    )
                    continue
                }
                if (pvmetaEntries.isNotEmpty() && pvaultEntries.isEmpty()) {
                    val (rel, ast) = pvmetaEntries.first()
                    totalEncryptedBytes += ast.size
                    incompleteIssues.add(
                        IncompleteAssetIssue(
                            logicalId = logicalId,
                            filename = ast.name,
                            releaseTag = rel.tagName,
                            assetId = ast.id,
                            sizeBytes = ast.size,
                            downloadUrl = ast.browserDownloadUrl,
                            issueType = IncompleteIssueType.MISSING_PVAULT
                        )
                    )
                    continue
                }

                if (pvaultEntries.size > 1 || pvmetaEntries.size > 1) {
                    duplicateCount++
                    val (rel, ast) = pvaultEntries.last()
                    incompleteIssues.add(
                        IncompleteAssetIssue(
                            logicalId = logicalId,
                            filename = ast.name,
                            releaseTag = rel.tagName,
                            assetId = ast.id,
                            sizeBytes = ast.size,
                            downloadUrl = ast.browserDownloadUrl,
                            issueType = IncompleteIssueType.DUPLICATE_ASSET
                        )
                    )
                }

                val (pvaultRelease, pvaultAsset) = pvaultEntries.first()
                val (_, pvmetaAsset) = pvmetaEntries.first()

                val metaJson = try {
                    github.downloadTextAsset(pvmetaAsset.browserDownloadUrl)
                } catch (_: Exception) {
                    existingCacheByAssetId.values.firstOrNull {
                        it.pvmetaAssetId == pvmetaAsset.id
                    }?.encryptedMetaJson
                }

                if (metaJson.isNullOrBlank()) {
                    invalidCount++
                    continue
                }

                val envelope = runCatching { crypto.parsePvMetaEnvelope(metaJson) }.getOrNull()
                if (envelope == null || envelope.vaultId != vaultId) {
                    invalidCount++
                    incompleteIssues.add(
                        IncompleteAssetIssue(
                            logicalId = logicalId,
                            filename = pvmetaAsset.name,
                            releaseTag = pvaultRelease.tagName,
                            assetId = pvmetaAsset.id,
                            sizeBytes = pvmetaAsset.size,
                            downloadUrl = pvmetaAsset.browserDownloadUrl,
                            issueType = IncompleteIssueType.CORRUPTED_OR_FOREIGN_SIDECAR
                        )
                    )
                    continue
                }

                val descriptor = runCatching {
                    crypto.decryptSidecarDescriptor(envelope, masterKey)
                }.getOrNull()

                if (descriptor == null || descriptor.assetId != envelope.assetId) {
                    invalidCount++
                    incompleteIssues.add(
                        IncompleteAssetIssue(
                            logicalId = logicalId,
                            filename = pvmetaAsset.name,
                            releaseTag = pvaultRelease.tagName,
                            assetId = pvmetaAsset.id,
                            sizeBytes = pvmetaAsset.size,
                            downloadUrl = pvmetaAsset.browserDownloadUrl,
                            issueType = IncompleteIssueType.CORRUPTED_OR_FOREIGN_SIDECAR
                        )
                    )
                    continue
                }

                if (descriptor.plaintextSha256.isNotBlank() && !seenSha256.add(descriptor.plaintextSha256)) {
                    duplicateCount++
                }

                val previous = existingCacheByAssetId[descriptor.assetId]
                if (previous == null) newCount++ else existingCount++
                totalEncryptedBytes += pvaultAsset.size + pvmetaAsset.size

                discoveredEntities.add(
                    CachedSidecarEntity(
                        assetId = descriptor.assetId,
                        vaultId = vaultId,
                        encryptedMetaJson = metaJson,
                        releaseId = pvaultRelease.id,
                        releaseTag = pvaultRelease.tagName,
                        pvaultAssetId = pvaultAsset.id,
                        pvmetaAssetId = pvmetaAsset.id,
                        pvaultDownloadUrl = pvaultAsset.browserDownloadUrl,
                        pvmetaDownloadUrl = pvmetaAsset.browserDownloadUrl,
                        pvaultSizeBytes = pvaultAsset.size,
                        pvmetaSizeBytes = pvmetaAsset.size,
                        isOfflineCached = previous?.isOfflineCached ?: false,
                        localPvaultPath = previous?.localPvaultPath,
                        isLocalOnlyImport = false
                    )
                )
            }

            if (discoveredEntities.isEmpty()) {
                dao.deleteAllCloudSidecars(vaultId)
            } else {
                dao.upsertSidecars(discoveredEntities)
                dao.pruneRemovedCloudSidecars(vaultId, discoveredEntities.map { it.assetId })
            }

            val nearLimitRelease = releaseAssetCounts.entries
                .firstOrNull { it.value >= VaultFormatConstants.RELEASE_ASSET_WARN_COUNT }
            val warning = nearLimitRelease?.let {
                "Release ${it.key} has ${it.value}/1000 assets."
            }

            val records = loadDecryptedLibraryFromCache(vaultId)
            val report = SyncReport(
                syncedAtEpochMs = System.currentTimeMillis(),
                newCount = newCount,
                existingCount = existingCount,
                incompleteIssues = incompleteIssues,
                duplicateCount = duplicateCount,
                invalidCount = invalidCount,
                totalEncryptedBytes = totalEncryptedBytes,
                releaseAssetCounts = releaseAssetCounts,
                warningMessage = warning
            )
            records to report
        } finally {
            crypto.zeroBytes(masterKey)
        }
    }

    suspend fun checkPdfBeforeEncryption(uri: Uri, existingDocs: List<VaultDocumentRecord>): PdfInspectionResult =
        withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            var displayName = "document.pdf"
            var sizeBytes = -1L
            cr.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                    if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                }
            }
            if (sizeBytes <= 0L) {
                cr.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    sizeBytes = afd.length
                }
            }
            // Validate %PDF- header
            cr.openInputStream(uri)?.use { stream ->
                val header = ByteArray(5)
                val read = stream.read(header)
                if (read < 5 || !header.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) {
                    throw VaultCryptoException.InvalidPdf("Selected file is not a valid PDF (%PDF- signature missing)")
                }
            } ?: throw VaultCryptoException.InvalidPdf("Unable to open selected file")

            val sha256 = cr.openInputStream(uri)?.let { crypto.computeInputSha256(it) } ?: ""
            val duplicate = existingDocs.firstOrNull {
                it.descriptor.plaintextSha256.isNotBlank() &&
                    it.descriptor.plaintextSha256.equals(sha256, ignoreCase = true)
            }
            PdfInspectionResult(
                filename = displayName,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                duplicateTitle = duplicate?.descriptor?.title
            )
        }

    suspend fun encryptAndUploadPdf(
        uri: Uri,
        owner: String,
        repo: String,
        vaultId: String,
        originalFilename: String,
        originalSizeBytes: Long,
        title: String,
        description: String,
        category: String,
        tags: List<String>,
        collection: String,
        onStageProgress: (String, Float) -> Unit
    ): VaultDocumentRecord = withContext(Dispatchers.IO) {
        val masterKey = session.getMasterKeyCopy()
            ?: throw VaultCryptoException.InvalidCredentials("Vault is locked")
        val token = session.getGitHubToken()
            ?: throw GitHubApiException.Unauthorized("GitHub Fine-Grained PAT required for direct upload")

        var encryptedPair: EncryptedPdfPairResult? = null
        try {
            onStageProgress("encrypting", 0f)
            encryptedPair = crypto.encryptPdfFileChunked(
                inputStreamProvider = {
                    context.contentResolver.openInputStream(uri)
                        ?: throw VaultCryptoException.InvalidPdf("Cannot read source PDF")
                },
                outputTempDir = tempWorkDir,
                masterKey = masterKey,
                vaultId = vaultId,
                originalFilename = originalFilename,
                title = title,
                description = description,
                category = category,
                tags = tags,
                collection = collection,
                originalSizeBytes = originalSizeBytes,
                onProgress = { onStageProgress("encrypting", it) }
            )

            val pvaultFile = File(encryptedPair.pvaultTempFilePath)
            val targetRelease = findOrCreateDataRelease(owner, repo, token)

            onStageProgress("uploading_pvault", 0f)
            val uploadedPvault = github.uploadReleaseAssetFile(
                owner = owner,
                repo = repo,
                releaseId = targetRelease.id,
                assetName = encryptedPair.pvaultFilename,
                file = pvaultFile,
                token = token,
                onProgress = { onStageProgress("uploading_pvault", it) }
            )

            onStageProgress("uploading_pvmeta", 0.5f)
            val uploadedPvmeta = try {
                github.uploadReleaseAssetBytes(
                    owner = owner,
                    repo = repo,
                    releaseId = targetRelease.id,
                    assetName = encryptedPair.pvmetaFilename,
                    bytes = encryptedPair.pvmetaJson.toByteArray(Charsets.UTF_8),
                    token = token
                )
            } catch (e: Exception) {
                // Clean up half-uploaded .pvault if .pvmeta fails so no orphan stays behind unless cleanup fails
                runCatching { github.deleteReleaseAsset(owner, repo, uploadedPvault.id, token) }
                throw e
            }

            val entity = CachedSidecarEntity(
                assetId = encryptedPair.assetId,
                vaultId = vaultId,
                encryptedMetaJson = encryptedPair.pvmetaJson,
                releaseId = targetRelease.id,
                releaseTag = targetRelease.tagName,
                pvaultAssetId = uploadedPvault.id,
                pvmetaAssetId = uploadedPvmeta.id,
                pvaultDownloadUrl = uploadedPvault.browserDownloadUrl,
                pvmetaDownloadUrl = uploadedPvmeta.browserDownloadUrl,
                pvaultSizeBytes = uploadedPvault.size,
                pvmetaSizeBytes = uploadedPvmeta.size
            )
            dao.upsertSidecar(entity)

            VaultDocumentRecord(
                assetId = encryptedPair.assetId,
                descriptor = encryptedPair.descriptor,
                releaseId = targetRelease.id,
                releaseTag = targetRelease.tagName,
                pvaultAssetId = uploadedPvault.id,
                pvmetaAssetId = uploadedPvmeta.id,
                pvaultDownloadUrl = uploadedPvault.browserDownloadUrl,
                pvmetaDownloadUrl = uploadedPvmeta.browserDownloadUrl,
                pvaultSizeBytes = uploadedPvault.size,
                pvmetaSizeBytes = uploadedPvmeta.size
            )
        } finally {
            crypto.zeroBytes(masterKey)
            encryptedPair?.pvaultTempFilePath?.let { runCatching { File(it).delete() } }
        }
    }

    suspend fun prepareEncryptedPairOnly(
        uri: Uri,
        vaultId: String,
        originalFilename: String,
        originalSizeBytes: Long,
        title: String,
        description: String,
        category: String,
        tags: List<String>,
        collection: String,
        onProgress: (Float) -> Unit
    ): EncryptedPdfPairResult = withContext(Dispatchers.IO) {
        val masterKey = session.getMasterKeyCopy()
            ?: throw VaultCryptoException.InvalidCredentials("Vault is locked")
        try {
            crypto.encryptPdfFileChunked(
                inputStreamProvider = {
                    context.contentResolver.openInputStream(uri)
                        ?: throw VaultCryptoException.InvalidPdf("Cannot read source PDF")
                },
                outputTempDir = tempWorkDir,
                masterKey = masterKey,
                vaultId = vaultId,
                originalFilename = originalFilename,
                title = title,
                description = description,
                category = category,
                tags = tags,
                collection = collection,
                originalSizeBytes = originalSizeBytes,
                onProgress = onProgress
            )
        } finally {
            crypto.zeroBytes(masterKey)
        }
    }

    suspend fun importEncryptedPairFromUris(
        pvaultUri: Uri,
        pvmetaUri: Uri,
        vaultId: String
    ): VaultDocumentRecord = withContext(Dispatchers.IO) {
        val masterKey = session.getMasterKeyCopy()
            ?: throw VaultCryptoException.InvalidCredentials("Vault is locked")
        try {
            val metaJson = context.contentResolver.openInputStream(pvmetaUri)?.use {
                it.bufferedReader(Charsets.UTF_8).readText()
            } ?: throw VaultCryptoException.CorruptedConfig("Cannot read .pvmeta file")

            val envelope = crypto.parsePvMetaEnvelope(metaJson)
            if (envelope.vaultId != vaultId) {
                throw VaultCryptoException.AuthenticationFailed("Imported .pvmeta belongs to a different Vault ID")
            }
            val descriptor = crypto.decryptSidecarDescriptor(envelope, masterKey)

            val localPvault = File(offlineVaultDir, descriptor.encryptedAssetFilename)
            context.contentResolver.openInputStream(pvaultUri)?.use { input ->
                FileOutputStream(localPvault).use { out ->
                    input.copyTo(out)
                }
            } ?: throw VaultCryptoException.InvalidPdf("Cannot read .pvault file")

            // Verify decryption integrity on a temp probe file before accepting import
            val verifyOut = File(decryptedTempDir, "verify_${descriptor.assetId}.pdf")
            try {
                crypto.decryptPdfFileChunked(localPvault, verifyOut, descriptor, masterKey)
            } catch (e: Exception) {
                localPvault.delete()
                throw e
            } finally {
                verifyOut.delete()
            }

            val entity = CachedSidecarEntity(
                assetId = descriptor.assetId,
                vaultId = vaultId,
                encryptedMetaJson = metaJson,
                releaseId = 0L,
                releaseTag = "local-import",
                pvaultAssetId = 0L,
                pvmetaAssetId = 0L,
                pvaultDownloadUrl = "",
                pvmetaDownloadUrl = "",
                pvaultSizeBytes = localPvault.length(),
                pvmetaSizeBytes = metaJson.toByteArray(Charsets.UTF_8).size.toLong(),
                isOfflineCached = true,
                localPvaultPath = localPvault.absolutePath,
                isLocalOnlyImport = true
            )
            dao.upsertSidecar(entity)

            VaultDocumentRecord(
                assetId = descriptor.assetId,
                descriptor = descriptor,
                releaseId = 0L,
                releaseTag = "local-import",
                pvaultAssetId = 0L,
                pvmetaAssetId = 0L,
                pvaultDownloadUrl = "",
                pvmetaDownloadUrl = "",
                pvaultSizeBytes = localPvault.length(),
                pvmetaSizeBytes = entity.pvmetaSizeBytes,
                isOfflineCached = true,
                localPvaultPath = localPvault.absolutePath,
                isLocalOnlyImport = true
            )
        } finally {
            crypto.zeroBytes(masterKey)
        }
    }

    suspend fun decryptDocumentToTempPdf(
        record: VaultDocumentRecord,
        onStageProgress: (String, Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val masterKey = session.getMasterKeyCopy()
            ?: throw VaultCryptoException.InvalidCredentials("Vault is locked")
        val tempEncrypted = File(tempWorkDir, "dl_${record.assetId}.pvault")
        val decryptedFile = File(decryptedTempDir, "${record.assetId}.pdf")
        try {
            val cachedFile = record.localPvaultPath?.let { File(it) }?.takeIf { it.exists() }
            val sourcePvault = if (cachedFile != null) {
                cachedFile
            } else {
                if (record.pvaultDownloadUrl.isBlank()) {
                    throw GitHubApiException.ReleaseNotFound("Encrypted PDF file not cached and has no cloud URL")
                }
                onStageProgress("downloading", 0f)
                github.downloadBinaryAssetToFile(
                    downloadUrl = record.pvaultDownloadUrl,
                    destinationFile = tempEncrypted,
                    expectedSize = record.pvaultSizeBytes,
                    onProgress = { onStageProgress("downloading", it) }
                )
                tempEncrypted
            }

            onStageProgress("decrypting", 0f)
            crypto.decryptPdfFileChunked(
                encryptedPvaultFile = sourcePvault,
                outputPdfFile = decryptedFile,
                descriptor = record.descriptor,
                masterKey = masterKey,
                onProgress = { onStageProgress("decrypting", it) }
            )
            decryptedFile
        } finally {
            crypto.zeroBytes(masterKey)
            tempEncrypted.delete()
        }
    }

    suspend fun cacheDocumentOffline(
        record: VaultDocumentRecord,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val destFile = File(offlineVaultDir, record.descriptor.encryptedAssetFilename)
        github.downloadBinaryAssetToFile(
            downloadUrl = record.pvaultDownloadUrl,
            destinationFile = destFile,
            expectedSize = record.pvaultSizeBytes,
            onProgress = onProgress
        )
        dao.updateOfflineCacheStatus(record.assetId, true, destFile.absolutePath)
        destFile.absolutePath
    }

    suspend fun removeDocumentOfflineCache(record: VaultDocumentRecord) = withContext(Dispatchers.IO) {
        record.localPvaultPath?.let { runCatching { File(it).delete() } }
        dao.updateOfflineCacheStatus(record.assetId, false, null)
    }

    suspend fun saveEncryptedReadingState(
        assetId: String,
        lastPage: Int,
        totalPages: Int,
        bookmarks: List<Int>
    ) = withContext(Dispatchers.IO) {
        val masterKey = session.getMasterKeyCopy() ?: return@withContext
        try {
            val state = LocalEncryptedReadingState(
                assetId = assetId,
                lastPage = lastPage,
                totalPages = totalPages,
                lastOpenedAtEpochMs = System.currentTimeMillis(),
                bookmarks = bookmarks.distinct().sorted()
            )
            val encrypted = crypto.encryptReadingState(state, masterKey)
            dao.upsertReadingState(
                EncryptedReadingStateEntity(
                    assetId = assetId,
                    encryptedStateBase64 = encrypted
                )
            )
        } finally {
            crypto.zeroBytes(masterKey)
        }
    }

    suspend fun updateDocumentMetadata(
        owner: String,
        repo: String,
        record: VaultDocumentRecord,
        title: String,
        description: String,
        category: String,
        tags: List<String>,
        collection: String,
        isFavorite: Boolean
    ): VaultDocumentRecord = withContext(Dispatchers.IO) {
        val masterKey = session.getMasterKeyCopy()
            ?: throw VaultCryptoException.InvalidCredentials("Vault is locked")
        try {
            val updatedDescriptor = record.descriptor.copy(
                title = title.trim().ifEmpty { record.descriptor.title },
                description = description.trim(),
                category = category.trim().ifEmpty { "Other" },
                tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                collection = collection.trim(),
                isFavorite = isFavorite
            )
            val newEnvelope = crypto.encryptDescriptorToSidecar(updatedDescriptor, masterKey)
            val newJson = crypto.serializePvMetaEnvelope(newEnvelope)

            var newPvmetaAssetId = record.pvmetaAssetId
            var newPvmetaUrl = record.pvmetaDownloadUrl
            var newPvmetaSize = newJson.toByteArray(Charsets.UTF_8).size.toLong()

            if (!record.isLocalOnlyImport && record.releaseId > 0L) {
                val token = session.getGitHubToken()
                    ?: throw GitHubApiException.Unauthorized("GitHub Fine-Grained PAT required to update cloud metadata")
                if (record.pvmetaAssetId > 0L) {
                    github.deleteReleaseAsset(owner, repo, record.pvmetaAssetId, token)
                }
                val uploaded = github.uploadReleaseAssetBytes(
                    owner = owner,
                    repo = repo,
                    releaseId = record.releaseId,
                    assetName = updatedDescriptor.encryptedMetaFilename,
                    bytes = newJson.toByteArray(Charsets.UTF_8),
                    token = token
                )
                newPvmetaAssetId = uploaded.id
                newPvmetaUrl = uploaded.browserDownloadUrl
                newPvmetaSize = uploaded.size
            }

            val existing = dao.getSidecar(record.assetId)
            if (existing != null) {
                dao.upsertSidecar(
                    existing.copy(
                        encryptedMetaJson = newJson,
                        pvmetaAssetId = newPvmetaAssetId,
                        pvmetaDownloadUrl = newPvmetaUrl,
                        pvmetaSizeBytes = newPvmetaSize,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }

            record.copy(
                descriptor = updatedDescriptor,
                pvmetaAssetId = newPvmetaAssetId,
                pvmetaDownloadUrl = newPvmetaUrl,
                pvmetaSizeBytes = newPvmetaSize
            )
        } finally {
            crypto.zeroBytes(masterKey)
        }
    }

    suspend fun deleteDocumentPermanentlyFromGitHub(
        owner: String,
        repo: String,
        record: VaultDocumentRecord
    ) = withContext(Dispatchers.IO) {
        if (!record.isLocalOnlyImport) {
            val token = session.getGitHubToken()
                ?: throw GitHubApiException.Unauthorized("GitHub Fine-Grained PAT required to delete cloud assets")
            var pvaultDeleted = false
            try {
                if (record.pvaultAssetId > 0L) {
                    github.deleteReleaseAsset(owner, repo, record.pvaultAssetId, token)
                    pvaultDeleted = true
                }
                if (record.pvmetaAssetId > 0L) {
                    github.deleteReleaseAsset(owner, repo, record.pvmetaAssetId, token)
                }
            } catch (e: Exception) {
                if (pvaultDeleted) {
                    throw IllegalStateException("Partial deletion: .pvault was deleted but .pvmeta deletion failed (${e.message}). Sync to inspect orphaned asset.")
                }
                throw e
            }
        }
        record.localPvaultPath?.let { runCatching { File(it).delete() } }
        dao.deleteSidecar(record.assetId)
    }

    suspend fun removeFromLocalLibraryViewOnly(record: VaultDocumentRecord) = withContext(Dispatchers.IO) {
        record.localPvaultPath?.let { runCatching { File(it).delete() } }
        dao.deleteSidecar(record.assetId)
    }

    suspend fun changeMasterPassword(
        owner: String,
        repo: String,
        currentConfig: VaultConfig,
        newPassword: CharArray,
        kdfAlgorithm: KdfAlgorithm,
        memoryKiB: Int,
        iterations: Int,
        publishToGitHub: Boolean
    ): Pair<VaultConfig, String> = withContext(Dispatchers.IO) {
        val masterKey = session.getMasterKeyCopy()
            ?: throw VaultCryptoException.InvalidCredentials("Vault is locked")
        try {
            val (newConfig, newConfigJson) = crypto.rewrapPassword(
                masterKey = masterKey,
                newPassword = newPassword,
                oldConfig = currentConfig,
                kdfAlgorithm = kdfAlgorithm,
                memoryKiB = memoryKiB,
                iterations = iterations
            )
            if (publishToGitHub) {
                publishVaultConfigToControlRelease(owner, repo, newConfig, newConfigJson)
            }
            preferences.saveCachedVaultConfigJson(newConfigJson)
            newConfig to newConfigJson
        } finally {
            crypto.zeroBytes(masterKey)
        }
    }

    suspend fun rotateEntireVaultKey(
        owner: String,
        repo: String,
        currentConfig: VaultConfig,
        newPassword: CharArray,
        currentDocs: List<VaultDocumentRecord>,
        publishToGitHub: Boolean
    ): Triple<VaultConfig, String, String> = withContext(Dispatchers.IO) {
        val oldMasterKey = session.getMasterKeyCopy()
            ?: throw VaultCryptoException.InvalidCredentials("Vault is locked")
        try {
            val rotation = crypto.rotateEntireVaultKey(
                oldMasterKey = oldMasterKey,
                newPassword = newPassword,
                oldConfig = currentConfig,
                existingDescriptors = currentDocs.map { it.descriptor }
            )
            val token = if (publishToGitHub) {
                session.getGitHubToken()
                    ?: throw GitHubApiException.Unauthorized("GitHub Fine-Grained PAT required for cloud key rotation")
            } else null

            // Update all sidecars locally and on GitHub before publishing new config
            for (doc in currentDocs) {
                val pair = rotation.updatedSidecars[doc.assetId] ?: continue
                val newMetaJson = crypto.serializePvMetaEnvelope(pair.second)
                var newMetaAssetId = doc.pvmetaAssetId
                var newMetaUrl = doc.pvmetaDownloadUrl
                var newMetaSize = newMetaJson.toByteArray(Charsets.UTF_8).size.toLong()

                if (publishToGitHub && token != null && !doc.isLocalOnlyImport && doc.releaseId > 0L) {
                    if (doc.pvmetaAssetId > 0L) {
                        github.deleteReleaseAsset(owner, repo, doc.pvmetaAssetId, token)
                    }
                    val uploaded = github.uploadReleaseAssetBytes(
                        owner = owner,
                        repo = repo,
                        releaseId = doc.releaseId,
                        assetName = pair.first,
                        bytes = newMetaJson.toByteArray(Charsets.UTF_8),
                        token = token
                    )
                    newMetaAssetId = uploaded.id
                    newMetaUrl = uploaded.browserDownloadUrl
                    newMetaSize = uploaded.size
                }

                dao.getSidecar(doc.assetId)?.let { existing ->
                    dao.upsertSidecar(
                        existing.copy(
                            encryptedMetaJson = newMetaJson,
                            pvmetaAssetId = newMetaAssetId,
                            pvmetaDownloadUrl = newMetaUrl,
                            pvmetaSizeBytes = newMetaSize,
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                }
            }

            if (publishToGitHub) {
                publishVaultConfigToControlRelease(owner, repo, rotation.newConfig, rotation.newConfigJson)
            }
            preferences.saveCachedVaultConfigJson(rotation.newConfigJson)
            session.replaceMasterKeyInSession(rotation.newMasterKey)
            crypto.zeroBytes(rotation.newMasterKey)

            Triple(rotation.newConfig, rotation.newConfigJson, rotation.newRecoveryKeyFormatted)
        } finally {
            crypto.zeroBytes(oldMasterKey)
        }
    }

    suspend fun restoreSidecarToGitHubFromLocalCache(
        owner: String,
        repo: String,
        vaultId: String,
        issue: IncompleteAssetIssue
    ): Boolean = withContext(Dispatchers.IO) {
        val token = session.getGitHubToken()
            ?: throw GitHubApiException.Unauthorized("GitHub Fine-Grained PAT required")
        val masterKey = session.getMasterKeyCopy() ?: return@withContext false
        try {
            val sidecars = dao.getSidecarsForVault(vaultId)
            val match = sidecars.firstOrNull { entity ->
                val env = runCatching { crypto.parsePvMetaEnvelope(entity.encryptedMetaJson) }.getOrNull()
                val desc = env?.let { runCatching { crypto.decryptSidecarDescriptor(it, masterKey) }.getOrNull() }
                desc?.encryptedAssetFilename == issue.filename
            } ?: return@withContext false

            val env = crypto.parsePvMetaEnvelope(match.encryptedMetaJson)
            val desc = crypto.decryptSidecarDescriptor(env, masterKey)
            val release = github.getReleaseByTag(owner, repo, issue.releaseTag, token)
                ?: return@withContext false
            github.uploadReleaseAssetBytes(
                owner = owner,
                repo = repo,
                releaseId = release.id,
                assetName = desc.encryptedMetaFilename,
                bytes = match.encryptedMetaJson.toByteArray(Charsets.UTF_8),
                token = token
            )
            true
        } finally {
            crypto.zeroBytes(masterKey)
        }
    }

    private suspend fun publishVaultConfigToControlRelease(
        owner: String,
        repo: String,
        config: VaultConfig,
        configJson: String
    ) {
        val token = session.getGitHubToken()
            ?: throw GitHubApiException.Unauthorized("GitHub Fine-Grained PAT required")
        val controlRelease = github.getReleaseByTag(
            owner, repo, VaultFormatConstants.CONTROL_RELEASE_TAG, token
        ) ?: github.createRelease(
            owner = owner,
            repo = repo,
            tag = VaultFormatConstants.CONTROL_RELEASE_TAG,
            name = "Vault Control Configuration",
            bodyText = "Encrypted Personal PDF Vault configuration releases.",
            token = token
        )
        val filename = formatConfigFilename(config.configVersion)
        github.uploadReleaseAssetBytes(
            owner = owner,
            repo = repo,
            releaseId = controlRelease.id,
            assetName = filename,
            bytes = configJson.toByteArray(Charsets.UTF_8),
            token = token
        )
    }

    private suspend fun findOrCreateDataRelease(
        owner: String,
        repo: String,
        token: String
    ): GitHubReleaseDto {
        val all = github.listAllReleasesWithAssets(owner, repo, token)
        val dataReleases = all.filter {
            it.tagName.startsWith(VaultFormatConstants.DATA_RELEASE_PREFIX)
        }.sortedBy { it.tagName }

        val available = dataReleases.lastOrNull {
            it.assets.size < VaultFormatConstants.RELEASE_ASSET_HARD_LIMIT
        }
        if (available != null) return available

        val nextNumber = (dataReleases.mapNotNull {
            it.tagName.removePrefix(VaultFormatConstants.DATA_RELEASE_PREFIX).toIntOrNull()
        }.maxOrNull() ?: 0) + 1
        val nextTag = "${VaultFormatConstants.DATA_RELEASE_PREFIX}%03d".format(nextNumber)
        return github.createRelease(
            owner = owner,
            repo = repo,
            tag = nextTag,
            name = "PDF Vault Data $nextNumber",
            bodyText = "Encrypted .pvault and .pvmeta assets.",
            token = token
        )
    }

    fun getBundledSampleSizeBytes(): Long {
        val fdLength = runCatching {
            context.assets.openFd(BUNDLED_SAMPLE_ASSET_NAME).use { it.length }
        }.getOrDefault(-1L)
        if (fdLength > 0L) return fdLength

        var total = 0L
        val buf = ByteArray(64 * 1024)
        context.assets.open(BUNDLED_SAMPLE_ASSET_NAME).use { stream ->
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                total += read
            }
        }
        return total
    }

    suspend fun inspectBundledSample(existingDocs: List<VaultDocumentRecord>): PdfInspectionResult =
        withContext(Dispatchers.IO) {
            val sizeBytes = getBundledSampleSizeBytes()
            val sha256 = context.assets.open(BUNDLED_SAMPLE_ASSET_NAME).use {
                crypto.computeInputSha256(it)
            }
            val duplicate = existingDocs.firstOrNull {
                it.descriptor.plaintextSha256.isNotBlank() &&
                    it.descriptor.plaintextSha256.equals(sha256, ignoreCase = true)
            }
            PdfInspectionResult(
                filename = BUNDLED_SAMPLE_ORIGINAL_FILENAME,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                duplicateTitle = duplicate?.descriptor?.title
            )
        }

    suspend fun importBundledSamplePdf(
        vaultId: String,
        title: String,
        description: String,
        category: String,
        tags: List<String>,
        collection: String,
        existingDocs: List<VaultDocumentRecord>,
        onProgress: (Float) -> Unit = {}
    ): Pair<VaultDocumentRecord, Boolean> = withContext(Dispatchers.IO) {
        val masterKey = session.getMasterKeyCopy()
            ?: throw VaultCryptoException.InvalidCredentials("Vault is locked")
        var encryptedPair: EncryptedPdfPairResult? = null
        try {
            val sha256 = context.assets.open(BUNDLED_SAMPLE_ASSET_NAME).use {
                crypto.computeInputSha256(it)
            }
            val existing = existingDocs.firstOrNull {
                it.descriptor.plaintextSha256.isNotBlank() &&
                    it.descriptor.plaintextSha256.equals(sha256, ignoreCase = true)
            }
            if (existing != null) {
                return@withContext existing to true
            }

            val sizeBytes = getBundledSampleSizeBytes()
            encryptedPair = crypto.encryptPdfFileChunked(
                inputStreamProvider = { context.assets.open(BUNDLED_SAMPLE_ASSET_NAME) },
                outputTempDir = tempWorkDir,
                masterKey = masterKey,
                vaultId = vaultId,
                originalFilename = BUNDLED_SAMPLE_ORIGINAL_FILENAME,
                title = title,
                description = description,
                category = category,
                tags = tags,
                collection = collection,
                originalSizeBytes = sizeBytes,
                onProgress = onProgress
            )

            val tempFile = File(encryptedPair.pvaultTempFilePath)
            val persistentFile = File(offlineVaultDir, encryptedPair.pvaultFilename)
            if (!tempFile.renameTo(persistentFile)) {
                tempFile.copyTo(persistentFile, overwrite = true)
                tempFile.delete()
            }

            val entity = CachedSidecarEntity(
                assetId = encryptedPair.assetId,
                vaultId = vaultId,
                encryptedMetaJson = encryptedPair.pvmetaJson,
                releaseId = 0L,
                releaseTag = "local-sample",
                pvaultAssetId = 0L,
                pvmetaAssetId = 0L,
                pvaultDownloadUrl = "",
                pvmetaDownloadUrl = "",
                pvaultSizeBytes = persistentFile.length(),
                pvmetaSizeBytes = encryptedPair.pvmetaSizeBytes,
                isOfflineCached = true,
                localPvaultPath = persistentFile.absolutePath,
                isLocalOnlyImport = true
            )
            dao.upsertSidecar(entity)

            val record = VaultDocumentRecord(
                assetId = encryptedPair.assetId,
                descriptor = encryptedPair.descriptor,
                releaseId = 0L,
                releaseTag = "local-sample",
                pvaultAssetId = 0L,
                pvmetaAssetId = 0L,
                pvaultDownloadUrl = "",
                pvmetaDownloadUrl = "",
                pvaultSizeBytes = persistentFile.length(),
                pvmetaSizeBytes = encryptedPair.pvmetaSizeBytes,
                isOfflineCached = true,
                localPvaultPath = persistentFile.absolutePath,
                isLocalOnlyImport = true
            )
            record to false
        } catch (e: Exception) {
            encryptedPair?.pvaultTempFilePath?.let { runCatching { File(it).delete() } }
            throw e
        } finally {
            crypto.zeroBytes(masterKey)
        }
    }

    suspend fun prepareBundledSamplePair(
        vaultId: String,
        title: String,
        description: String,
        category: String,
        tags: List<String>,
        collection: String,
        onProgress: (Float) -> Unit = {}
    ): EncryptedPdfPairResult = withContext(Dispatchers.IO) {
        val masterKey = session.getMasterKeyCopy()
            ?: throw VaultCryptoException.InvalidCredentials("Vault is locked")
        try {
            val sizeBytes = getBundledSampleSizeBytes()
            crypto.encryptPdfFileChunked(
                inputStreamProvider = { context.assets.open(BUNDLED_SAMPLE_ASSET_NAME) },
                outputTempDir = tempWorkDir,
                masterKey = masterKey,
                vaultId = vaultId,
                originalFilename = BUNDLED_SAMPLE_ORIGINAL_FILENAME,
                title = title,
                description = description,
                category = category,
                tags = tags,
                collection = collection,
                originalSizeBytes = sizeBytes,
                onProgress = onProgress
            )
        } finally {
            crypto.zeroBytes(masterKey)
        }
    }

    suspend fun encryptAndUploadBundledSamplePdf(
        owner: String,
        repo: String,
        vaultId: String,
        title: String,
        description: String,
        category: String,
        tags: List<String>,
        collection: String,
        onStageProgress: (String, Float) -> Unit
    ): VaultDocumentRecord = withContext(Dispatchers.IO) {
        val masterKey = session.getMasterKeyCopy()
            ?: throw VaultCryptoException.InvalidCredentials("Vault is locked")
        val token = session.getGitHubToken()
            ?: throw GitHubApiException.Unauthorized("GitHub Fine-Grained PAT required for direct upload")

        var encryptedPair: EncryptedPdfPairResult? = null
        try {
            onStageProgress("encrypting", 0f)
            val sizeBytes = getBundledSampleSizeBytes()
            encryptedPair = crypto.encryptPdfFileChunked(
                inputStreamProvider = { context.assets.open(BUNDLED_SAMPLE_ASSET_NAME) },
                outputTempDir = tempWorkDir,
                masterKey = masterKey,
                vaultId = vaultId,
                originalFilename = BUNDLED_SAMPLE_ORIGINAL_FILENAME,
                title = title,
                description = description,
                category = category,
                tags = tags,
                collection = collection,
                originalSizeBytes = sizeBytes,
                onProgress = { onStageProgress("encrypting", it) }
            )

            val pvaultFile = File(encryptedPair.pvaultTempFilePath)
            val targetRelease = findOrCreateDataRelease(owner, repo, token)

            onStageProgress("uploading_pvault", 0f)
            val uploadedPvault = github.uploadReleaseAssetFile(
                owner = owner,
                repo = repo,
                releaseId = targetRelease.id,
                assetName = encryptedPair.pvaultFilename,
                file = pvaultFile,
                token = token,
                onProgress = { onStageProgress("uploading_pvault", it) }
            )

            onStageProgress("uploading_pvmeta", 0.5f)
            val uploadedPvmeta = try {
                github.uploadReleaseAssetBytes(
                    owner = owner,
                    repo = repo,
                    releaseId = targetRelease.id,
                    assetName = encryptedPair.pvmetaFilename,
                    bytes = encryptedPair.pvmetaJson.toByteArray(Charsets.UTF_8),
                    token = token
                )
            } catch (e: Exception) {
                runCatching { github.deleteReleaseAsset(owner, repo, uploadedPvault.id, token) }
                throw e
            }

            val entity = CachedSidecarEntity(
                assetId = encryptedPair.assetId,
                vaultId = vaultId,
                encryptedMetaJson = encryptedPair.pvmetaJson,
                releaseId = targetRelease.id,
                releaseTag = targetRelease.tagName,
                pvaultAssetId = uploadedPvault.id,
                pvmetaAssetId = uploadedPvmeta.id,
                pvaultDownloadUrl = uploadedPvault.browserDownloadUrl,
                pvmetaDownloadUrl = uploadedPvmeta.browserDownloadUrl,
                pvaultSizeBytes = uploadedPvault.size,
                pvmetaSizeBytes = uploadedPvmeta.size
            )
            dao.upsertSidecar(entity)

            VaultDocumentRecord(
                assetId = encryptedPair.assetId,
                descriptor = encryptedPair.descriptor,
                releaseId = targetRelease.id,
                releaseTag = targetRelease.tagName,
                pvaultAssetId = uploadedPvault.id,
                pvmetaAssetId = uploadedPvmeta.id,
                pvaultDownloadUrl = uploadedPvault.browserDownloadUrl,
                pvmetaDownloadUrl = uploadedPvmeta.browserDownloadUrl,
                pvaultSizeBytes = uploadedPvault.size,
                pvmetaSizeBytes = uploadedPvmeta.size
            )
        } finally {
            crypto.zeroBytes(masterKey)
            encryptedPair?.pvaultTempFilePath?.let { runCatching { File(it).delete() } }
        }
    }

    suspend fun uploadLocalDocumentToGitHub(
        owner: String,
        repo: String,
        vaultId: String,
        record: VaultDocumentRecord,
        onStageProgress: (String, Float) -> Unit = { _, _ -> }
    ): VaultDocumentRecord = withContext(Dispatchers.IO) {
        val token = session.getGitHubToken()
            ?: throw GitHubApiException.Unauthorized("GitHub Fine-Grained PAT required for direct upload")
        val pvaultPath = record.localPvaultPath
            ?: throw VaultCryptoException.InvalidPdf("Local encrypted .pvault file is missing")
        val pvaultFile = File(pvaultPath)
        if (!pvaultFile.exists()) {
            throw VaultCryptoException.InvalidPdf("Local encrypted .pvault file does not exist")
        }
        val entity = dao.getSidecar(record.assetId)
            ?: throw VaultCryptoException.CorruptedConfig("Local encrypted sidecar missing")

        val targetRelease = findOrCreateDataRelease(owner, repo, token)
        onStageProgress("uploading_pvault", 0f)
        val uploadedPvault = github.uploadReleaseAssetFile(
            owner = owner,
            repo = repo,
            releaseId = targetRelease.id,
            assetName = record.descriptor.encryptedAssetFilename,
            file = pvaultFile,
            token = token,
            onProgress = { onStageProgress("uploading_pvault", it) }
        )

        onStageProgress("uploading_pvmeta", 0.5f)
        val uploadedPvmeta = try {
            github.uploadReleaseAssetBytes(
                owner = owner,
                repo = repo,
                releaseId = targetRelease.id,
                assetName = record.descriptor.encryptedMetaFilename,
                bytes = entity.encryptedMetaJson.toByteArray(Charsets.UTF_8),
                token = token
            )
        } catch (e: Exception) {
            runCatching { github.deleteReleaseAsset(owner, repo, uploadedPvault.id, token) }
            throw e
        }

        val updatedEntity = entity.copy(
            releaseId = targetRelease.id,
            releaseTag = targetRelease.tagName,
            pvaultAssetId = uploadedPvault.id,
            pvmetaAssetId = uploadedPvmeta.id,
            pvaultDownloadUrl = uploadedPvault.browserDownloadUrl,
            pvmetaDownloadUrl = uploadedPvmeta.browserDownloadUrl,
            pvaultSizeBytes = uploadedPvault.size,
            pvmetaSizeBytes = uploadedPvmeta.size,
            isOfflineCached = true,
            localPvaultPath = pvaultFile.absolutePath,
            isLocalOnlyImport = false,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertSidecar(updatedEntity)

        record.copy(
            releaseId = targetRelease.id,
            releaseTag = targetRelease.tagName,
            pvaultAssetId = uploadedPvault.id,
            pvmetaAssetId = uploadedPvmeta.id,
            pvaultDownloadUrl = uploadedPvault.browserDownloadUrl,
            pvmetaDownloadUrl = uploadedPvmeta.browserDownloadUrl,
            pvaultSizeBytes = uploadedPvault.size,
            pvmetaSizeBytes = uploadedPvmeta.size,
            isLocalOnlyImport = false
        )
    }

    private fun selectHighestConfigAsset(assets: List<GitHubAssetDto>): GitHubAssetDto? {
        val regex = Regex("vault-config-v(\\d+)\\.json", RegexOption.IGNORE_CASE)
        return assets.mapNotNull { asset ->
            val match = regex.matchEntire(asset.name)
            val version = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (version != null) version to asset else null
        }.maxByOrNull { it.first }?.second
    }

    private fun formatConfigFilename(version: Int): String =
        "vault-config-v%04d.json".format(version)

    private fun extractLogicalId(filename: String, ext: String): String {
        val base = filename.removeSuffix(ext)
        return base.removePrefix("asset_").removePrefix("meta_")
    }

    companion object {
        const val BUNDLED_SAMPLE_ASSET_NAME = "sample_chemistry_2nd_ch1.pdf"
        const val BUNDLED_SAMPLE_ORIGINAL_FILENAME = "Chemistry_2nd_Chapter_1_Sample.pdf"
    }
}

data class PdfInspectionResult(
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
    val duplicateTitle: String?
)
