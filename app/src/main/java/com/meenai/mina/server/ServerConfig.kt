package com.meenai.mina.server

import com.meenai.mina.BuildConfig

/** Cloud backend configuration. Empty URLs keep every existing on-device flow unchanged. */
data class ServerConfig(
    val apiBaseUrl: String,
    val supabaseUrl: String,
    val supabaseAnonKey: String,
) {
    /** Server mode is only active when a production HTTPS base URL is configured. */
    fun isEnabled(): Boolean = apiBaseUrl.startsWith("https://")

    companion object {
        val fromBuildConfig: ServerConfig = ServerConfig(
            apiBaseUrl = BuildConfig.MINA_API_BASE_URL.trimEnd('/'),
            supabaseUrl = BuildConfig.MINA_SUPABASE_URL.trimEnd('/'),
            supabaseAnonKey = BuildConfig.MINA_SUPABASE_ANON_KEY,
        )
    }
}
