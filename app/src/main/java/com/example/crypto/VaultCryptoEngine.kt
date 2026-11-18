package com.example.crypto

import android.util.Base64
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Genuine client-side cryptographic engine implementing:
 * - Argon2id (RFC 9106 via BouncyCastle) and PBKDF2-HMAC-SHA256 fallback
 * - AES-256-GCM authenticated encryption with strict AAD binding
 * - Chunked 4 MiB PDF encryption/decryption with unique 64-bit nonce prefix + 32-bit chunk counter
 * - Per-file random 256-bit DEKs wrapped by the Vault Master Key
 */
class VaultCryptoEngine {

    private val secureRandom = SecureRandom()
    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val vaultConfigAdapter = moshi.adapter(VaultConfig::class.java).indent("  ")
    private val pvMetaAdapter = moshi.adapter(PvMetaEnvelope::class.java)
    private val descriptorAdapter = moshi.adapter(PdfDescriptor::class.java)
    private val readingStateAdapter = moshi.adapter(LocalEncryptedReadingState::class.java)

    fun serializeVaultConfig(config: VaultConfig): String = vaultConfigAdapter.toJson(config)

    fun parseVaultConfig(json: String): VaultConfig {
        val parsed = try {
            vaultConfigAdapter.fromJson(json)
        } catch (e: Exception) {
            throw VaultCryptoException.CorruptedConfig("Malformed vault configuration JSON")
        } ?: throw VaultCryptoException.CorruptedConfig("Empty vault configuration")

        if (parsed.format != VaultFormatConstants.VAULT_CONFIG_FORMAT) {
            throw VaultCryptoException.UnsupportedFormat("Unknown vault format: ${parsed.format}")
        }
        if (parsed.version != VaultFormatConstants.VAULT_CONFIG_VERSION) {
            throw VaultCryptoException.UnsupportedFormat("Unsupported vault version: ${parsed.version}")
        }
        if (parsed.vaultId.isBlank()) {
            throw VaultCryptoException.CorruptedConfig("Missing vaultId")
        }
        KdfAlgorithm.fromId(parsed.kdf.algorithm)
        return parsed
    }

    fun parsePvMetaEnvelope(json: String): PvMetaEnvelope {
        val env = try {
            pvMetaAdapter.fromJson(json)
        } catch (e: Exception) {
            throw VaultCryptoException.UnsupportedFormat("Malformed .pvmeta sidecar JSON")
        } ?: throw VaultCryptoException.UnsupportedFormat("Empty .pvmeta sidecar")

        if (env.format != VaultFormatConstants.META_FORMAT || env.version != VaultFormatConstants.META_VERSION) {
            throw VaultCryptoException.UnsupportedFormat("Unsupported .pvmeta format or version")
        }
        return env
    }

    fun serializePvMetaEnvelope(envelope: PvMetaEnvelope): String = pvMetaAdapter.toJson(envelope)

