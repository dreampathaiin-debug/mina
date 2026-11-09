package com.meenai.mina.cap.phone.shell

import android.Manifest
import android.app.RemoteInput
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import com.meenai.mina.core.cap.phone.PhoneDataPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 权限层的手机一侧实现:短信、联系人、通话记录经系统 ContentProvider / SmsManager 直接读写,
 * 通知经 [MinaNotificationListener] 收着的那份。每项先看运行时权限在不在,不在就报 permission_needed
 * (带权限名),由模型告诉用户去哪里给;端口不动任何界面。
 */
class PhoneDataBridge(private val context: Context) : PhoneDataPort {

    override suspend fun sendSms(to: String, text: String): JsonObject = withContext(Dispatchers.IO) {
        if (!granted(Manifest.permission.SEND_SMS)) return@withContext need(Manifest.permission.SEND_SMS)
        val sm = manager() ?: return@withContext failed("send_failed", "no SMS manager on this device")
        runCatching {
            val parts = sm.divideMessage(text)
            if (parts.size <= 1) sm.sendTextMessage(to, null, text, null, null)
            else sm.sendMultipartTextMessage(to, null, parts, null, null)
            buildJsonObject { put("ok", true); put("sent", true); put("to", to) }
        }.getOrElse { failed("send_failed", it.message ?: "could not send the SMS") }
    }

    override suspend fun readSms(query: String?, limit: Int): JsonObject = withContext(Dispatchers.IO) {
        if (!granted(Manifest.permission.READ_SMS)) return@withContext need(Manifest.permission.READ_SMS)
        val rows = runCatching {
            val sel = query?.let { "(body LIKE ? OR address LIKE ?)" to arrayOf("%$it%", "%$it%") }
            query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.Inbox.ADDRESS, Telephony.Sms.Inbox.BODY, Telephony.Sms.Inbox.DATE, Telephony.Sms.Inbox.READ),
                sel?.first, sel?.second, "date DESC LIMIT $limit",
            ) { c ->
                buildJsonObject {
                    put("from", c.getString(0) ?: "")
                    put("body", c.getString(1) ?: "")
                    put("when", stamp(c.getLong(2)))
                    put("read", c.getInt(3) == 1)
                }
            }
        }.getOrElse { return@withContext failed("read_failed", it.message ?: "could not read SMS") }
        buildJsonObject { put("ok", true); put("count", rows.size); put("messages", JsonArray(rows)) }
    }

    override suspend fun findContacts(query: String?, limit: Int): JsonObject = withContext(Dispatchers.IO) {
        if (!granted(Manifest.permission.READ_CONTACTS)) return@withContext need(Manifest.permission.READ_CONTACTS)
        val rows = runCatching {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val sel = query?.let { "(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?)" to arrayOf("%$it%", "%$it%") }
            query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.STARRED),
                sel?.first, sel?.second,
                "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC",
            ) { c ->
                (c.getString(0) ?: "") to buildJsonObject {
                    put("name", c.getString(0) ?: "")
                    put("number", c.getString(1) ?: "")
                    put("starred", c.getInt(3) == 1)
                }
            }.groupBy { it.first }
                .map { (name, pairs) -> buildJsonObject {
                    put("name", name)
                    put("numbers", JsonArray(pairs.map { it.second["number"]!! }))
                    put("starred", pairs.any { (it.second["starred"] as? Boolean) == true })
                } }
                .take(limit)
        }.getOrElse { return@withContext failed("read_failed", it.message ?: "could not read contacts") }
        buildJsonObject { put("ok", true); put("count", rows.size); put("contacts", JsonArray(rows)) }
    }

    override suspend fun readCallLog(limit: Int): JsonObject = withContext(Dispatchers.IO) {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return@withContext need(Manifest.permission.READ_CALL_LOG)
        val rows = runCatching {
            query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC LIMIT $limit",
            ) { c ->
                buildJsonObject {
                    put("number", c.getString(0) ?: "")
                    put("name", c.getString(1) ?: "")
                    put("kind", kind(c.getInt(2)))
                    put("when", stamp(c.getLong(3)))
                    put("durationSec", c.getLong(4))
                }
            }
        }.getOrElse { return@withContext failed("read_failed", it.message ?: "could not read the call log") }
        buildJsonObject { put("ok", true); put("count", rows.size); put("calls", JsonArray(rows)) }
    }

    override suspend fun recentNotifications(app: String?, limit: Int): JsonObject = withContext(Dispatchers.IO) {
        if (!listenerOn()) return@withContext need("notification_listener")
        val items = NotificationStore.snapshot(app, limit)
        buildJsonObject { put("ok", true); put("count", items.size); put("notifications", JsonArray(items)) }
    }

    override suspend fun replyNotification(key: String, text: String): JsonObject = withContext(Dispatchers.IO) {
        if (!listenerOn()) return@withContext need("notification_listener")
        val act = NotificationStore.replyAction(key)
            ?: return@withContext failed("not_supported", "that notification has no reply action (or it is gone); read the recent notifications again")
        val sent = MinaNotificationListener.reply(context, act, text)
        if (sent) buildJsonObject { put("ok", true); put("replied", true); put("repliedIn", key.split("|").getOrNull(1) ?: key) }
        else failed("send_failed", "the notification's reply action did not accept the reply")
    }

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** 通知监听权的授权不在运行时权限里:看系统是不是把 Mina 列在监听者里。 */
    private fun listenerOn() = runCatching {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    private fun need(p: String) = buildJsonObject {
        put("ok", false); put("error", "permission_needed"); put("permission", p)
    }

    private fun failed(code: String, message: String) = buildJsonObject {
        put("ok", false); put("error", code); put("message", message)
    }

    private fun manager(): SmsManager? = runCatching { context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault() }.getOrNull()

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    private fun kind(type: Int) = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "other"
    }

    private inline fun <T> query(uri: android.net.Uri, projection: Array<String>, selection: String?, args: Array<String>?, sort: String?, read: (android.database.Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        context.contentResolver.query(uri, projection, selection, args, sort)?.use { c ->
            while (c.moveToNext()) out.add(read(c))
        }
        return out
    }
}
