package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Entry
import com.meenai.mina.core.tool.EvidenceLog
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.int
import com.meenai.mina.core.tool.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * 权限层的一组工具:经安卓标准接口直接读写短信、联系人、通话记录、通知,不开任何界面。
 * 端口没接上(手机一侧没接)时不提供给模型;权限没给时结果里报 permission_needed,
 * 模型按 capabilities/phone.md 的说明告诉用户去哪里给,再试一次。
 */

/** 端口的结果照原样交给模型;权限没给时补一句去哪里给的说明。 */
private fun dataResult(res: JsonObject): ToolExecution {
    if ((res["error"] as? JsonPrimitive)?.contentOrNull == "permission_needed") {
        val hint = Feedback["phone_data.permission_needed"]
        return ToolExecution(JsonObject(res + mapOf("hint" to JsonPrimitive(hint))))
    }
    return ToolExecution(res)
}

/** 端口没接上(手机一侧没接这层)。 */
private fun unavailable() = ToolExecution(
    JsonObject(
        mapOf(
            "ok" to JsonPrimitive(false),
            "error" to JsonPrimitive("not_supported"),
            "message" to JsonPrimitive(Feedback["phone_data.unavailable"]),
        )
    )
)

/** 发短信:不用点屏幕,SmsManager 直接发。 */
internal object SmsSend : PhoneTool {
    override val verb = Verb.SmsSend
    override val label = "Send SMS"
    override val risk = Risk.Write
    override val entry = Entry.Alone
    override val logged = true
    override val offScreen = true
    override val def = ToolDef("phone_sms_send", Prompts.tool("phone_sms_send"), mapOf(
        "to" to str("Recipient phone number, international format like +919876543210."),
        "text" to str("Message text to send."),
    ), listOf("to", "text"))

    override fun offered(ctx: ToolContext) = ctx.phone.data != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val to = a.str("to", max = 20)!!.trim()
        val text = a.str("text", max = 1000)!!
        if (!Regex("^[+0-9 ()-]{5,20}$").matches(to)) throw ArgError("to", "must be a phone number in international format, like +919876543210")
        val port = ctx.phone.data ?: return unavailable()
        return dataResult(port.sendSms(to, text))
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["to"] as? JsonPrimitive)?.contentOrNull

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> =
        listOf(Observation(ObsKind.Fact, "$name sent an SMS to ${(o["to"] as? JsonPrimitive)?.contentOrNull ?: "the number"}", source = PHONE))
        .takeIf { r.content["ok"] == JsonPrimitive(true) } ?: emptyList()
}

/** 读最近的短信:按时间倒序,可按内容或号码过滤。 */
internal object SmsRead : PhoneTool {
    override val verb = Verb.SmsRead
    override val label = "Read SMS"
    override val risk = Risk.Read
    override val entry = Entry.Alone
    override val offScreen = true
    override val def = ToolDef("phone_sms_read", Prompts.tool("phone_sms_read"), mapOf(
        "query" to str("Optional: only messages whose text or sender contains these words."),
        "limit" to int("Optional: how many to read, 1-50. Default 20.", 1, 50),
    ))

    override fun offered(ctx: ToolContext) = ctx.phone.data != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val query = a.str("query", min = 0, max = 100)?.trim()?.takeIf { it.isNotEmpty() }
        val limit = a.o["limit"]?.let { a.int("limit", 1, 50) } ?: 20
        val port = ctx.phone.data ?: return unavailable()
        return dataResult(port.readSms(query, limit))
    }
}

/** 找联系人:通讯录里的名字、号码、邮箱。 */
internal object ContactsFind : PhoneTool {
    override val verb = Verb.ContactsFind
    override val label = "Find contacts"
    override val risk = Risk.Read
    override val entry = Entry.Alone
    override val offScreen = true
    override val def = ToolDef("phone_contacts_find", Prompts.tool("phone_contacts_find"), mapOf(
        "query" to str("Optional: part of the name (or number) to look for."),
        "limit" to int("Optional: how many to return, 1-50. Default 10.", 1, 50),
    ))

    override fun offered(ctx: ToolContext) = ctx.phone.data != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val query = a.str("query", min = 0, max = 100)?.trim()?.takeIf { it.isNotEmpty() }
        val limit = a.o["limit"]?.let { a.int("limit", 1, 50) } ?: 10
        val port = ctx.phone.data ?: return unavailable()
        return dataResult(port.findContacts(query, limit))
    }
}

/** 最近的通话记录:谁打来、打给谁、什么类型、多久。 */
internal object CallLogRead : PhoneTool {
    override val verb = Verb.CallLogRead
    override val label = "Read call log"
    override val risk = Risk.Read
    override val entry = Entry.Alone
    override val offScreen = true
    override val def = ToolDef("phone_call_log_recent", Prompts.tool("phone_call_log_recent"), mapOf(
        "limit" to int("Optional: how many recent calls to read, 1-50. Default 20.", 1, 50),
    ))

    override fun offered(ctx: ToolContext) = ctx.phone.data != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val limit = a.o["limit"]?.let { a.int("limit", 1, 50) } ?: 20
        val port = ctx.phone.data ?: return unavailable()
        return dataResult(port.readCallLog(limit))
    }
}

/** 最近的通知:哪个 App、标题、内容;带回复动作的通知 canReply 为 true。 */
internal object NotificationsRecent : PhoneTool {
    override val verb = Verb.NotificationsRead
    override val label = "Read notifications"
    override val risk = Risk.Read
    override val entry = Entry.Alone
    override val offScreen = true
    override val def = ToolDef("phone_notifications_recent", Prompts.tool("phone_notifications_recent"), mapOf(
        "app" to str("Optional: only notifications from this Android package name, e.g. com.whatsapp."),
        "limit" to int("Optional: how many to read, 1-50. Default 20.", 1, 50),
    ))

    override fun offered(ctx: ToolContext) = ctx.phone.data != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val app = a.str("app", min = 0, max = 100)?.trim()?.takeIf { it.isNotEmpty() }
        val limit = a.o["limit"]?.let { a.int("limit", 1, 50) } ?: 20
        val port = ctx.phone.data ?: return unavailable()
        return dataResult(port.recentNotifications(app, limit))
    }
}

/** 直接回复通知里的消息(WhatsApp、短信等自带回复按钮的通知),不用点屏幕。 */
internal object NotificationReply : PhoneTool {
    override val verb = Verb.NotificationReply
    override val label = "Reply to notification"
    override val risk = Risk.Write
    override val entry = Entry.Alone
    override val logged = true
    override val offScreen = true
    override val def = ToolDef("phone_notification_reply", Prompts.tool("phone_notification_reply"), mapOf(
        "key" to str("The notification key from phone_notifications_recent."),
        "text" to str("Reply text to send."),
    ), listOf("key", "text"))

    override fun offered(ctx: ToolContext) = ctx.phone.data != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val key = a.str("key", max = 300)!!
        val text = a.str("text", max = 1000)!!
        val port = ctx.phone.data ?: return unavailable()
        return dataResult(port.replyNotification(key, text))
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["key"] as? JsonPrimitive)?.contentOrNull

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val app = (r.content["repliedIn"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        return listOf(Observation(ObsKind.Fact, "$name replied to a notification in $app", source = PHONE))
    }
}