    fun createNewVault(
        password: CharArray,
        kdfAlgorithm: KdfAlgorithm = KdfAlgorithm.ARGON2ID,
        memoryKiB: Int = VaultFormatConstants.ARGON2_MIN_MEMORY_KIB,
        iterations: Int = VaultFormatConstants.ARGON2_DEFAULT_ITERATIONS,
        parallelism: Int = VaultFormatConstants.ARGON2_DEFAULT_PARALLELISM
    ): CreatedVaultBundle {
        val vaultId = UUID.randomUUID().toString()
        val masterKey = randomBytes(32)
        val recoveryKeyBytes = randomBytes(32)
        val salt = randomBytes(16)
        val now = isoUtcNow()

        val effectiveIter = if (kdfAlgorithm == KdfAlgorithm.PBKDF2_SHA256) {
            maxOf(iterations, VaultFormatConstants.PBKDF2_MIN_ITERATIONS)
        } else {
            maxOf(iterations, VaultFormatConstants.ARGON2_DEFAULT_ITERATIONS)
        }
        val effectiveMem = maxOf(memoryKiB, VaultFormatConstants.ARGON2_MIN_MEMORY_KIB)
        val effectiveParallelism = maxOf(parallelism, VaultFormatConstants.ARGON2_DEFAULT_PARALLELISM)

        val kdfConfig = KdfConfig(
            algorithm = kdfAlgorithm.id,
            salt = encodeB64Url(salt),
            parameters = KdfParameters(
                memoryKiB = effectiveMem,
                iterations = effectiveIter,
                parallelism = effectiveParallelism
            )
        )

        val wrappingKey = deriveWrappingKey(password, kdfConfig)
        try {
            val wrappedMaster = aesGcmEncryptToEnvelope(
                keyBytes = wrappingKey,
                plaintext = masterKey,
                aad = masterWrapAad(vaultId)
            )
            val recoveryEnvelope = aesGcmEncryptToEnvelope(
                keyBytes = recoveryKeyBytes,
                plaintext = masterKey,
                aad = recoveryWrapAad(vaultId)
            )
            val config = VaultConfig(
                configVersion = 1,
                vaultId = vaultId,
                kdf = kdfConfig,
                wrappedMasterKey = wrappedMaster,
                recoveryEnvelope = recoveryEnvelope,
                createdAt = now,
                updatedAt = now
            )
            val recoveryFormatted = formatRecoveryKey(recoveryKeyBytes)
            return CreatedVaultBundle(
                config = config,
                configJson = serializeVaultConfig(config),
                recoveryKeyFormatted = recoveryFormatted,
                masterKey = masterKey
            )
        } finally {
            zeroBytes(wrappingKey)
            zeroBytes(recoveryKeyBytes)
        }
    }

    fun unlockWithPassword(password: CharArray, config: VaultConfig): ByteArray {
        val wrappingKey = deriveWrappingKey(password, config.kdf)
        return try {
            aesGcmDecryptEnvelope(
                keyBytes = wrappingKey,
                envelope = config.wrappedMasterKey,
                aad = masterWrapAad(config.vaultId)
            )
        } catch (e: AEADBadTagException) {
            throw VaultCryptoException.InvalidCredentials("Incorrect master password")
        } catch (e: Exception) {
            throw VaultCryptoException.InvalidCredentials("Failed to unlock vault")
        } finally {
            zeroBytes(wrappingKey)
        }
    }

    fun unlockWithRecoveryKey(recoveryKeyInput: String, config: VaultConfig): ByteArray {
        val recoveryKeyBytes = parseRecoveryKey(recoveryKeyInput)
        return try {
            aesGcmDecryptEnvelope(
                keyBytes = recoveryKeyBytes,
                envelope = config.recoveryEnvelope,
                aad = recoveryWrapAad(config.vaultId)
            )
        } catch (e: AEADBadTagException) {
            throw VaultCryptoException.InvalidRecoveryKey("Recovery key does not match this vault")
        } catch (e: Exception) {
            throw VaultCryptoException.InvalidRecoveryKey("Failed to decrypt recovery envelope")
        } finally {
            zeroBytes(recoveryKeyBytes)
        }
    }

