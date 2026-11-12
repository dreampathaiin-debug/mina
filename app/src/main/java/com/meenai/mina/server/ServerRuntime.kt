package com.meenai.mina.server

import android.content.Context

/**
 * One place the app reaches the Mina Cloud backend. Enabled only when an
 * HTTPS API base URL is baked in; every call carries the Firebase ID token,
 * which the Firebase SDK keeps fresh automatically.
 */
object ServerRuntime {
    val config: ServerConfig = ServerConfig.fromBuildConfig
    val enabled: Boolean get() = config.isEnabled()

    class Instance(val client: MinaServerClient, val api: MinaApi)

    @Volatile private var instance: Instance? = null

    fun obtain(context: Context): Instance {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            FirebaseAuthFlow.ensureInit(context.applicationContext)
            val provider = FirebaseTokenProvider()
            val client = MinaServerClient(config, provider)
            val api = MinaApi(client, MinaSseClient(config, provider))
            return Instance(client, api).also { instance = it }
        }
    }

    /** True when a Firebase session is live: the user has signed in. */
    suspend fun signedIn(context: Context): Boolean {
        obtain(context)
        return FirebaseAuthFlow.signedIn()
    }

    suspend fun signOut(context: Context) = FirebaseAuthFlow.signOut(context)
}
