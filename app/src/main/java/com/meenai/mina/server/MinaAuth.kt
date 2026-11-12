package com.meenai.mina.server

/** Supplies the access token used on every server request. */
interface MinaTokenProvider {
    /** Current access token, or null when the user is not signed in. */
    suspend fun accessToken(): String?

    /** Force a fresh token; null when it cannot be obtained. */
    suspend fun refresh(): String?
}