    fun rewrapPassword(
        masterKey: ByteArray,
        newPassword: CharArray,
        oldConfig: VaultConfig,
        kdfAlgorithm: KdfAlgorithm = KdfAlgorithm.fromId(oldConfig.kdf.algorithm),
        memoryKiB: Int = oldConfig.kdf.parameters.memoryKiB,
        iterations: Int = if (kdfAlgorithm == KdfAlgorithm.PBKDF2_SHA256)
            VaultFormatConstants.PBKDF2_MIN_ITERATIONS else oldConfig.kdf.parameters.iterations,
        parallelism: Int = oldConfig.kdf.parameters.parallelism
    ): Pair<VaultConfig, String> {
        val newSalt = randomBytes(16)
        val newKdf = KdfConfig(
            algorithm = kdfAlgorithm.id,
            salt = encodeB64Url(newSalt),
            parameters = KdfParameters(
                memoryKiB = maxOf(memoryKiB, VaultFormatConstants.ARGON2_MIN_MEMORY_KIB),
                iterations = if (kdfAlgorithm == KdfAlgorithm.PBKDF2_SHA256)
                    maxOf(iterations, VaultFormatConstants.PBKDF2_MIN_ITERATIONS)
                else maxOf(iterations, VaultFormatConstants.ARGON2_DEFAULT_ITERATIONS),
                parallelism = maxOf(parallelism, 1)
            )
        )
        val wrappingKey = deriveWrappingKey(newPassword, newKdf)
        return try {
            val wrappedMaster = aesGcmEncryptToEnvelope(
                keyBytes = wrappingKey,
                plaintext = masterKey,
                aad = masterWrapAad(oldConfig.vaultId)
            )
            val newConfig = oldConfig.copy(
                configVersion = oldConfig.configVersion + 1,
                kdf = newKdf,
                wrappedMasterKey = wrappedMaster,
                updatedAt = isoUtcNow()
            )
            newConfig to serializeVaultConfig(newConfig)
        } finally {
            zeroBytes(wrappingKey)
        }
    }

    fun rotateEntireVaultKey(
        oldMasterKey: ByteArray,
        newPassword: CharArray,
        oldConfig: VaultConfig,
        existingDescriptors: List<PdfDescriptor>
    ): VaultRotationResult {
        val newMasterKey = randomBytes(32)
        val newRecoveryBytes = randomBytes(32)
        val newSalt = randomBytes(16)
        val kdfAlg = KdfAlgorithm.fromId(oldConfig.kdf.algorithm)
        val newKdf = KdfConfig(
            algorithm = kdfAlg.id,
            salt = encodeB64Url(newSalt),
            parameters = oldConfig.kdf.parameters
        )
        val wrappingKey = deriveWrappingKey(newPassword, newKdf)
        try {
            val wrappedMaster = aesGcmEncryptToEnvelope(
                keyBytes = wrappingKey,
                plaintext = newMasterKey,
                aad = masterWrapAad(oldConfig.vaultId)
            )
            val recoveryEnvelope = aesGcmEncryptToEnvelope(
                keyBytes = newRecoveryBytes,
                plaintext = newMasterKey,
                aad = recoveryWrapAad(oldConfig.vaultId)
            )
            val newConfig = oldConfig.copy(
                configVersion = oldConfig.configVersion + 1,
                kdf = newKdf,
                wrappedMasterKey = wrappedMaster,
                recoveryEnvelope = recoveryEnvelope,
                updatedAt = isoUtcNow()
            )
            val updatedSidecars = mutableMapOf<String, Pair<String, PvMetaEnvelope>>()
            for (desc in existingDescriptors) {
                val dek = unwrapDek(desc.wrappedDekBase64, oldMasterKey, desc.vaultId, desc.assetId)
                try {
                    val rewrappedDek = wrapDek(dek, newMasterKey, desc.vaultId, desc.assetId)
                    val updatedDesc = desc.copy(wrappedDekBase64 = rewrappedDek)
                    val newEnvelope = encryptDescriptorToSidecar(updatedDesc, newMasterKey)
                    updatedSidecars[desc.assetId] =
                        desc.encryptedMetaFilename to newEnvelope
                } finally {
                    zeroBytes(dek)
                }
            }
            return VaultRotationResult(
                newConfig = newConfig,
                newConfigJson = serializeVaultConfig(newConfig),
                newRecoveryKeyFormatted = formatRecoveryKey(newRecoveryBytes),
                newMasterKey = newMasterKey,
                updatedSidecars = updatedSidecars
            )
        } finally {
            zeroBytes(wrappingKey)
            zeroBytes(newRecoveryBytes)
        }
    }

