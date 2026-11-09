package com.meenai.mina.core.chatgpt.check

import com.meenai.mina.core.chatgpt.ChatGptAuth
import com.meenai.mina.core.chatgpt.ChatGptSession
import com.meenai.mina.core.chatgpt.ChatGptTokens
import com.meenai.mina.core.chatgpt.SignInException
import com.meenai.mina.core.chatgpt.SignInFailure
import com.meenai.mina.core.chatgpt.signInWithChatGpt
import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelCatalog
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelListResult
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.ResponsesModel
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.model.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 桌面上把「用 ChatGPT 登录」整条路走一遍:用 App 同一套登录、列模型和 Responses 代码,对着 OpenAI 的真实服务,
 * 好在上手机之前先确认服务端这一侧没问题。用法见 docs/dev/chatgpt-signin-check.md。
 *
 * 本机标识和注册到的 client_id 存在 ~/.mina-dev/chatgpt-check/(目录 0700),多次运行沿用,和 App 一样。
 * 令牌、授权码、ID 令牌一律不打印;默认只在内存里,`--keep` 才写到同一目录的 session.json(0600)。
 *
 * 参数:`--timeout=<秒>` 等回调多久(默认 300);`--model=<slug>` 指定模型;`--keep` 保存登录。
 */
fun main(args: Array<String>) {
    val opts = args.associate { a -> a.removePrefix("--").let { it.substringBefore('=') to it.substringAfter('=', "") } }
    val timeoutSec = opts["timeout"]?.toLongOrNull()?.takeIf { it > 0 } ?: 300L
    val ok = runBlocking { Check(timeoutSec, opts["model"]?.takeIf { it.isNotBlank() }, "keep" in opts).run() }
    exitProcess(if (ok) 0 else 1)
}

