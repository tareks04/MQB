package com.example.crypto

import com.squareup.moshi.JsonClass

object VaultFormatConstants {
    const val VAULT_CONFIG_FORMAT = "PERSONAL_PDF_VAULT"
    const val VAULT_CONFIG_VERSION = 1
    const val META_FORMAT = "PERSONAL_PDF_VAULT_META"
    const val META_VERSION = 1
    val PVAULT_MAGIC = "PVAULT01".toByteArray(Charsets.US_ASCII)
    const val PVAULT_VERSION = 1
    const val DEFAULT_CHUNK_SIZE_BYTES = 4 * 1024 * 1024 // 4 MiB
    const val MAX_SINGLE_ASSET_BYTES = 1900L * 1024L * 1024L // < 2 GiB GitHub release asset limit
    const val STORAGE_LIMIT_MIB = 950L
    const val STORAGE_WARN_MIB = 900L
    const val STORAGE_LIMIT_BYTES = STORAGE_LIMIT_MIB * 1024L * 1024L
    const val STORAGE_WARN_BYTES = STORAGE_WARN_MIB * 1024L * 1024L
    const val CONTROL_RELEASE_TAG = "vault-control"
    const val DATA_RELEASE_PREFIX = "pdf-vault-"
    const val RELEASE_ASSET_WARN_COUNT = 900
    const val RELEASE_ASSET_HARD_LIMIT = 990

    const val ARGON2_MIN_MEMORY_KIB = 19456 // 19 MiB OWASP minimum
    const val ARGON2_DEFAULT_ITERATIONS = 2
    const val ARGON2_DEFAULT_PARALLELISM = 1
    const val PBKDF2_MIN_ITERATIONS = 600_000
}

enum class KdfAlgorithm(val id: String) {
    ARGON2ID("Argon2id"),
    PBKDF2_SHA256("PBKDF2-HMAC-SHA256");

    companion object {
        fun fromId(id: String): KdfAlgorithm =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
                ?: throw VaultCryptoException.UnsupportedFormat("Unsupported KDF algorithm: $id")
    }
}

@JsonClass(generateAdapter = true)
data class KdfParameters(
    val memoryKiB: Int = VaultFormatConstants.ARGON2_MIN_MEMORY_KIB,
    val iterations: Int = VaultFormatConstants.ARGON2_DEFAULT_ITERATIONS,
    val parallelism: Int = VaultFormatConstants.ARGON2_DEFAULT_PARALLELISM
)

@JsonClass(generateAdapter = true)
data class KdfConfig(
    val algorithm: String,
    val salt: String,
    val parameters: KdfParameters
)

@JsonClass(generateAdapter = true)
data class WrappedKeyEnvelope(
    val algorithm: String = "AES-256-GCM",
    val iv: String,
    val ciphertext: String
)

@JsonClass(generateAdapter = true)
data class VaultConfig(
    val format: String = VaultFormatConstants.VAULT_CONFIG_FORMAT,
    val version: Int = VaultFormatConstants.VAULT_CONFIG_VERSION,
    val configVersion: Int = 1,
    val vaultId: String,
    val kdf: KdfConfig,
    val wrappedMasterKey: WrappedKeyEnvelope,
    val recoveryEnvelope: WrappedKeyEnvelope,
    val createdAt: String,
    val updatedAt: String
)

@JsonClass(generateAdapter = true)
data class PvMetaEnvelope(
    val format: String = VaultFormatConstants.META_FORMAT,
    val version: Int = VaultFormatConstants.META_VERSION,
    val vaultId: String,
    val assetId: String,
    val iv: String,
    val ciphertext: String
)

@JsonClass(generateAdapter = true)
data class PdfDescriptor(
    val assetId: String,
    val vaultId: String,
    val originalFilename: String,
    val title: String,
    val description: String = "",
    val category: String = "Other",
    val tags: List<String> = emptyList(),
    val collection: String = "",
    val originalSizeBytes: Long,
    val encryptedAssetFilename: String,
    val encryptedMetaFilename: String,
    val plaintextSha256: String,
    val chunkSize: Int,
    val chunkCount: Int,
    val noncePrefixBase64: String,
    val wrappedDekBase64: String,
    val mimeType: String = "application/pdf",
    val uploadedAt: String,
    val isFavorite: Boolean = false
)

@JsonClass(generateAdapter = true)
data class LocalEncryptedReadingState(
    val assetId: String,
    val lastPage: Int = 0,
    val totalPages: Int = 0,
    val lastOpenedAtEpochMs: Long = 0L,
    val bookmarks: List<Int> = emptyList()
)

data class CreatedVaultBundle(
    val config: VaultConfig,
    val configJson: String,
    val recoveryKeyFormatted: String,
    val masterKey: ByteArray
)

data class EncryptedPdfPairResult(
    val assetId: String,
    val pvaultFilename: String,
    val pvmetaFilename: String,
    val pvaultTempFilePath: String,
    val pvmetaJson: String,
    val pvaultSizeBytes: Long,
    val pvmetaSizeBytes: Long,
    val descriptor: PdfDescriptor
)

data class VaultRotationResult(
    val newConfig: VaultConfig,
    val newConfigJson: String,
    val newRecoveryKeyFormatted: String,
    val newMasterKey: ByteArray,
    val updatedSidecars: Map<String, Pair<String, PvMetaEnvelope>>
)

sealed class VaultCryptoException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class InvalidCredentials(message: String = "Invalid credentials") : VaultCryptoException(message)
    class InvalidRecoveryKey(message: String = "Invalid recovery key") : VaultCryptoException(message)
    class CorruptedConfig(message: String = "Corrupted vault config") : VaultCryptoException(message)
    class UnsupportedFormat(message: String = "Unsupported vault format") : VaultCryptoException(message)
    class InvalidPdf(message: String = "Invalid PDF file") : VaultCryptoException(message)
    class AuthenticationFailed(message: String = "Cryptographic tag verification failed") :
        VaultCryptoException(message)
    class KeyDerivationFailed(message: String, cause: Throwable? = null) :
        VaultCryptoException(message, cause)
}
