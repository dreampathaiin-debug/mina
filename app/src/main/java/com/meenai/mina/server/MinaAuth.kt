package com.meenai.mina.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicReference

/** Supplies the Supabase access token used on every server request. */
interface MinaTokenProvider {
    /** Current access token, or null when the user is not signed in. */
    suspend fun accessToken(): String?

    /** Force a refresh; returns the new access token or null when refresh is impossible. */
    suspend fun refresh(): String?
}

/**
 * Supabase auth token provider: refreshes through POST /auth/v1/token?grant_type=refresh_token
 * using the anon key, single-flight, and auto-refreshes 60s before expiry.
 * The refresh token is held by whatever session store the app already uses
 * (implement a small MinaTokenStore and pass it in); nothing is written here.
 */
class SupabaseTokenProvider(
    private val config: ServerConfig,
    private val store: MinaTokenStore,
    private val http: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MinaTokenProvider {

    interface MinaTokenStore {
        suspend fun accessToken(): String?
        suspend fun accessTokenExpiresAtMs(): Long?
        suspend fun refreshToken(): String?
        suspend fun updateTokens(access: String, refresh: String, expiresAtMs: Long)
        suspend fun clear()
    }

    private val refreshMutex = Mutex()
    private val cached = AtomicReference<CachedToken?>(null)

    private data class CachedToken(val value: String, val expiresAtMs: Long)

    override suspend fun accessToken(): String? {
        val current = cached.get()
        val now = System.currentTimeMillis()
        if (current != null && current.expiresAtMs - 60_000 > now) return current.value
        return refresh()
    }

    override suspend fun refresh(): String? = refreshMutex.withLock {
        val refreshToken = store.refreshToken() ?: return@withLock null.also { store.clear() }
        return@withLock withContext(Dispatchers.IO) {
            runCatching {
                val body = """{"refresh_token":"$refreshToken"}"""
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${config.supabaseUrl}/auth/v1/token?grant_type=refresh_token")
                    .header("apikey", config.supabaseAnonKey)
                    .post(body)
                    .build()
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // Refresh rejected: the session is gone, sign-out is the only path.
                        store.clear()
                        return@use null
                    }
                    val obj = json.parseToJsonElement(text).jsonObject
                    val access = obj["access_token"]?.jsonPrimitive?.content ?: return@use null
                    val newRefresh = obj["refresh_token"]?.jsonPrimitive?.content ?: refreshToken
                    val expiresAt = obj["expires_at"]?.jsonPrimitive?.longOrNull
                        ?: (System.currentTimeMillis() / 1000 + 3600)
                    val expiresAtMs = expiresAt * 1000
                    store.updateTokens(access, newRefresh, expiresAtMs)
                    cached.set(CachedToken(access, expiresAtMs))
                    access
                }
            }.getOrNull()
        }
    }
}
