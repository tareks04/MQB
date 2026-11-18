package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Stores ONLY encrypted .pvmeta JSON envelopes and encrypted file locations.
 * Zero plaintext PDF titles, filenames, or descriptions exist in SQLite.
 */
@Entity(tableName = "cached_sidecars")
data class CachedSidecarEntity(
    @PrimaryKey val assetId: String,
    val vaultId: String,
    val encryptedMetaJson: String,
    val releaseId: Long,
    val releaseTag: String,
    val pvaultAssetId: Long,
    val pvmetaAssetId: Long,
    val pvaultDownloadUrl: String,
    val pvmetaDownloadUrl: String,
    val pvaultSizeBytes: Long,
    val pvmetaSizeBytes: Long,
    val isOfflineCached: Boolean = false,
    val localPvaultPath: String? = null,
    val isLocalOnlyImport: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Stores reading position and bookmarks AES-256-GCM encrypted with the Vault Master Key.
 */
@Entity(tableName = "encrypted_reading_states")
data class EncryptedReadingStateEntity(
    @PrimaryKey val assetId: String,
    val encryptedStateBase64: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Dao
interface VaultDao {
    @Query("SELECT * FROM cached_sidecars WHERE vaultId = :vaultId")
    fun observeSidecarsForVault(vaultId: String): Flow<List<CachedSidecarEntity>>

    @Query("SELECT * FROM cached_sidecars WHERE vaultId = :vaultId")
    suspend fun getSidecarsForVault(vaultId: String): List<CachedSidecarEntity>

    @Query("SELECT * FROM cached_sidecars WHERE assetId = :assetId LIMIT 1")
    suspend fun getSidecar(assetId: String): CachedSidecarEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSidecars(items: List<CachedSidecarEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSidecar(item: CachedSidecarEntity)

    @Query("DELETE FROM cached_sidecars WHERE assetId = :assetId")
    suspend fun deleteSidecar(assetId: String)

    @Query("DELETE FROM cached_sidecars WHERE vaultId = :vaultId AND isLocalOnlyImport = 0 AND assetId NOT IN (:activeAssetIds)")
    suspend fun pruneRemovedCloudSidecars(vaultId: String, activeAssetIds: List<String>)

    @Query("DELETE FROM cached_sidecars WHERE vaultId = :vaultId AND isLocalOnlyImport = 0")
    suspend fun deleteAllCloudSidecars(vaultId: String)

    @Query("UPDATE cached_sidecars SET isOfflineCached = :cached, localPvaultPath = :path WHERE assetId = :assetId")
    suspend fun updateOfflineCacheStatus(assetId: String, cached: Boolean, path: String?)

    @Query("SELECT * FROM encrypted_reading_states")
    suspend fun getAllReadingStates(): List<EncryptedReadingStateEntity>

    @Query("SELECT * FROM encrypted_reading_states WHERE assetId = :assetId LIMIT 1")
    suspend fun getReadingState(assetId: String): EncryptedReadingStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadingState(state: EncryptedReadingStateEntity)
}

@Database(
    entities = [CachedSidecarEntity::class, EncryptedReadingStateEntity::class],
    version = 1,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var INSTANCE: VaultDatabase? = null

        fun getInstance(context: Context): VaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "personal_pdf_vault.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
