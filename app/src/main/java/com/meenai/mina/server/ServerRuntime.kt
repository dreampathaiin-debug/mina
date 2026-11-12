package com.meenai.mina.server

import android.content.Context

/**
 * One place the app reaches the Mina Cloud backend. Enabled only when an
 * HTTPS API base URL is baked in; every call carries the Supabase access
 * token, which refreshes itself 60s before expiry.
 */
object ServerRuntime {
    val config: ServerConfig = ServerConfig.fromBuildConfig
    val enabled: Boolean get() = config.isEnabled()

    class Instance(val store: PrefsTokenStore, val client: MinaServerClient, val api: MinaApi)

    @Volatile private var instance: Instance? = null

    fun obtain(context: Context): Instance {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val store = PrefsTokenStore(context.applicationContext)
            val provider = SupabaseTokenProvider(config, store)
            val client = MinaServerClient(config, provider)
            val api = MinaApi(client, MinaSseClient(config, provider))
            return Instance(store, client, api).also { instance = it }
        }
    }

    /** True when a refresh token is on file: the user has signed in before. */
    suspend fun signedIn(context: Context): Boolean = obtain(context).store.refreshToken() != null

    /** Save tokens that came back on the OAuth deep link. */
    suspend fun storeSession(context: Context, access: String, refresh: String, expiresInSec: Long) {
        obtain(context).store.updateTokens(
            access, refresh,
            System.currentTimeMillis() / 1000 * 1000 + expiresInSec * 1000,
        )
    }

    suspend fun signOut(context: Context) = obtain(context).store.clear()
}
