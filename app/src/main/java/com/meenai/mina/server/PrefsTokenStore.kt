package com.meenai.mina.server

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SharedPreferences-backed SupabaseTokenProvider.MinaTokenStore: the app's only server session storage. */
class PrefsTokenStore(context: Context) : SupabaseTokenProvider.MinaTokenStore {
    private val prefs = context.getSharedPreferences("mina_auth", Context.MODE_PRIVATE)

    override suspend fun accessToken(): String? = read("access")
    override suspend fun accessTokenExpiresAtMs(): Long? = withContext(Dispatchers.IO) {
        prefs.getLong("expires_at_ms", 0L).takeIf { it > 0 }
    }
    override suspend fun refreshToken(): String? = read("refresh")
    override suspend fun updateTokens(access: String, refresh: String, expiresAtMs: Long) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString("access", access).putString("refresh", refresh)
                .putLong("expires_at_ms", expiresAtMs).apply()
        }
    }
    override suspend fun clear() { withContext(Dispatchers.IO) { prefs.edit().clear().apply() } }

    private suspend fun read(key: String): String? = withContext(Dispatchers.IO) { prefs.getString(key, null) }
}