private class Check(private val timeoutSec: Long, private val modelArg: String?, private val keep: Boolean) {
    private val dir = File(System.getProperty("user.home"), ".mina-dev/chatgpt-check")
    private val hostFile = File(dir, "host.json")
    private val sessionFile = File(dir, "session.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
    private val auth = ChatGptAuth(http)
    private val results = mutableListOf<Pair<String, Boolean>>()

    suspend fun run(): Boolean {
        println("== Mina: ChatGPT sign-in · desktop check ==")
        println("Working dir: ${dir.absolutePath} (tokens are not printed; ${if (keep) "--keep: keep the login at the end" else "by default the token is not saved"})")
        prepareDir()
        val (hostId, savedClient) = registration()
        println("Client id: ${if (savedClient == null) "first run, a new client will be registered" else "reusing the registered client"}")

        var session = signIn(hostId, savedClient) ?: return summary()
        val tokens = ChatGptTokens(auth, { session }, { session = it })

        val model = listModels(session.accessToken)
        if (model != null) {
            val caps = Capabilities(
                toolCalls = true, vision = model.vision ?: false,
                contextTokens = model.contextTokens ?: 128_000, reasoning = model.reasoning ?: false,
            )
            val responses = ResponsesModel(ChatGptAuth.API_BASE, tokens, model.id, caps, http, chatGptPlan = true)
            simpleCall(responses)
            toolRoundTrip(responses)
        }
        refresh(tokens) { session }
        if (keep) save(session)
        return summary()
    }

    // ---------- 1. 本机标识 ----------

    private fun prepareDir() {
        dir.mkdirs()
        runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
    }

    private fun registration(): Pair<String, String?> {
        val saved = runCatching { json.parseToJsonElement(hostFile.readText()).jsonObject }.getOrNull()
        val host = saved?.get("hostId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val client = saved?.get("clientId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (host != null) return host to client
        val fresh = ChatGptAuth.newHostId()
        writePrivate(hostFile, buildJsonObject { put("hostId", fresh) }.toString())
        return fresh to null
    }

    private fun rememberClient(hostId: String, clientId: String) {
        writePrivate(hostFile, buildJsonObject { put("hostId", hostId); put("clientId", clientId) }.toString())
        step("Register client (client_id saved to ${hostFile.name})", true)
    }

    // ---------- 2–3. 登录 ----------

    private suspend fun signIn(hostId: String, clientId: String?): ChatGptSession? {
        // 只拿上次的邮箱当登录提示;不带 ID 令牌,打印出来的授权地址里就不会有令牌
        val previous = if (sessionFile.isFile) ChatGptSession.fromJson(sessionFile.readText())?.signedOut() else null
        return try {
            val s = signInWithChatGpt(
                auth, hostId, clientId, previous,
                openBrowser = { url -> openBrowser(url) },
                onRegistered = { rememberClient(hostId, it) },
                page = ::callbackPage,
                timeoutMs = timeoutSec * 1000,
            )
            step("Callback received (state verified)", true)
            step("Exchange the code for tokens", true)
            step("Verify the ID token (signature, issuer, audience, expiry, nonce)", true)
            step("Granted ${ChatGptAuth.PLAN_SCOPE}", true)
            println("    Granted scopes: ${s.scopes.joinToString(" ")}")
            println("    Account: ${s.email.ifEmpty { "(no email in the ID token)" }}")
            println("    Access token valid for about ${(s.expiresAtMs - System.currentTimeMillis()) / 1000} s; refresh token: ${if (s.refreshToken.isNotEmpty()) "yes" else "none"}")
            s
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignInException) {
            step(signInStep(e.reason), false, explain(e))
            null
        } catch (e: Exception) {
            step("Sign in", false, "${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun openBrowser(url: String) {
        println("Authorization URL (no tokens):")
        println("    $url")
        val opened = runCatching {
            System.getProperty("os.name").lowercase().contains("mac") &&
                ProcessBuilder("open", url).inheritIO().start().waitFor(10, TimeUnit.SECONDS)
        }.getOrDefault(false)
        step(if (opened) "Opened the authorization page in the default browser" else "could not open a browser automatically; copy the URL above and open it", opened, null, record = false)
        println("Waiting for the callback... (up to $timeoutSec s; Ctrl+C to give up)")
    }

    private fun callbackPage(failure: SignInException?): String {
        val text = if (failure != null) "Sign-in did not succeed (${failure.reason}); check the terminal for the reason." else "Sign-in complete; you can close this page and check the terminal for the result."
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Mina</title></head>" +
            "<body style=\"font-family:system-ui;margin:3em\"><p>$text</p></body></html>"
    }

    private fun signInStep(r: SignInFailure): String = when (r) {
        SignInFailure.Denied, SignInFailure.StateMismatch, SignInFailure.ClientMismatch, SignInFailure.NoClientId -> "Verify the callback"
        SignInFailure.TimedOut -> "Wait for the callback"
        SignInFailure.Exchange, SignInFailure.CodeExpired, SignInFailure.Region, SignInFailure.Blocked -> "Exchange the code for tokens"
        SignInFailure.IdToken -> "Verify the ID token"
        SignInFailure.NoPlanScope -> "Grant ${ChatGptAuth.PLAN_SCOPE}"
        SignInFailure.Network -> "Connect to the OpenAI sign-in service"
    }

    // ---------- 4. 列模型 ----------

    private suspend fun listModels(accessToken: String): ListedModel? {
        val listed = when (val r = ModelCatalog(http).openAiCompat(ChatGptAuth.API_BASE, accessToken)) {
            is ModelListResult.Ok -> r.models
            is ModelListResult.Failed -> {
                step("List models (GET /v1/models)", false, r.message)
                return null
            }
        }
        step("List models (GET /v1/models): ${listed.size}", listed.isNotEmpty(), if (listed.isEmpty()) "the list is empty" else null)
        listed.forEach { m ->
            val flags = listOfNotNull(
                m.contextTokens?.let { "context $it" }, m.vision?.let { if (it) "vision" else "no vision" },
                m.reasoning?.takeIf { it }?.let { "reasoning" }, m.toolCalls?.let { if (it) "tools" else "no tools" },
            )
            println("    ${m.id}${m.displayName.takeIf { it.isNotEmpty() && it != m.id }?.let { "  「$it」" }.orEmpty()}" +
                if (flags.isEmpty()) "" else "  (${flags.joinToString(",")})")
        }
        val chosen = if (modelArg != null) {
            listed.firstOrNull { it.id == modelArg } ?: ListedModel(modelArg).also { println("    Note: --model=$modelArg is not in the list; trying anyway") }
        } else {
            // 优先挑推理模型:工具调用那一步要顺带验加密思考的回放
            listed.firstOrNull { it.usable && it.reasoning == true && it.toolCalls != false } ?: listed.firstOrNull { it.usable }
        }
        chosen?.let { println("    Using model: ${it.id}") }
        return chosen
    }

    // ---------- 5. 一次简单调用 ----------

    private suspend fun simpleCall(model: ResponsesModel) {
        val name = "Responses call"
        try {
            val reply = model.complete("", listOf(Message(Role.User, listOf(Part.Text("Reply with the single word: pong")), Author.User)), emptyList())
            val text = reply.text()
            step(name, text.isNotBlank(), if (text.isBlank()) "the answer is empty" else null)
            println("    Answer: ${text.trim().take(200)}")
            println("    ${usage(reply.usage)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step(name, false, explain(e))
        }
    }

    // ---------- 6. 工具调用往返 ----------

    private suspend fun toolRoundTrip(model: ResponsesModel) {
        val tool = ToolSpec(
            "get_time", "Returns the current local time as an ISO-8601 string.",
            """{"type":"object","properties":{},"additionalProperties":false}""",
        )
        val system = "You can call tools. When asked for the time, call get_time, then answer with the time it returned."
        val history = mutableListOf(Message(Role.User, listOf(Part.Text("What time is it now? Use the get_time tool.")), Author.User))
        try {
            val first = model.complete(system, history, listOf(tool))
            val call = first.parts.filterIsInstance<Part.ToolCall>().firstOrNull()
            if (call == null) {
                step("Tool call: the model calls get_time", false, "the model did not call a tool and answered directly: ${first.text().take(120)}")
                return
            }
            step("Tool call: the model called ${call.name} (args ${call.argsJson.take(80)})", call.name == tool.name)
            println("    Encrypted reasoning: ${if (call.opaque != null) "present, replayed verbatim next round (store:false)" else "none (not given, or not a reasoning model)"}")
            println("    ${usage(first.usage)}")
            val now = OffsetDateTime.now().withNano(0).toString()
            history += Message(Role.Assistant, first.parts, Author.Mina)
            history += Message(Role.Tool, listOf(Part.ToolResult(call.id, """{"time":"$now"}""", isError = false)), Author.Mina)
            val second = model.complete(system, history, listOf(tool))
            val text = second.text()
            val more = second.parts.any { it is Part.ToolCall }
            step("Tool call round-trip: answered after the function_call_output", text.isNotBlank() && !more,
                when { more -> "the model called another tool"; text.isBlank() -> "the answer is empty"; else -> null })
            println("    Answer: ${text.trim().take(300)}")
            println("    ${usage(second.usage)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step("Tool-call round trip", false, explain(e))
        }
    }

    // ---------- 7. 换新令牌 ----------

    private suspend fun refresh(tokens: ChatGptTokens, current: () -> ChatGptSession) {
        val before = current()
        try {
            tokens.get(fresh = true)
            val after = current()
            step("Refresh the tokens", true)
            println("    Access token ${if (after.accessToken != before.accessToken) "updated" else "unchanged"};" +
                "refresh token ${if (after.refreshToken != before.refreshToken) "rotated" else "unchanged"};" +
                "new validity about ${(after.expiresAtMs - System.currentTimeMillis()) / 1000} s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step("Refresh the tokens", false, explain(e))
        }
    }

    private fun save(session: ChatGptSession) {
        writePrivate(sessionFile, session.toJson())
        println("Login saved to ${sessionFile.absolutePath} (0600)")
    }

    // ---------- 输出 ----------

    private fun step(name: String, ok: Boolean, why: String? = null, record: Boolean = true) {
        println("${if (ok) "✓" else "✗"} $name")
        if (!ok && why != null) println("    Reason: $why")
        if (record) results += name to ok
    }

    private fun summary(): Boolean {
        val failed = results.filterNot { it.second }
        println()
        println("== Summary: ${results.size - failed.size}/${results.size} steps passed ==")
        failed.forEach { println("✗ ${it.first}") }
        return failed.isEmpty() && results.isNotEmpty()
    }

    private fun ModelReply.text() = parts.filterIsInstance<Part.Text>().joinToString("") { it.text }

    private fun usage(u: Usage?) = u?.let { "Usage: input ${it.inputTokens} (cached ${it.cachedInputTokens}), output ${it.outputTokens}" } ?: "Usage: not provided by the server"

    private fun writePrivate(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }
}

/** 把已知的错误说成人话。 */
private fun explain(e: Throwable): String = when (e) {
    is SignInException -> when (e.reason) {
        SignInFailure.Denied -> "The authorization page returned an error (${e.message?.substringAfter(": ", "")}):" +
            "access_denied means cancel was tapped on the authorization page; when the page says the required entitlement is unavailable, the account is not Plus or Pro and cannot share its plan quota with other apps"
        SignInFailure.StateMismatch -> "The state in the callback does not match this run (it is not this sign-in's callback)"
        SignInFailure.ClientMismatch -> "The callback's client_id differs from the saved one: delete clientId from ~/.mina-dev/chatgpt-check/host.json and retry"
        SignInFailure.NoClientId -> "First registration, yet the callback gave no client_id"
        SignInFailure.TimedOut -> "等回调超时:浏览器里没有完成登录,或回调没回到 127.0.0.1"
        SignInFailure.Exchange -> "换令牌被拒(${e.message?.substringAfter(": ", "")}):redirect_uri 或 PKCE 不符,或别的原因"
        SignInFailure.CodeExpired -> "换令牌被拒(${e.message?.substringAfter(": ", "")}):授权码过期、已用过或和这次对不上"
        SignInFailure.Region -> "换令牌被拒(${e.message?.substringAfter(": ", "")}):当前网络所在的地区不被支持"
        SignInFailure.Blocked -> "换令牌被拒(${e.message?.substringAfter(": ", "")}):返回的是网页不是 JSON,请求被防护拦下了"
        SignInFailure.IdToken -> "ID 令牌没通过校验(${e.message?.substringAfter(": ", "")})"
        SignInFailure.NoPlanScope -> "没授予 ${ChatGptAuth.PLAN_SCOPE}:这个账号的套餐不能在其他 App 里用额度(需要 Plus 或 Pro),或授权时没勾选"
        SignInFailure.Network -> "连不上 OpenAI 登录服务(${e.message?.substringAfter(": ", "")})"
    }
    is ModelError.ChatGptNotEligible -> "subscription_sharing_user_not_eligible:这个账号不能在其他 App 里用 ChatGPT 额度(套餐、地区或账号策略不允许)" +
        e.detail.takeIf { it.isNotEmpty() }?.let { ";服务端说:$it" }.orEmpty()
    is ModelError.ChatGptLimitReached -> "usage_limit_exceeded: the ChatGPT plan's quota for other apps is at its limit; wait for the reset and retry (usage at ${ChatGptAuth.USAGE_URL})"
    is ModelError.SignInExpired -> "刷新令牌已不能用(被注销、过期或被重复使用),要重新登录"
    is ModelError.Auth -> "令牌被拒或客户端配置不对(401/403、client_id 不认)"
    is ModelError.ModelNotFound -> "model_not_found:这个账号用不了这个模型,换一个(--model=…)"
    is ModelError.ContextTooLong -> "context_length_exceeded"
    is ModelError.Network -> "网络连接失败:${e.cause?.message.orEmpty()}"
    is ModelError.Server -> "服务端出错(HTTP ${e.status}),稍后再试"
    is ModelError.BadResponse -> if (e.message.orEmpty().contains("服务端不支持请求里的")) {
        "unsupported_capability:${e.message?.substringAfter("服务端不支持请求里的 ")?.let { "请求里的 `$it` 这一项不被支持" }}"
    } else e.message.orEmpty()
    else -> "${e::class.simpleName}: ${e.message}"
}
