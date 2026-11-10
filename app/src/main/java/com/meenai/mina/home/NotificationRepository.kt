package com.meenai.mina.home

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.meenai.mina.R
import com.meenai.mina.cap.phone.shell.NotificationStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Home 顶部的重要通知:来自 [NotificationStore](通知使用权收进来的最近通知,内存),
 * 左滑删掉的那几条记进 DataStore,不再回来;Undo 把它们放回来。
 */
data class NotifUi(
    val key: String,
    val app: String?,
    val title: String,
    val text: String,
    /** 动词小胶囊的文字资源。 */
    val verb: Int,
)

private val Context.notifDismissedStore by preferencesDataStore(name = "notif_dismissed")
private val DISMISSED_KEY = stringSetPreferencesKey("dismissed_notifs")

class NotificationRepository(private val context: Context) {

    /** 内存里也留一份,删了立刻消失、Undo 立刻回来,不用等 DataStore。 */
    private val dismissed = LinkedHashSet<String>()
    private var loaded = false

    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        dismissed.addAll(context.notifDismissedStore.data.first()[DISMISSED_KEY].orEmpty())
    }

    /** 当前要展示的通知(最新在前),已删的滤掉。 */
    suspend fun active(limit: Int = 6): List<NotifUi> {
        ensureLoaded()
        return NotificationStore.snapshot(null, 20)
            .filter { o -> (jsonKey(o) !in dismissed) }
            .take(limit)
            .map { o ->
                val title = jsonText(o, "title")
                NotifUi(
                    key = jsonKey(o),
                    app = jsonText(o, "app").takeIf { it.isNotBlank() },
                    title = title.ifBlank { jsonText(o, "text") },
                    text = jsonText(o, "text"),
                    verb = R.string.home_note_verb,
                )
            }
            .filter { it.title.isNotBlank() }
    }

    suspend fun dismiss(key: String) {
        ensureLoaded()
        dismissed.add(key)
        persist()
    }

    suspend fun undismiss(key: String) {
        ensureLoaded()
        dismissed.remove(key)
        persist()
    }

    private suspend fun persist() {
        context.notifDismissedStore.edit { it[DISMISSED_KEY] = dismissed.toSet() }
    }

    private fun jsonKey(o: JsonObject): String = (o["key"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun jsonText(o: JsonObject, k: String): String = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
