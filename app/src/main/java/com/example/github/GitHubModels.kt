package com.example.github

import com.example.crypto.PdfDescriptor
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GitHubAssetDto(
    val id: Long,
    val name: String,
    val size: Long,
    @Json(name = "browser_download_url") val browserDownloadUrl: String,
    @Json(name = "created_at") val createdAt: String = "",
    @Json(name = "updated_at") val updatedAt: String = "",
    @Json(name = "content_type") val contentType: String = "application/octet-stream"
)

@JsonClass(generateAdapter = true)
data class GitHubReleaseDto(
    val id: Long,
    @Json(name = "tag_name") val tagName: String,
    val name: String? = null,
    @Json(name = "upload_url") val uploadUrl: String = "",
    val assets: List<GitHubAssetDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CreateReleaseRequestDto(
    @Json(name = "tag_name") val tagName: String,
    val name: String,
    val body: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false
)

data class VaultDocumentRecord(
    val assetId: String,
    val descriptor: PdfDescriptor,
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
    val lastOpenedAtEpochMs: Long = 0L,
    val lastReadPage: Int = 0,
    val totalPages: Int = 0,
    val bookmarks: List<Int> = emptyList(),
    val isLocalOnlyImport: Boolean = false
) {
    val totalEncryptedBytes: Long
        get() = pvaultSizeBytes + pvmetaSizeBytes
}

enum class IncompleteIssueType {
    MISSING_SIDECAR,
    MISSING_PVAULT,
    CORRUPTED_OR_FOREIGN_SIDECAR,
    DUPLICATE_ASSET
}

data class IncompleteAssetIssue(
    val logicalId: String,
    val filename: String,
    val releaseTag: String,
    val assetId: Long,
    val sizeBytes: Long,
    val downloadUrl: String,
    val issueType: IncompleteIssueType,
    val detail: String = ""
)

data class SyncReport(
    val syncedAtEpochMs: Long,
    val newCount: Int,
    val existingCount: Int,
    val incompleteIssues: List<IncompleteAssetIssue>,
    val duplicateCount: Int,
    val invalidCount: Int,
    val totalEncryptedBytes: Long,
    val releaseAssetCounts: Map<String, Int>,
    val warningMessage: String? = null
)

sealed class GitHubApiException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class RepoNotFound(message: String = "Repository not found") : GitHubApiException(message)
    class ReleaseNotFound(message: String = "Release not found") : GitHubApiException(message)
    class Unauthorized(message: String = "Token invalid or expired") : GitHubApiException(message)
    class Forbidden(message: String = "Insufficient repository permissions") : GitHubApiException(message)
    class RateLimited(val resetEpochSeconds: Long, message: String = "Rate limit exceeded") :
        GitHubApiException(message)
    class NetworkError(message: String, cause: Throwable? = null) : GitHubApiException(message, cause)
    class HttpError(val code: Int, message: String) : GitHubApiException(message)
}
