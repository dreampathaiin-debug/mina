package com.meenai.mina.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class MinaSseEvent(val id: String?, val event: String, val data: String)

/**
 * Server-Sent Events with automatic recovery: exponential backoff reconnect
 * (1s..30s), Last-Event-ID resume so no event is lost across a drop, and a
 * read timeout that reconnects a silently stalled stream. Events are emitted
 * the moment they arrive; nothing is buffered until stream close.
 */
class MinaSseClient(private val config: ServerConfig, private val tokens: MinaTokenProvider) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS) // must exceed the server heartbeat gap
        .build()

    /** Long-lived SSE stream; cancels when the collecting coroutine is cancelled. */
    fun stream(path: String): Flow<MinaSseEvent> = flow {
        var lastEventId: String? = null
        var backoffMs = 1000L
        while (true) {
            try {
                val builder = Request.Builder()
                    .url(config.apiBaseUrl + path)
                    .header("Accept", "text/event-stream")
                if (lastEventId != null) builder.header("Last-Event-ID", lastEventId)
                runCatching { tokens.accessToken() }.getOrNull()?.let {
                    builder.header("Authorization", "Bearer $it")
                }
                http.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(30_000)
                        return@use
                    }
                    backoffMs = 1000L
                    val reader = BufferedReader(
                        InputStreamReader(response.body!!.byteStream())
                    )
                    var id: String? = null
                    var event = "message"
                    val data = StringBuilder()
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) {
                                    if (id != null) lastEventId = id
                                    emit(MinaSseEvent(id, event, data.toString().trim()))
                                    id = null; event = "message"; data.clear()
                                }
                            }
                            line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                            line.startsWith(":") -> Unit // comment / heartbeat keepalive
                        }
                    }
                }
                delay(1000) // clean stream end: reconnect shortly
            } catch (e: IOException) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000)
            }
        }
    }.flowOn(Dispatchers.IO)
}
