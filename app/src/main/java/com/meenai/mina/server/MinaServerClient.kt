package com.meenai.mina.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Typed failures the UI can switch on without parsing strings. */
sealed class ServerFailure(message: String) : Exception(message) {
    /** Device has no usable network; queue or retry per caller policy. */
    class Offline(cause: IOException) : ServerFailure(cause.message ?: "offline")
    /** Backend did not answer inside the timeout; show retry, keep local state. */
    class Unreachable(cause: IOException) : ServerFailure(cause.message ?: "unreachable")
    /** 429; retryAfterMs honours the Retry-After header. */
    class RateLimited(val retryAfterMs: Long) : ServerFailure("rate limited")
    /** HTTP error with the backend's {detail} payload when present. */
    class Api(val status: Int, val code: String) : ServerFailure("http $status: $code")
    /** 409 reauthorization_required from a Composio connection; deep-link reconnect. */
    class ReauthorizationRequired(val connectionHint: String) : ServerFailure("reauth required")
}

class MinaServerClient(
    private val config: ServerConfig,
    private val tokens: MinaTokenProvider,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    suspend fun get(path: String, auth: Boolean = true): JsonElement =
        request("GET", path, body = null, auth = auth)

    suspend fun post(path: String, body: JsonElement? = null, auth: Boolean = true): JsonElement =
        request("POST", path, body, auth)

    suspend fun patch(path: String, body: JsonElement, auth: Boolean = true): JsonElement =
        request("PATCH", path, body, auth)

    suspend fun delete(path: String, auth: Boolean = true): JsonElement =
        request("DELETE", path, body = null, auth)

    /** Core request loop: one automatic retry after a token refresh, Retry-After aware 429s. */
    private suspend fun request(
        method: String,
        path: String,
        body: JsonElement?,
        auth: Boolean,
        attempt: Int = 0,
    ): JsonElement = withContext(Dispatchers.IO) {
        val url = (config.apiBaseUrl + path).toHttpUrl()
        val builder = Request.Builder().url(url).method(method, body?.let {
            it.toString().toRequestBody(jsonMedia)
        } ?: if (method == "GET" || method == "DELETE") null
        else ByteArray(0).toRequestBody(jsonMedia))
        if (auth) {
            val token = tokens.accessToken()
                ?: throw ServerFailure.Api(401, "not_signed_in")
            builder.header("Authorization", "Bearer $token")
        }
        val response = try {
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw if (isNetworkDown(e)) ServerFailure.Offline(e) else ServerFailure.Unreachable(e)
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            when {
                resp.code == 401 && auth && attempt == 0 -> {
                    // Access token expired or rotated out: refresh once and replay once.
                    tokens.refresh() ?: throw ServerFailure.Api(401, "session_expired")
                    return@withContext request(method, path, body, auth, attempt = 1)
                }
                resp.code == 429 -> {
                    val retryAfter = (resp.header("Retry-After")?.toDoubleOrNull()
                        ?: (2.0 * (1 shl attempt))).coerceIn(1.0, 60.0)
                    if (attempt < 2) {
                        delay((retryAfter * 1000).toLong())
                        return@withContext request(method, path, body, auth, attempt + 1)
                    }
                    throw ServerFailure.RateLimited((retryAfter * 1000).toLong())
                }
                resp.code == 409 && text.contains("reauthorization_required") ->
                    throw ServerFailure.ReauthorizationRequired(text)
                !resp.isSuccessful -> throw ServerFailure.Api(resp.code, detailOf(text))
                else -> if (text.isBlank()) buildJsonObject { put("ok", true) }
                else json.parseToJsonElement(text)
            }
        }
    }

    private fun detailOf(text: String): String =
        runCatching {
            (json.parseToJsonElement(text) as? JsonObject)?.get("detail")?.toString().orEmpty()
        }.getOrDefault(text.take(200))

    private fun isNetworkDown(e: IOException): Boolean =
        e.message?.contains("Unable to resolve host") == true
}

/** Convenience JSON builders so call sites stay one-liners. */
fun jsonObject(vararg pairs: Pair<String, JsonElement>): JsonObject =
    JsonObject(mapOf(*pairs))
