package com.mangako.app.data.lanraragi

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.cio.readChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

/**
 * Thin wrapper around the LANraragi HTTP API. We only use the upload endpoint
 * (`PUT /api/archives/upload`) and let the server settle the archive ID & dedup.
 *
 * The route is PUT, not POST — that mismatch is what was causing the 404s
 * users were hitting; LANraragi's openapi.yaml registers the operation under
 * the PUT verb and Mojolicious only matches the verb the spec declares.
 *
 * Auth: `Authorization: Bearer <base64(apiKey)>` per LANraragi docs. We
 * explicitly tell Ktor's logger to redact that header so the key never reaches
 * logcat — a lesson learned from the security review.
 *
 * The upload body is streamed from disk via [ChannelProvider] so we don't have
 * to buffer the entire (potentially hundreds of MB) archive in JVM heap.
 */
class LanraragiClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    private fun engine(timeoutMs: Long): HttpClient = buildDefaultClient(apiKey, timeoutMs)

    @Serializable
    data class UploadResponse(
        val success: Int? = null,
        val id: String? = null,
        val message: String? = null,
    )

    /**
     * PUT multipart upload of [file]. [uploadAs] is the filename the server
     * records; we strip path separators and control chars before putting it in
     * `Content-Disposition` so a misconfigured pipeline can't produce a
     * traversal-looking filename.
     *
     * Why we build [PartData] by hand instead of using Ktor's `formData {}`:
     * `formData.append(key, value, headers)` always emits its own
     * `Content-Disposition: form-data; name="$key"` header AND appends any
     * Content-Disposition we pass in `headers`. The two end up on the wire
     * as two separate Content-Disposition lines; Mojolicious comma-folds
     * them and loses the field-name attribute, so [Mojo::Upload] gives back
     * undef and LANraragi reports 'no file attached'. Constructing the
     * PartData list directly lets us emit exactly one well-formed
     * `Content-Disposition: form-data; name="file"; filename="..."`.
     */
    suspend fun uploadArchive(file: File, uploadAs: String): Result<UploadResponse> {
        require(file.exists()) { "File does not exist: ${file.path}" }
        val safeName = sanitizeFilename(uploadAs)
        val client = engine(UPLOAD_TIMEOUT_MS)
        return try {
            val endpoint = baseUrl.trimEnd('/') + "/api/archives/upload"
            val parts = listOf<PartData>(
                PartData.BinaryChannelItem(
                    provider = { file.readChannel() },
                    partHeaders = Headers.build {
                        append(
                            HttpHeaders.ContentDisposition,
                            "form-data; name=\"file\"; filename=\"${safeName.escapeForContentDisposition()}\"",
                        )
                        append(HttpHeaders.ContentType, "application/vnd.comicbook+zip")
                    },
                ),
                PartData.FormItem(
                    value = safeName.removeSuffix(".cbz"),
                    dispose = {},
                    partHeaders = Headers.build {
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"title\"")
                    },
                ),
            )
            val response: HttpResponse = client.put(endpoint) {
                setBody(MultiPartFormDataContent(parts))
            }
            when (response.status) {
                HttpStatusCode.OK -> Result.success(response.body<UploadResponse>())
                else -> {
                    val detail = runCatching { response.bodyAsText() }.getOrNull()?.trim().orEmpty()
                    val msg = buildString {
                        append("HTTP ").append(response.status.value)
                        append(": ").append(response.status.description)
                        if (detail.isNotEmpty()) append(" — ").append(detail.take(500))
                    }
                    Result.failure(LanraragiException(code = response.status.value, message = msg))
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            client.close()
        }
    }

    /** Reachability probe for Settings → "Test connection". */
    suspend fun ping(): Result<Int> {
        val client = engine(PING_TIMEOUT_MS)
        return try {
            val res: HttpResponse = client.get(baseUrl.trimEnd('/') + "/api/info")
            Result.success(res.status.value)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            client.close()
        }
    }

    // Strip path separators, control chars, and anything that could confuse a
    // `Content-Disposition: filename="..."` parser. We ALSO strip here because
    // a user who disabled the sanitize rule in the pipeline shouldn't be able
    // to send `../foo.cbz` to the server.
    private fun sanitizeFilename(name: String): String =
        name.replace(Regex("""[\\/\p{Cntrl}]"""), "").trim()

    private fun String.escapeForContentDisposition(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val UPLOAD_TIMEOUT_MS = 180_000L
        private const val PING_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L

        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private fun buildDefaultClient(apiKey: String, timeoutMs: Long): HttpClient = HttpClient(OkHttp) {
            engine { config { retryOnConnectionFailure(true) } }
            install(ContentNegotiation) { json(JSON) }
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = timeoutMs
            }
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.INFO
                // Never let the bearer token hit logcat. Ktor's default logger
                // dumps full headers at INFO level, which would persist the API
                // key in anyone's crash-reporter or logcat buffer.
                sanitizeHeader { header -> header == HttpHeaders.Authorization }
            }
            defaultRequest {
                header(
                    HttpHeaders.Authorization,
                    "Bearer " + Base64.getEncoder().encodeToString(apiKey.toByteArray(Charsets.UTF_8)),
                )
                header(HttpHeaders.Accept, "application/json")
            }
        }
    }
}

class LanraragiException(val code: Int, message: String) : RuntimeException(message)
