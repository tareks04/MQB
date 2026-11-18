package com.example.github

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Official GitHub REST API v3 client.
 * - Supports public unauthenticated reads and optional in-memory PAT for owner mutations.
 * - Uses ETag conditional requests (If-None-Match) and full pagination.
 * - Never logs Authorization headers or token values.
 */
class GitHubApiClient {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val releaseListType = Types.newParameterizedType(List::class.java, GitHubReleaseDto::class.java)
    private val releaseListAdapter = moshi.adapter<List<GitHubReleaseDto>>(releaseListType)
    private val releaseAdapter = moshi.adapter(GitHubReleaseDto::class.java)
    private val assetListType = Types.newParameterizedType(List::class.java, GitHubAssetDto::class.java)
    private val assetListAdapter = moshi.adapter<List<GitHubAssetDto>>(assetListType)
    private val assetAdapter = moshi.adapter(GitHubAssetDto::class.java)
    private val createReleaseAdapter = moshi.adapter(CreateReleaseRequestDto::class.java)

    private val etagCache = ConcurrentHashMap<String, Pair<String, String>>()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun listAllReleasesWithAssets(
        owner: String,
        repo: String,
        token: String? = null
    ): List<GitHubReleaseDto> = withContext(Dispatchers.IO) {
        validateOwnerRepo(owner, repo)
        val releases = mutableListOf<GitHubReleaseDto>()
        var page = 1
        while (true) {
            val url = "https://api.github.com/repos/$owner/$repo/releases?per_page=100&page=$page"
            val body = executeJsonGet(url, token)
            val pageList = releaseListAdapter.fromJson(body) ?: emptyList()
            if (pageList.isEmpty()) break

            for (rel in pageList) {
                // If release has >= 30 assets, paginate /releases/{id}/assets to guarantee full list
                val fullAssets = if (rel.assets.size >= 30) {
                    listAllAssetsForRelease(owner, repo, rel.id, token)
                } else {
                    rel.assets
                }
                releases.add(rel.copy(assets = fullAssets))
            }
            if (pageList.size < 100) break
            page++
        }
        releases
    }

    suspend fun listAllAssetsForRelease(
        owner: String,
        repo: String,
        releaseId: Long,
        token: String? = null
    ): List<GitHubAssetDto> = withContext(Dispatchers.IO) {
        val allAssets = mutableListOf<GitHubAssetDto>()
        var page = 1
        while (true) {
            val url = "https://api.github.com/repos/$owner/$repo/releases/$releaseId/assets?per_page=100&page=$page"
            val body = executeJsonGet(url, token)
            val pageAssets = assetListAdapter.fromJson(body) ?: emptyList()
            if (pageAssets.isEmpty()) break
            allAssets.addAll(pageAssets)
            if (pageAssets.size < 100) break
            page++
        }
        allAssets
    }

    suspend fun getReleaseByTag(
        owner: String,
        repo: String,
        tag: String,
        token: String? = null
    ): GitHubReleaseDto? = withContext(Dispatchers.IO) {
        validateOwnerRepo(owner, repo)
        val encodedTag = URLEncoder.encode(tag, "UTF-8")
        val url = "https://api.github.com/repos/$owner/$repo/releases/tags/$encodedTag"
        try {
            val body = executeJsonGet(url, token)
            val rel = releaseAdapter.fromJson(body) ?: return@withContext null
            val fullAssets = if (rel.assets.size >= 30) {
                listAllAssetsForRelease(owner, repo, rel.id, token)
            } else rel.assets
            rel.copy(assets = fullAssets)
        } catch (e: GitHubApiException.RepoNotFound) {
            null
        } catch (e: GitHubApiException.ReleaseNotFound) {
            null
        }
    }

