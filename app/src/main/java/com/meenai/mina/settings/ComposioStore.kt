package com.meenai.mina.settings

import android.content.Context
import com.meenai.mina.core.composio.ComposioClient

/**
 * The user's Composio API key (BYOK). Saved in Accounts > Connections; with it Mina offers
 * connector_list / connector_actions / connector_run tools and can work in the connected apps.
 */
object ComposioStore {
    private const val PREFS = "composio"
    private const val K_KEY = "api_key"
    private const val K_USER = "user_id"

    fun key(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveKey(context: Context, key: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(K_KEY, key.trim()).apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /** Stable per-user id for Composio connections. */
    fun userId(context: Context): String {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(K_USER, null) ?: "default".also { p.edit().putString(K_USER, it).apply() }
    }

    /** A client when the key is saved, else null (connector tools stay off). */
    fun client(context: Context): ComposioClient? =
        key(context)?.let { ComposioClient(it) }
}