    /**
     * Validates PDF magic header and streams encryption in 4 MiB chunks.
     * Never loads the whole PDF into a single ByteArray.
     */
    fun encryptPdfFileChunked(
        inputStreamProvider: () -> InputStream,
        outputTempDir: File,
        masterKey: ByteArray,
        vaultId: String,
        originalFilename: String,
        title: String,
        description: String,
        category: String,
        tags: List<String>,
        collection: String,
        originalSizeBytes: Long,
        chunkSize: Int = VaultFormatConstants.DEFAULT_CHUNK_SIZE_BYTES,
        onProgress: (Float) -> Unit = {}
    ): EncryptedPdfPairResult {
        if (originalSizeBytes <= 5 || originalSizeBytes > VaultFormatConstants.MAX_SINGLE_ASSET_BYTES) {
            throw VaultCryptoException.InvalidPdf("Invalid PDF file size")
        }

        // Verify %PDF- signature first
        inputStreamProvider().use { probeStream ->
            val header = ByteArray(5)
            val read = probeStream.read(header)
            if (read < 5 || !header.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) {
                throw VaultCryptoException.InvalidPdf("Missing %PDF- header signature")
            }
        }

        val assetId = UUID.randomUUID().toString()
        val opaqueId = randomHex(6)
        val pvaultFilename = "asset_$opaqueId.pvault"
        val pvmetaFilename = "meta_$opaqueId.pvmeta"
        val pvaultFile = File(outputTempDir, pvaultFilename)

        val dek = randomBytes(32)
        val noncePrefix = randomBytes(8)
        val totalChunksLong = (originalSizeBytes + chunkSize - 1L) / chunkSize
        if (totalChunksLong <= 0L || totalChunksLong > Int.MAX_VALUE) {
            throw VaultCryptoException.InvalidPdf("Chunk count overflow")
        }
        val totalChunks = totalChunksLong.toInt()

        val vaultUuidBytes = uuidToBytes(vaultId)
        val assetUuidBytes = uuidToBytes(assetId)
        val sha256 = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(chunkSize)
        var bytesProcessed = 0L

        try {
            BufferedInputStream(inputStreamProvider(), 64 * 1024).use { input ->
                DataOutputStream(BufferedOutputStream(FileOutputStream(pvaultFile), 64 * 1024)).use { out ->
                    // Header
                    out.write(VaultFormatConstants.PVAULT_MAGIC)
                    out.writeShort(VaultFormatConstants.PVAULT_VERSION)
                    out.writeShort(0) // reserved flags
                    out.write(vaultUuidBytes)
                    out.write(assetUuidBytes)
                    out.writeInt(chunkSize)
                    out.writeInt(totalChunks)
                    out.write(noncePrefix)
                    out.writeLong(originalSizeBytes)

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val dekSpec = SecretKeySpec(dek, "AES")

                    var chunkIndex = 0
                    while (true) {
                        val bytesRead = readFullyUpTo(input, buffer)
                        if (bytesRead <= 0) break

                        sha256.update(buffer, 0, bytesRead)
                        val nonce = buildChunkNonce(noncePrefix, chunkIndex)
                        val aad = chunkAad(vaultId, assetId, chunkIndex)

                        cipher.init(Cipher.ENCRYPT_MODE, dekSpec, GCMParameterSpec(128, nonce))
                        cipher.updateAAD(aad)
                        val encryptedChunk = cipher.doFinal(buffer, 0, bytesRead)

                        out.writeInt(encryptedChunk.size)
                        out.write(encryptedChunk)

                        chunkIndex++
                        bytesProcessed += bytesRead
                        onProgress((bytesProcessed.toFloat() / originalSizeBytes.toFloat()).coerceIn(0f, 1f))
                    }
                    if (chunkIndex != totalChunks) {
                        throw VaultCryptoException.InvalidPdf(
                            "Expected $totalChunks chunks but encrypted $chunkIndex"
                        )
                    }
                    out.flush()
                }
            }

            val plaintextHashHex = sha256.digest().joinToString("") { "%02x".format(it) }
            val wrappedDek = wrapDek(dek, masterKey, vaultId, assetId)
            val descriptor = PdfDescriptor(
                assetId = assetId,
                vaultId = vaultId,
                originalFilename = originalFilename,
                title = title.ifBlank { originalFilename.removeSuffix(".pdf") },
                description = description,
                category = category,
                tags = tags,
                collection = collection,
                originalSizeBytes = originalSizeBytes,
                encryptedAssetFilename = pvaultFilename,
                encryptedMetaFilename = pvmetaFilename,
                plaintextSha256 = plaintextHashHex,
                chunkSize = chunkSize,
                chunkCount = totalChunks,
                noncePrefixBase64 = encodeB64Url(noncePrefix),
                wrappedDekBase64 = wrappedDek,
                uploadedAt = isoUtcNow(),
                isFavorite = false
            )
            val sidecarEnvelope = encryptDescriptorToSidecar(descriptor, masterKey)
            val pvmetaJson = serializePvMetaEnvelope(sidecarEnvelope)

            return EncryptedPdfPairResult(
                assetId = assetId,
                pvaultFilename = pvaultFilename,
                pvmetaFilename = pvmetaFilename,
                pvaultTempFilePath = pvaultFile.absolutePath,
                pvmetaJson = pvmetaJson,
                pvaultSizeBytes = pvaultFile.length(),
                pvmetaSizeBytes = pvmetaJson.toByteArray(Charsets.UTF_8).size.toLong(),
                descriptor = descriptor
            )
        } catch (e: Exception) {
            pvaultFile.delete()
            if (e is VaultCryptoException) throw e
            throw VaultCryptoException.InvalidPdf("Encryption failed: ${e.message}")
        } finally {
            zeroBytes(dek)
            zeroBytes(buffer)
        }
    }

