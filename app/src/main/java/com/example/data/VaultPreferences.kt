package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.vaultDataStore by preferencesDataStore(name = "vault_public_prefs")

data class PublicVaultSettings(
    val languageCode: String = "bn", // Bangla default
    val themeMode: String = "system", // system, light, dark
    val autoLockMinutes: Int = 10, // 5, 10, 15, 0 (manual)
    val githubOwner: String = "",
    val githubRepo: String = "personal-pdf-vault",
    val kdfAlgorithm: String = "Argon2id",
    val argon2MemoryKiB: Int = 19456,
    val argon2Iterations: Int = 2,
    val cachedVaultConfigJson: String? = null,
    val flagSecureEnabled: Boolean = false,
    val customCategoriesCsv: String = ""
)

/**
 * Stores ONLY public repository metadata and UI preferences.
 * Never stores Master Password, Master Key, Recovery Key, or GitHub PAT.
 */
class VaultPreferences(private val context: Context) {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language_code")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_LOCK_MINUTES = intPreferencesKey("auto_lock_minutes")
        val GITHUB_OWNER = stringPreferencesKey("github_owner")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val KDF_ALGORITHM = stringPreferencesKey("kdf_algorithm")
        val ARGON2_MEMORY_KIB = intPreferencesKey("argon2_memory_kib")
        val ARGON2_ITERATIONS = intPreferencesKey("argon2_iterations")
        val CACHED_VAULT_CONFIG = stringPreferencesKey("cached_vault_config_json")
        val FLAG_SECURE_ENABLED = booleanPreferencesKey("flag_secure_enabled")
        val CUSTOM_CATEGORIES = stringPreferencesKey("custom_categories_csv")
    }

    val settingsFlow: Flow<PublicVaultSettings> = context.vaultDataStore.data.map { prefs ->
        PublicVaultSettings(
            languageCode = prefs[Keys.LANGUAGE] ?: "bn",
            themeMode = prefs[Keys.THEME_MODE] ?: "system",
            autoLockMinutes = prefs[Keys.AUTO_LOCK_MINUTES] ?: 10,
            githubOwner = prefs[Keys.GITHUB_OWNER] ?: "",
            githubRepo = prefs[Keys.GITHUB_REPO] ?: "personal-pdf-vault",
            kdfAlgorithm = prefs[Keys.KDF_ALGORITHM] ?: "Argon2id",
            argon2MemoryKiB = prefs[Keys.ARGON2_MEMORY_KIB] ?: 19456,
            argon2Iterations = prefs[Keys.ARGON2_ITERATIONS] ?: 2,
            cachedVaultConfigJson = prefs[Keys.CACHED_VAULT_CONFIG],
            flagSecureEnabled = prefs[Keys.FLAG_SECURE_ENABLED] ?: false,
            customCategoriesCsv = prefs[Keys.CUSTOM_CATEGORIES] ?: ""
        )
    }

    suspend fun setLanguage(code: String) {
        context.vaultDataStore.edit { it[Keys.LANGUAGE] = if (code == "en") "en" else "bn" }
    }

    suspend fun setThemeMode(mode: String) {
        context.vaultDataStore.edit { it[Keys.THEME_MODE] = mode }
    }

    suspend fun setAutoLockMinutes(minutes: Int) {
        context.vaultDataStore.edit { it[Keys.AUTO_LOCK_MINUTES] = minutes }
    }

    suspend fun setGitHubRepoConfig(owner: String, repo: String) {
        context.vaultDataStore.edit {
            it[Keys.GITHUB_OWNER] = owner.trim()
            it[Keys.GITHUB_REPO] = repo.trim().ifEmpty { "personal-pdf-vault" }
        }
    }

    suspend fun setKdfSettings(algorithm: String, memoryKiB: Int, iterations: Int) {
        context.vaultDataStore.edit {
            it[Keys.KDF_ALGORITHM] = algorithm
            it[Keys.ARGON2_MEMORY_KIB] = memoryKiB
            it[Keys.ARGON2_ITERATIONS] = iterations
        }
    }

    suspend fun saveCachedVaultConfigJson(configJson: String?) {
        context.vaultDataStore.edit {
            if (configJson == null) it.remove(Keys.CACHED_VAULT_CONFIG)
            else it[Keys.CACHED_VAULT_CONFIG] = configJson
        }
    }

    suspend fun setFlagSecureEnabled(enabled: Boolean) {
        context.vaultDataStore.edit { it[Keys.FLAG_SECURE_ENABLED] = enabled }
    }

    suspend fun addCustomCategory(category: String) {
        val clean = category.trim()
        if (clean.isEmpty()) return
        context.vaultDataStore.edit { prefs ->
            val current = prefs[Keys.CUSTOM_CATEGORIES]
                ?.split("|")
                ?.filter { it.isNotBlank() }
                ?.toMutableSet() ?: mutableSetOf()
            current.add(clean)
            prefs[Keys.CUSTOM_CATEGORIES] = current.joinToString("|")
        }
    }
}