    suspend fun createRelease(
        owner: String,
        repo: String,
        tag: String,
        name: String,
        bodyText: String,
        token: String
    ): GitHubReleaseDto = withContext(Dispatchers.IO) {
        validateOwnerRepo(owner, repo)
        val url = "https://api.github.com/repos/$owner/$repo/releases"
        val payload = createReleaseAdapter.toJson(
            CreateReleaseRequestDto(tagName = tag, name = name, body = bodyText)
        )
        val req = baseBuilder(url, token)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            checkErrorResponse(resp.code, resp.headers["X-RateLimit-Reset"], respBody, isReleaseEndpoint = true)
            etagCache.clear()
            releaseAdapter.fromJson(respBody)
                ?: throw GitHubApiException.HttpError(resp.code, "Malformed release response")
        }
    }

    suspend fun uploadReleaseAssetFile(
        owner: String,
        repo: String,
        releaseId: Long,
        assetName: String,
        file: File,
        token: String,
        onProgress: (Float) -> Unit = {}
    ): GitHubAssetDto = withContext(Dispatchers.IO) {
        validateOwnerRepo(owner, repo)
        val encodedName = URLEncoder.encode(assetName, "UTF-8")
        val uploadUrl = "https://uploads.github.com/repos/$owner/$repo/releases/$releaseId/assets?name=$encodedName"
        val totalLen = file.length().coerceAtLeast(1L)

        val streamingBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = file.length()
            override fun writeTo(sink: BufferedSink) {
                var uploaded = 0L
                val buf = ByteArray(64 * 1024)
                file.inputStream().use { fis ->
                    while (true) {
                        val read = fis.read(buf)
                        if (read == -1) break
                        sink.write(buf, 0, read)
                        uploaded += read
                        onProgress((uploaded.toFloat() / totalLen.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
        }

        val req = baseBuilder(uploadUrl, token)
            .post(streamingBody)
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                checkErrorResponse(resp.code, resp.headers["X-RateLimit-Reset"], respBody, isReleaseEndpoint = true)
                etagCache.clear()
                assetAdapter.fromJson(respBody)
                    ?: throw GitHubApiException.HttpError(resp.code, "Malformed asset upload response")
            }
        } catch (e: IOException) {
            throw GitHubApiException.NetworkError("Upload network error: ${e.message}", e)
        }
    }

    suspend fun uploadReleaseAssetBytes(
        owner: String,
        repo: String,
        releaseId: Long,
        assetName: String,
        bytes: ByteArray,
        token: String,
        contentType: String = "application/json"
    ): GitHubAssetDto = withContext(Dispatchers.IO) {
        validateOwnerRepo(owner, repo)
        val encodedName = URLEncoder.encode(assetName, "UTF-8")
        val uploadUrl = "https://uploads.github.com/repos/$owner/$repo/releases/$releaseId/assets?name=$encodedName"
        val req = baseBuilder(uploadUrl, token)
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                checkErrorResponse(resp.code, resp.headers["X-RateLimit-Reset"], respBody, isReleaseEndpoint = true)
                etagCache.clear()
                assetAdapter.fromJson(respBody)
                    ?: throw GitHubApiException.HttpError(resp.code, "Malformed asset upload response")
            }
        } catch (e: IOException) {
            throw GitHubApiException.NetworkError("Upload network error: ${e.message}", e)
        }
    }

    suspend fun deleteReleaseAsset(
        owner: String,
        repo: String,
        assetId: Long,
        token: String
    ) = withContext(Dispatchers.IO) {
        validateOwnerRepo(owner, repo)
        val url = "https://api.github.com/repos/$owner/$repo/releases/assets/$assetId"
        val req = baseBuilder(url, token)
            .delete()
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 204 || resp.code == 404) {
                    etagCache.clear()
                    return@withContext
                }
                val body = resp.body?.string().orEmpty()
                checkErrorResponse(resp.code, resp.headers["X-RateLimit-Reset"], body, isReleaseEndpoint = true)
            }
        } catch (e: IOException) {
            throw GitHubApiException.NetworkError("Delete asset network error: ${e.message}", e)
        }
    }

    suspend fun downloadTextAsset(
        downloadUrl: String,
        apiAssetUrl: String? = null,
        token: String? = null
    ): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "PersonalPDFVault-Android/1.0")
            .apply {
                if (!token.isNullOrBlank() && apiAssetUrl != null && downloadUrl == apiAssetUrl) {
                    header("Authorization", "Bearer $token")
                    header("Accept", "application/octet-stream")
                }
            }
            .get()
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    checkErrorResponse(resp.code, resp.headers["X-RateLimit-Reset"], body, isReleaseEndpoint = false)
                }
                body
            }
        } catch (e: IOException) {
            throw GitHubApiException.NetworkError("Asset download failed: ${e.message}", e)
        }
    }

    suspend fun downloadBinaryAssetToFile(
        downloadUrl: String,
        destinationFile: File,
        expectedSize: Long = -1L,
        onProgress: (Float) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "PersonalPDFVault-Android/1.0")
            .get()
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    checkErrorResponse(resp.code, resp.headers["X-RateLimit-Reset"], err, isReleaseEndpoint = false)
                }
                val body = resp.body ?: throw GitHubApiException.NetworkError("Empty download body")
                val total = (if (body.contentLength() > 0) body.contentLength() else expectedSize).coerceAtLeast(1L)
                var downloaded = 0L
                val buf = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { out ->
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            out.write(buf, 0, read)
                            downloaded += read
                            onProgress((downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                        out.flush()
                    }
                }
            }
        } catch (e: IOException) {
            destinationFile.delete()
            throw GitHubApiException.NetworkError("Binary asset download failed: ${e.message}", e)
        }
    }

    private fun executeJsonGet(url: String, token: String?): String {
        val cached = etagCache[url]
        val reqBuilder = baseBuilder(url, token)
        if (cached != null) {
            reqBuilder.header("If-None-Match", cached.first)
        }
        try {
            httpClient.newCall(reqBuilder.get().build()).execute().use { resp ->
                if (resp.code == 304 && cached != null) {
                    return cached.second
                }
                val body = resp.body?.string().orEmpty()
                checkErrorResponse(resp.code, resp.headers["X-RateLimit-Reset"], body, isReleaseEndpoint = true)
                val etag = resp.header("ETag")
                if (!etag.isNullOrBlank()) {
                    etagCache[url] = etag to body
                }
                return body
            }
        } catch (e: IOException) {
            throw GitHubApiException.NetworkError("GitHub network error: ${e.message}", e)
        }
    }

    private fun baseBuilder(url: String, token: String?): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "PersonalPDFVault-Android/1.0")
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    private fun checkErrorResponse(
        code: Int,
        rateResetHeader: String?,
        body: String,
        isReleaseEndpoint: Boolean
    ) {
        if (code in 200..299) return
        when (code) {
            401 -> throw GitHubApiException.Unauthorized("GitHub token rejected or expired (401)")
            403 -> {
                if (body.contains("rate limit", ignoreCase = true)) {
                    val reset = rateResetHeader?.toLongOrNull() ?: 0L
                    throw GitHubApiException.RateLimited(reset, "GitHub API rate limit exceeded")
                }
                throw GitHubApiException.Forbidden("Insufficient GitHub repository permissions (403)")
            }
            404 -> {
                if (isReleaseEndpoint && body.contains("Not Found", ignoreCase = true)) {
                    throw GitHubApiException.ReleaseNotFound("GitHub repository or release not found (404)")
                }
                throw GitHubApiException.RepoNotFound("GitHub resource not found (404)")
            }
            429 -> {
                val reset = rateResetHeader?.toLongOrNull() ?: 0L
                throw GitHubApiException.RateLimited(reset, "Too many requests to GitHub API (429)")
            }
            else -> throw GitHubApiException.HttpError(code, "GitHub API error ($code)")
        }
    }

    private fun validateOwnerRepo(owner: String, repo: String) {
        if (owner.isBlank() || repo.isBlank()) {
            throw GitHubApiException.RepoNotFound("GitHub owner and repository must be configured")
        }
    }
}