    /**
     * Validates PVAULT01 binary header, unwraps DEK, decrypts and authenticates every chunk
     * with AES-256-GCM AAD, and writes verified plaintext to [outputPdfFile].
     * Deletes [outputPdfFile] on any authentication failure.
     */
    fun decryptPdfFileChunked(
        encryptedPvaultFile: File,
        outputPdfFile: File,
        descriptor: PdfDescriptor,
        masterKey: ByteArray,
        onProgress: (Float) -> Unit = {}
    ) {
        val dek = unwrapDek(descriptor.wrappedDekBase64, masterKey, descriptor.vaultId, descriptor.assetId)
        val expectedVaultBytes = uuidToBytes(descriptor.vaultId)
        val expectedAssetBytes = uuidToBytes(descriptor.assetId)

        try {
            DataInputStream(BufferedInputStream(FileInputStream(encryptedPvaultFile), 64 * 1024)).use { input ->
                val magic = ByteArray(8)
                input.readFully(magic)
                if (!magic.contentEquals(VaultFormatConstants.PVAULT_MAGIC)) {
                    throw VaultCryptoException.UnsupportedFormat("Invalid .pvault magic header")
                }
                val version = input.readUnsignedShort()
                if (version != VaultFormatConstants.PVAULT_VERSION) {
                    throw VaultCryptoException.UnsupportedFormat("Unsupported .pvault version: $version")
                }
                input.readUnsignedShort() // flags
                val vaultBytes = ByteArray(16)
                input.readFully(vaultBytes)
                val assetBytes = ByteArray(16)
                input.readFully(assetBytes)
                if (!vaultBytes.contentEquals(expectedVaultBytes) ||
                    !assetBytes.contentEquals(expectedAssetBytes)
                ) {
                    throw VaultCryptoException.AuthenticationFailed("Vault or Asset ID mismatch in .pvault header")
                }

                val chunkSize = input.readInt()
                val totalChunks = input.readInt()
                if (chunkSize <= 0 || totalChunks <= 0 || totalChunks != descriptor.chunkCount) {
                    throw VaultCryptoException.AuthenticationFailed("Chunk metadata mismatch")
                }
                val noncePrefix = ByteArray(8)
                input.readFully(noncePrefix)
                val plaintextSize = input.readLong()
                if (plaintextSize != descriptor.originalSizeBytes || plaintextSize <= 0L) {
                    throw VaultCryptoException.AuthenticationFailed("Plaintext size mismatch")
                }

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val dekSpec = SecretKeySpec(dek, "AES")
                val sha256 = MessageDigest.getInstance("SHA-256")
                var totalDecrypted = 0L

                BufferedOutputStream(FileOutputStream(outputPdfFile), 64 * 1024).use { out ->
                    for (chunkIndex in 0 until totalChunks) {
                        val encLen = input.readInt()
                        if (encLen <= 16 || encLen > chunkSize + 64) {
                            throw VaultCryptoException.AuthenticationFailed("Invalid encrypted chunk size")
                        }
                        val encBytes = ByteArray(encLen)
                        input.readFully(encBytes)

                        val nonce = buildChunkNonce(noncePrefix, chunkIndex)
                        val aad = chunkAad(descriptor.vaultId, descriptor.assetId, chunkIndex)

                        cipher.init(Cipher.DECRYPT_MODE, dekSpec, GCMParameterSpec(128, nonce))
                        cipher.updateAAD(aad)
                        val plainChunk = try {
                            cipher.doFinal(encBytes)
                        } catch (e: AEADBadTagException) {
                            throw VaultCryptoException.AuthenticationFailed(
                                "Authentication tag mismatch at chunk $chunkIndex"
                            )
                        }

                        sha256.update(plainChunk)
                        out.write(plainChunk)
                        totalDecrypted += plainChunk.size
                        zeroBytes(plainChunk)
                        onProgress(((chunkIndex + 1).toFloat() / totalChunks.toFloat()).coerceIn(0f, 1f))
                    }
                    out.flush()
                }

                if (totalDecrypted != plaintextSize) {
                    throw VaultCryptoException.AuthenticationFailed("Decrypted length mismatch")
                }
                if (descriptor.plaintextSha256.isNotBlank()) {
                    val actualHash = sha256.digest().joinToString("") { "%02x".format(it) }
                    if (!actualHash.equals(descriptor.plaintextSha256, ignoreCase = true)) {
                        throw VaultCryptoException.AuthenticationFailed("Plaintext SHA-256 integrity check failed")
                    }
                }
            }
        } catch (e: Exception) {
            outputPdfFile.delete()
            if (e is VaultCryptoException) throw e
            throw VaultCryptoException.AuthenticationFailed("Decryption failed: ${e.message}")
        } finally {
            zeroBytes(dek)
        }
    }

