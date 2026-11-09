package com.meenai.mina.cap.phone.shell

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 通知监听:权限层里「读最近的通知」「直接回复通知里的消息」都靠它。
 * 系统把通知送进来,这里把有内容的收进 [NotificationStore](有界、去噪:进行中不打扰的那种不收),
 * 回复动作的 PendingIntent 也留在这里,回复时把文字塞进通知自带的回复框直接发出去,不开任何界面。
 * 授权在系统的「通知使用权」里,不是运行时权限。
 */

/** 通知自带的回复动作:哪个 action、要填哪些 RemoteInput 键。 */
class NotificationReplyAction(val action: Notification.Action)

/** 最近的通知(内存,有界,最新在前);回复动作按通知的 key 单独收着,通知撤了就丢。 */
object NotificationStore {
    private const val KEEP = 60
    private val lock = Any()
    private val recent = ArrayDeque<JsonObject>(KEEP)
    private val replies = HashMap<String, NotificationReplyAction>()

    fun add(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.isOngoing) return
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val canReply = n.actions?.any { !it.getRemoteInputs().isNullOrEmpty() } == true
        val item = buildJsonObject {
            put("key", sbn.key)
            put("app", sbn.packageName)
            put("title", title)
            put("text", text)
            put("when", sbn.postTime)
            put("canReply", canReply)
        }
        synchronized(lock) {
            recent.removeAll { (it["key"] as? JsonPrimitive)?.contentOrNull == sbn.key }
            recent.addFirst(item)
            while (recent.size > KEEP) recent.removeLast()
            val act = n.actions?.firstOrNull { !it.getRemoteInputs().isNullOrEmpty() }
            if (act != null) replies[sbn.key] = NotificationReplyAction(act) else replies.remove(sbn.key)
        }
    }

    fun remove(key: String) {
        synchronized(lock) {
            replies.remove(key)
            recent.removeAll { (it["key"] as? JsonPrimitive)?.contentOrNull == key }
        }
    }

    fun snapshot(app: String?, limit: Int): List<JsonObject> = synchronized(lock) {
        recent.filter { app == null || (it["app"] as? String) == app }.take(limit)
    }

    fun replyAction(key: String): NotificationReplyAction? = synchronized(lock) { replies[key] }
}

class MinaNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { NotificationStore.add(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching { NotificationStore.remove(sbn.key) }
    }

    companion object {
        /** 用通知自带的回复动作把 [text] 发出去(不开界面);发出去 true。 */
        fun reply(context: Context, act: NotificationReplyAction, text: String): Boolean = runCatching {
            val inputs = act.action.remoteInputs ?: return false
            val intent = Intent()
            val results = Bundle()
            results.putCharSequence(inputs.first().resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            act.action.actionIntent?.send(context, 0, intent) != null
        }.getOrDefault(false)
    }
}
