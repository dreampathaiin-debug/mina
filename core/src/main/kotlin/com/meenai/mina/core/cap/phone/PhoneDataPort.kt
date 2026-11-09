package com.meenai.mina.core.cap.phone

import kotlinx.serialization.json.JsonObject

/*
 * 权限层:经安卓标准接口直接读写短信、联系人、通话记录和通知,不经过读屏、不动任何界面。
 * 手机 App 一侧用运行时权限做这一层;权限没给时结果里报 permission_needed,由模型告诉用户去哪里给。
 * 返回的 JSON 就是给模型的最终结果:成功带行,失败带 ok=false、error 与说明。
 */

/** 权限层的端口;手机一侧实现。端口没接上时这一组工具不提供给模型。 */
interface PhoneDataPort {
    /** 发一条短信(SmsManager;需要 SEND_SMS 权限)。 */
    suspend fun sendSms(to: String, text: String): JsonObject

    /** 最近的短信,按时间倒序;[query] 非空时按内容或号码过滤。 */
    suspend fun readSms(query: String?, limit: Int): JsonObject

    /** 按名字找联系人;[query] 为空时返回常用的几位。 */
    suspend fun findContacts(query: String?, limit: Int): JsonObject

    /** 最近的通话记录,按时间倒序。 */
    suspend fun readCallLog(limit: Int): JsonObject

    /** 最近的通知(通知监听权);[app] 给了时只看这个包的。 */
    suspend fun recentNotifications(app: String?, limit: Int): JsonObject

    /** 直接回复一条通知里的消息(通知自己带回复动作时才行)。 */
    suspend fun replyNotification(key: String, text: String): JsonObject
}