    fun encryptDescriptorToSidecar(descriptor: PdfDescriptor, masterKey: ByteArray): PvMetaEnvelope {
        val jsonBytes = descriptorAdapter.toJson(descriptor).toByteArray(Charsets.UTF_8)
        val iv = randomBytes(12)
        val aad = sidecarAad(descriptor.vaultId, descriptor.assetId)
        val cipherText = aesGcmEncrypt(masterKey, iv, jsonBytes, aad)
        return PvMetaEnvelope(
            vaultId = descriptor.vaultId,
            assetId = descriptor.assetId,
            iv = encodeB64Url(iv),
            ciphertext = encodeB64Url(cipherText)
        )
    }

    fun decryptSidecarDescriptor(envelope: PvMetaEnvelope, masterKey: ByteArray): PdfDescriptor {
        if (envelope.format != VaultFormatConstants.META_FORMAT ||
            envelope.version != VaultFormatConstants.META_VERSION
        ) {
            throw VaultCryptoException.UnsupportedFormat("Unsupported sidecar format")
        }
        val iv = decodeB64Url(envelope.iv)
        val cipherBytes = decodeB64Url(envelope.ciphertext)
        val aad = sidecarAad(envelope.vaultId, envelope.assetId)
        val plainBytes = try {
            aesGcmDecrypt(masterKey, iv, cipherBytes, aad)
        } catch (e: AEADBadTagException) {
            throw VaultCryptoException.AuthenticationFailed("Sidecar decryption tag mismatch")
        }
        val json = plainBytes.toString(Charsets.UTF_8)
        zeroBytes(plainBytes)
        return descriptorAdapter.fromJson(json)
            ?: throw VaultCryptoException.CorruptedConfig("Invalid decrypted metadata descriptor")
    }

