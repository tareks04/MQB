package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.KdfAlgorithm
import com.example.crypto.VaultCryptoEngine
import com.example.crypto.VaultCryptoException
import com.example.data.VaultRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VaultCryptoEngineTest {

    private val engine = VaultCryptoEngine()

    @Test
    fun createUnlockRecoverAndRewrapPassword() {
        val password = "CorrectHorseBatteryStaple!99".toCharArray()
        val bundle = engine.createNewVault(
            password = password,
            kdfAlgorithm = KdfAlgorithm.ARGON2ID,
            memoryKiB = 19456,
            iterations = 2,
            parallelism = 1
        )

        val unlocked = engine.unlockWithPassword(password, bundle.config)
        assertArrayEquals(bundle.masterKey, unlocked)

        assertThrows(VaultCryptoException.InvalidCredentials::class.java) {
            engine.unlockWithPassword("WrongPassword123".toCharArray(), bundle.config)
        }

        val recovered = engine.unlockWithRecoveryKey(bundle.recoveryKeyFormatted, bundle.config)
        assertArrayEquals(bundle.masterKey, recovered)

        assertThrows(VaultCryptoException.InvalidRecoveryKey::class.java) {
            engine.unlockWithRecoveryKey("0000-0000-0000-0000", bundle.config)
        }

        val newPass = "UpdatedStrongPassphrase!2026".toCharArray()
        val (rewrappedConfig, _) = engine.rewrapPassword(
            masterKey = unlocked,
            newPassword = newPass,
            oldConfig = bundle.config
        )
        assertEquals(2, rewrappedConfig.configVersion)
        val unlockedWithNew = engine.unlockWithPassword(newPass, rewrappedConfig)
        assertArrayEquals(bundle.masterKey, unlockedWithNew)
    }

    @Test
    fun chunkedEncryptDecryptTamperDetectionAndKeyRotation() {
        val tempDir = createTempDirectory("pvault_test").toFile()
        try {
            val bundle = engine.createNewVault(
                password = "VaultMasterPassword#2026".toCharArray(),
                kdfAlgorithm = KdfAlgorithm.ARGON2ID
            )

            // Build > 2 chunks using a custom 1024-byte chunk size for fast multi-chunk verification
            val pdfPayload = ("%PDF-1.7\n" + "SECRET_ACADEMIC_PDF_CONTENT_".repeat(120)).toByteArray(Charsets.UTF_8)
            val pair = engine.encryptPdfFileChunked(
                inputStreamProvider = { ByteArrayInputStream(pdfPayload) },
                outputTempDir = tempDir,
                masterKey = bundle.masterKey,
                vaultId = bundle.config.vaultId,
                originalFilename = "biology_notes.pdf",
                title = "Biology Notes",
                description = "Secret notes",
                category = "Biology",
                tags = listOf("bio", "admission"),
                collection = "2026",
                originalSizeBytes = pdfPayload.size.toLong(),
                chunkSize = 1024
            )

            // Ensure public .pvmeta does not leak original filename or title
            assertFalse(pair.pvmetaJson.contains("biology_notes.pdf"))
            assertFalse(pair.pvmetaJson.contains("Biology Notes"))

            val pvaultFile = File(pair.pvaultTempFilePath)
            val encryptedBytes = pvaultFile.readBytes()
            assertFalse(String(encryptedBytes, Charsets.ISO_8859_1).contains("SECRET_ACADEMIC_PDF_CONTENT"))

            val decryptedOut = File(tempDir, "decrypted.pdf")
            engine.decryptPdfFileChunked(pvaultFile, decryptedOut, pair.descriptor, bundle.masterKey)
            assertArrayEquals(pdfPayload, decryptedOut.readBytes())

            // Rotate entire Vault Master Key and confirm old key fails while new key decrypts
            val rotation = engine.rotateEntireVaultKey(
                oldMasterKey = bundle.masterKey,
                newPassword = "RotatedPassword!2026".toCharArray(),
                oldConfig = bundle.config,
                existingDescriptors = listOf(pair.descriptor)
            )
            assertNotEquals(bundle.masterKey.toList(), rotation.newMasterKey.toList())
            val rotatedEnv = rotation.updatedSidecars[pair.assetId]!!.second
            val rotatedDesc = engine.decryptSidecarDescriptor(rotatedEnv, rotation.newMasterKey)
            val rotatedOut = File(tempDir, "rotated_decrypted.pdf")
            engine.decryptPdfFileChunked(pvaultFile, rotatedOut, rotatedDesc, rotation.newMasterKey)
            assertArrayEquals(pdfPayload, rotatedOut.readBytes())

            // Tamper with ciphertext byte inside .pvault and verify AEAD tag failure
            val raf = RandomAccessFile(pvaultFile, "rw")
            raf.seek(pvaultFile.length() - 20)
            val orig = raf.readByte()
            raf.seek(pvaultFile.length() - 20)
            raf.writeByte((orig.toInt() xor 0xFF))
            raf.close()

            assertThrows(VaultCryptoException.AuthenticationFailed::class.java) {
                engine.decryptPdfFileChunked(pvaultFile, File(tempDir, "tampered.pdf"), rotatedDesc, rotation.newMasterKey)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsNonPdfHeader() {
        val tempDir = createTempDirectory("pvault_invalid").toFile()
        try {
            val bundle = engine.createNewVault("StrongPassword#123".toCharArray())
            val fakeBytes = "NOT_A_PDF_FILE_CONTENT".toByteArray()
            assertThrows(VaultCryptoException.InvalidPdf::class.java) {
                engine.encryptPdfFileChunked(
                    inputStreamProvider = { ByteArrayInputStream(fakeBytes) },
                    outputTempDir = tempDir,
                    masterKey = bundle.masterKey,
                    vaultId = bundle.config.vaultId,
                    originalFilename = "fake.pdf",
                    title = "Fake",
                    description = "",
                    category = "Other",
                    tags = emptyList(),
                    collection = "",
                    originalSizeBytes = fakeBytes.size.toLong()
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun bundledSamplePdfAssetIsValidAndEncryptable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val header = context.assets.open(VaultRepository.BUNDLED_SAMPLE_ASSET_NAME).use { stream ->
            ByteArray(5).also { buf ->
                val read = stream.read(buf)
                assertEquals(5, read)
            }
        }
        assertArrayEquals("%PDF-".toByteArray(Charsets.US_ASCII), header)

        val tempDir = createTempDirectory("pvault_sample_test").toFile()
        try {
            val sampleSize = context.assets.openFd(VaultRepository.BUNDLED_SAMPLE_ASSET_NAME).use { it.length }
            assertTrue(sampleSize > 1_000_000L)

            val bundle = engine.createNewVault(
                password = "SampleVaultPassword#2026".toCharArray(),
                kdfAlgorithm = KdfAlgorithm.PBKDF2_SHA256,
                iterations = 100_000
            )
            val pair = engine.encryptPdfFileChunked(
                inputStreamProvider = { context.assets.open(VaultRepository.BUNDLED_SAMPLE_ASSET_NAME) },
                outputTempDir = tempDir,
                masterKey = bundle.masterKey,
                vaultId = bundle.config.vaultId,
                originalFilename = VaultRepository.BUNDLED_SAMPLE_ORIGINAL_FILENAME,
                title = "Chemistry 2nd Paper - Chapter 1",
                description = "Sample",
                category = "Chemistry",
                tags = listOf("Chemistry", "Chapter 1"),
                collection = "Chemistry 2nd Paper",
                originalSizeBytes = sampleSize
            )

            assertEquals(sampleSize, pair.descriptor.originalSizeBytes)
            assertTrue(pair.descriptor.chunkCount >= 2)
            val pvaultFile = File(pair.pvaultTempFilePath)
            assertTrue(pvaultFile.exists())

            val decryptedOut = File(tempDir, "sample_decrypted.pdf")
            engine.decryptPdfFileChunked(pvaultFile, decryptedOut, pair.descriptor, bundle.masterKey)
            assertEquals(sampleSize, decryptedOut.length())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
