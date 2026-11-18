package com.example.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Arrays

/**
 * Holds sensitive session secrets strictly in RAM.
 * Never persists Master Password, Master Key, Recovery Key, or GitHub PAT to disk.
 */
class VaultSessionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inactivityJob: Job? = null

    private var masterKeyBytes: ByteArray? = null
    private var githubPat: String? = null

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _hasGitHubToken = MutableStateFlow(false)
    val hasGitHubToken: StateFlow<Boolean> = _hasGitHubToken.asStateFlow()

    private val _remainingAutoLockSeconds = MutableStateFlow<Long?>(null)
    val remainingAutoLockSeconds: StateFlow<Long?> = _remainingAutoLockSeconds.asStateFlow()

    private var autoLockMinutes: Int = 10
    private var onAutoLockedCallback: (() -> Unit)? = null

    fun setOnAutoLockedListener(listener: () -> Unit) {
        onAutoLockedCallback = listener
    }

    fun updateAutoLockMinutes(minutes: Int) {
        autoLockMinutes = minutes
        if (_isUnlocked.value) {
            resetInactivityTimer()
        }
    }

    fun unlock(masterKey: ByteArray) {
        clearMasterKeyOnly()
        masterKeyBytes = masterKey.copyOf()
        _isUnlocked.value = true
        resetInactivityTimer()
    }

    fun replaceMasterKeyInSession(newMasterKey: ByteArray) {
        clearMasterKeyOnly()
        masterKeyBytes = newMasterKey.copyOf()
        _isUnlocked.value = true
        resetInactivityTimer()
    }

    fun getMasterKeyCopy(): ByteArray? {
        touchActivity()
        return masterKeyBytes?.copyOf()
    }

    fun setGitHubToken(token: String?) {
        val trimmed = token?.trim().orEmpty()
        githubPat = if (trimmed.isEmpty()) null else trimmed
        _hasGitHubToken.value = githubPat != null
    }

    fun getGitHubToken(): String? = githubPat

    fun clearGitHubToken() {
        githubPat = null
        _hasGitHubToken.value = false
    }

    fun touchActivity() {
        if (_isUnlocked.value) {
            resetInactivityTimer()
        }
    }

    fun lockVault(clearTokenToo: Boolean = false) {
        inactivityJob?.cancel()
        inactivityJob = null
        clearMasterKeyOnly()
        _isUnlocked.value = false
        _remainingAutoLockSeconds.value = null
        if (clearTokenToo) {
            clearGitHubToken()
        }
    }

    fun clearAllSessionCredentials() {
        lockVault(clearTokenToo = true)
    }

    private fun clearMasterKeyOnly() {
        masterKeyBytes?.let { Arrays.fill(it, 0.toByte()) }
        masterKeyBytes = null
    }

    private fun resetInactivityTimer() {
        inactivityJob?.cancel()
        if (autoLockMinutes <= 0) {
            _remainingAutoLockSeconds.value = null
            return
        }
        val totalSeconds = autoLockMinutes * 60L
        _remainingAutoLockSeconds.value = totalSeconds
        inactivityJob = scope.launch {
            var remaining = totalSeconds
            while (remaining > 0 && _isUnlocked.value) {
                delay(1_000L)
                remaining -= 1L
                _remainingAutoLockSeconds.value = remaining
            }
            if (_isUnlocked.value && remaining <= 0L) {
                lockVault(clearTokenToo = false)
                onAutoLockedCallback?.invoke()
            }
        }
    }
}