    fun encryptReadingState(state: LocalEncryptedReadingState, masterKey: ByteArray): String {
        val plain = readingStateAdapter.toJson(state).toByteArray(Charsets.UTF_8)
        val iv = randomBytes(12)
        val aad = "READING_STATE:1:${state.assetId}".toByteArray(Charsets.UTF_8)
        val ct = aesGcmEncrypt(masterKey, iv, plain, aad)
        val combined = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ct, 0, combined, iv.size, ct.size)
        return encodeB64Url(combined)
    }

    fun decryptReadingState(
        assetId: String,
        encodedBase64: String,
        masterKey: ByteArray
    ): LocalEncryptedReadingState {
        val combined = decodeB64Url(encodedBase64)
        if (combined.size <= 28) {
            throw VaultCryptoException.AuthenticationFailed("Invalid encrypted reading state")
        }
        val iv = combined.copyOfRange(0, 12)
        val ct = combined.copyOfRange(12, combined.size)
        val aad = "READING_STATE:1:$assetId".toByteArray(Charsets.UTF_8)
        val plain = try {
            aesGcmDecrypt(masterKey, iv, ct, aad)
        } catch (e: AEADBadTagException) {
            throw VaultCryptoException.AuthenticationFailed("Reading state tag mismatch")
        }
        val json = plain.toString(Charsets.UTF_8)
        zeroBytes(plain)
        return readingStateAdapter.fromJson(json)
            ?: LocalEncryptedReadingState(assetId = assetId)
    }

    fun computeInputSha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        inputStream.use { stream ->
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deriveWrappingKey(password: CharArray, kdfConfig: KdfConfig): ByteArray {
        val salt = decodeB64Url(kdfConfig.salt)
        val alg = KdfAlgorithm.fromId(kdfConfig.algorithm)
        return when (alg) {
            KdfAlgorithm.ARGON2ID -> {
                val passwordBytes = charsToUtf8Bytes(password)
                try {
                    val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                        .withSalt(salt)
                        .withMemoryAsKB(maxOf(kdfConfig.parameters.memoryKiB, 8192))
                        .withIterations(maxOf(kdfConfig.parameters.iterations, 1))
                        .withParallelism(maxOf(kdfConfig.parameters.parallelism, 1))
                        .build()
                    val generator = Argon2BytesGenerator()
                    generator.init(params)
                    val out = ByteArray(32)
                    generator.generateBytes(passwordBytes, out)
                    out
                } catch (e: Exception) {
                    throw VaultCryptoException.KeyDerivationFailed("Argon2id failed", e)
                } finally {
                    zeroBytes(passwordBytes)
                }
            }

            KdfAlgorithm.PBKDF2_SHA256 -> {
                try {
                    val spec = PBEKeySpec(
                        password,
                        salt,
                        maxOf(kdfConfig.parameters.iterations, 100_000),
                        256
                    )
                    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    val encoded = factory.generateSecret(spec).encoded
                    spec.clearPassword()
                    encoded
                } catch (e: Exception) {
                    throw VaultCryptoException.KeyDerivationFailed("PBKDF2 key derivation failed", e)
                }
            }
        }
    }

    private fun wrapDek(dek: ByteArray, masterKey: ByteArray, vaultId: String, assetId: String): String {
        val iv = randomBytes(12)
        val aad = "DEK:1:$vaultId:$assetId".toByteArray(Charsets.UTF_8)
        val ct = aesGcmEncrypt(masterKey, iv, dek, aad)
        val combined = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ct, 0, combined, iv.size, ct.size)
        return encodeB64Url(combined)
    }

    private fun unwrapDek(
        wrappedBase64: String,
        masterKey: ByteArray,
        vaultId: String,
        assetId: String
    ): ByteArray {
        val combined = decodeB64Url(wrappedBase64)
        if (combined.size <= 28) {
            throw VaultCryptoException.AuthenticationFailed("Invalid wrapped DEK")
        }
        val iv = combined.copyOfRange(0, 12)
        val ct = combined.copyOfRange(12, combined.size)
        val aad = "DEK:1:$vaultId:$assetId".toByteArray(Charsets.UTF_8)
        return try {
            aesGcmDecrypt(masterKey, iv, ct, aad)
        } catch (e: AEADBadTagException) {
            throw VaultCryptoException.AuthenticationFailed("DEK unwrap failed")
        }
    }

    private fun aesGcmEncryptToEnvelope(
        keyBytes: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): WrappedKeyEnvelope {
        val iv = randomBytes(12)
        val ct = aesGcmEncrypt(keyBytes, iv, plaintext, aad)
        return WrappedKeyEnvelope(
            algorithm = "AES-256-GCM",
            iv = encodeB64Url(iv),
            ciphertext = encodeB64Url(ct)
        )
    }

    private fun aesGcmDecryptEnvelope(
        keyBytes: ByteArray,
        envelope: WrappedKeyEnvelope,
        aad: ByteArray
    ): ByteArray {
        val iv = decodeB64Url(envelope.iv)
        val ct = decodeB64Url(envelope.ciphertext)
        return aesGcmDecrypt(keyBytes, iv, ct, aad)
    }

    private fun aesGcmEncrypt(
        keyBytes: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(
        keyBytes: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun buildChunkNonce(prefix8: ByteArray, chunkIndex: Int): ByteArray {
        val buf = ByteBuffer.allocate(12)
        buf.put(prefix8, 0, 8)
        buf.putInt(chunkIndex)
        return buf.array()
    }

    private fun masterWrapAad(vaultId: String): ByteArray =
        "PERSONAL_PDF_VAULT:1:$vaultId:MASTER".toByteArray(Charsets.UTF_8)

    private fun recoveryWrapAad(vaultId: String): ByteArray =
        "PERSONAL_PDF_VAULT:1:$vaultId:RECOVERY".toByteArray(Charsets.UTF_8)

    private fun sidecarAad(vaultId: String, assetId: String): ByteArray =
        "PVMETA:1:$vaultId:$assetId".toByteArray(Charsets.UTF_8)

    private fun chunkAad(vaultId: String, assetId: String, chunkIndex: Int): ByteArray =
        "PVAULT:1:$vaultId:$assetId:$chunkIndex".toByteArray(Charsets.UTF_8)

    private fun randomBytes(len: Int): ByteArray {
        val b = ByteArray(len)
        secureRandom.nextBytes(b)
        return b
    }

    private fun randomHex(byteCount: Int): String =
        randomBytes(byteCount).joinToString("") { "%02x".format(it) }

    private fun formatRecoveryKey(bytes: ByteArray): String {
        val hex = bytes.joinToString("") { "%02X".format(it) }
        return hex.chunked(4).joinToString("-")
    }

    private fun parseRecoveryKey(input: String): ByteArray {
        val cleaned = input.trim()
            .replace("-", "")
            .replace(" ", "")
            .replace("\n", "")
            .replace("\r", "")
        if (cleaned.length == 64 && cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return ByteArray(32) { idx ->
                cleaned.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
        }
        return try {
            val decoded = decodeB64Url(input.trim())
            if (decoded.size != 32) {
                throw VaultCryptoException.InvalidRecoveryKey("Recovery key must be 256 bits")
            }
            decoded
        } catch (e: Exception) {
            throw VaultCryptoException.InvalidRecoveryKey("Malformed recovery key")
        }
    }

    private fun uuidToBytes(uuidStr: String): ByteArray {
        val uuid = UUID.fromString(uuidStr)
        val bb = ByteBuffer.allocate(16)
        bb.putLong(uuid.mostSignificantBits)
        bb.putLong(uuid.leastSignificantBits)
        return bb.array()
    }

    private fun readFullyUpTo(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read == -1) break
            total += read
        }
        return total
    }

    private fun charsToUtf8Bytes(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        return out
    }

    fun encodeB64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun decodeB64Url(text: String): ByteArray =
        Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP)

    fun zeroBytes(bytes: ByteArray?) {
        if (bytes != null) Arrays.fill(bytes, 0.toByte())
    }

    fun isoUtcNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
