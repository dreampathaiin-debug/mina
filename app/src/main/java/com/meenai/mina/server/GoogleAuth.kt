package com.meenai.mina.server

import android.net.Uri

/**
 * Google sign-in through Supabase OAuth: open the authorize URL in the user's
 * browser, and the tokens come back on the mina://auth-callback deep link
 * (implicit flow, tokens in the URI fragment).
 */
object GoogleAuth {
    const val SCHEME = "mina"
    const val HOST = "auth-callback"

    fun authorizeUrl(config: ServerConfig): String =
        "${config.supabaseUrl}/auth/v1/authorize?provider=google&response_type=token&redirect_to=mina://auth-callback"

    /** Tokens from a mina://auth-callback URI, or null when it is not one. */
    fun parseCallback(uri: Uri): ParsedSession? {
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val params = (uri.fragment ?: uri.query ?: "").split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to Uri.decode(it.substring(i + 1))
        }.toMap()
        val access = params["access_token"] ?: return null
        val refresh = params["refresh_token"] ?: return null
        val expiresIn = params["expires_in"]?.toLongOrNull() ?: 3600L
        return ParsedSession(access, refresh, expiresIn)
    }

    /** Error text when the OAuth round-trip came back with an error (query params). */
    fun errorOf(uri: Uri): String? {
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        if (uri.getQueryParameter("error") == null) return null
        return uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
    }

    data class ParsedSession(val accessToken: String, val refreshToken: String, val expiresInSec: Long)
}
