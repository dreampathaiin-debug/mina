package com.meenai.mina.core.agent

import com.meenai.mina.core.net.ProxyFailure
import com.meenai.mina.core.guard.GuardVerdict
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.tool.Outcome

/**
 * 停止规则:任务什么时候、以什么结局结束(放弃、守卫叫停、模型出错、没给出回答、步数用完)。办完不在这里:
 * 不带工具调用的回复就是回答,由循环核对后交出。纯函数;结局里给用户看的说明不发给模型。
 */
internal object StopRules {
    /** 一个动作执行完之后:放弃了就按它结束;否则守卫([stop],屏幕守卫或不动屏幕的工具的守卫)叫停了就停。都没有为 null,接着做。 */
    fun afterAction(outcome: Outcome?, stop: GuardVerdict?): TaskState? = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stop?.let(::guard)
    }

    /** 步数用完后的最后一轮调了工具(只给了 end_not_done):放弃按它结束,否则算步数用完。 */
    fun afterFinalTurn(outcome: Outcome?, maxSteps: Int): TaskState = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stepLimit(maxSteps)
    }

    fun stepLimit(maxSteps: Int) = TaskState.Failed("Reached the $maxSteps-step limit; the task is not finished.", FailKind.StepLimit)

    /** 提醒过一次,模型仍然回了空话(或又被输出上限截断)。 */
    fun noAnswer() = TaskState.Failed("The model stopped without giving an answer.", FailKind.Model)

    /** 退回过一次,模型的工具调用又以文字形式回来了(见 [LeakedToolCall]):不把原样的标记当回答交出。 */
    fun toolCallAsText() = TaskState.Failed("The model's tool call was not recognized by the server and came back as plain text, so it was not executed.", FailKind.Model)

    /** 模型调用失败(重试过仍不行,或重试也没用)。 */
    fun modelFailed(e: Throwable): TaskState.Failed =
        if (e is ModelError) TaskState.Failed(userReason(e), FailKind.Model)
        else TaskState.Failed("Model call error: ${e.message ?: e::class.simpleName}", FailKind.Model)

    fun guard(v: GuardVerdict): TaskState.Failed {
        val why = when (v.code) {
            "repeating_action" -> "the same action was repeated with no effect"
            "no_progress" -> "the screen did not change after repeated attempts"
            "read_loop" -> "the page did not change and repeated reads found nothing"
            "too_many_failures" -> "actions kept failing"
            "sandbox_loop" -> "the same command kept failing unchanged"
            else -> "the operation safety guard was triggered"
        }
        return TaskState.Failed("Stopped safely (${v.code}): $why.", FailKind.Guard)
    }
}

internal fun userReason(e: ModelError): String = when (e) {
    is ModelError.Auth -> "The model service rejected the request: the API key is invalid or lacks permission. Check the API key in Settings and that the account can use this model."
    is ModelError.QuotaExceeded -> "The model account's quota is used up. Check the provider account's balance or plan."
    is ModelError.ModelNotFound -> {
        // 404 不一定是模型名不对(地址不对,或网关、中转服务说的别的情况):有原话时照原话说,不断定模型不存在
        val check = if (e.model.isEmpty()) "Check the model name and Base URL in Settings › Models."
            else "Open Settings › Models and check the model name (use the full model ID, e.g. Gemini model names look like gemini-flash-latest) or switch to the matching preset."
        when {
            e.detail.isNotEmpty() ->
                if (e.model.isEmpty()) "The server replied: ${quoted(e.detail)}. The model name may be wrong, or the service may be temporarily unavailable. $check"
                else "Calling model “${e.model}”: the server replied: ${quoted(e.detail)}. The model name may be wrong, or the service may be temporarily unavailable. $check"
            e.model.isEmpty() -> "Model not found. $check"
            else -> "Model “${e.model}” does not exist at this provider. $check"
        }
    }
    is ModelError.ModelNotAvailable ->
        "Model “${e.model.ifEmpty { "the current model" }}” is not available for this account (not enabled or no permission). Switch models in Settings › Models, or enable it in the provider's console."
    is ModelError.ToolsUnsupported ->
        "Model “${e.model.ifEmpty { "the current model" }}” does not support tool calls, and Mina needs tools to work. Switch to a model that supports tool calls in Settings › Models."
    is ModelError.ContextTooLong -> "The conversation exceeds the model's context length. Switch to a model with a longer context, or break the task into smaller parts."
    is ModelError.ContentRefused -> "The model refused this content. Adjust the task description or switch models."
    is ModelError.RateLimited -> "The model service is rate limiting; it was still too frequent after retries. Try again later, or check the account's rate limits."
    is ModelError.Network -> "Could not connect to the model service, still failing after retries. Check the network and the Base URL."
    is ModelError.Proxy -> when (e.failure) {
        ProxyFailure.Unreachable ->
            "Could not reach the network proxy, or it did not forward this request; still failing after retries. Check that the proxy is on and that the address and port in Settings › Network proxy are correct; clear it if no proxy is needed."
        ProxyFailure.AuthFailed -> "The network proxy rejected the username or password (HTTP 407). Check the proxy's username and password in Settings › Network proxy."
    }
    is ModelError.Server -> "Model service error (HTTP ${e.status}). Try again later."
    is ModelError.StreamCut ->
        "The model's reply was cut off before it finished and the upstream connection closed; retries did not help. Common causes: unstable network, or a relay or gateway with a short idle timeout (likely when the model thinks for a long time). Try again later."
    is ModelError.BadResponse -> "${e.message}. Check that the Base URL matches the API type."
    is ModelError.ChatGptLimitReached ->
        "The ChatGPT quota is used up, try again later. Usage is under ChatGPT Settings › Usage (chatgpt.com/settings/usage); the Plus quota refreshes every 5 hours and is shared with other apps signed in with ChatGPT. You can also switch to another model."
    is ModelError.ChatGptNotEligible ->
        "This account cannot use its ChatGPT quota in other apps (needs Plus or Pro, and the region and account settings must allow it).${if (e.detail.isEmpty()) "" else " The server says: ${quoted(e.detail)}."} You can switch to another model."
    is ModelError.SignInExpired -> "The ${e.account} sign-in has expired. Sign in with ${e.account} again in Settings › Models."
    is ModelError.AccountPlanNotAllowed ->
        "Your ${e.account} plan cannot use this API (HTTP 403).${if (e.detail.isEmpty()) "" else " The server says: ${quoted(e.detail)}."} You can use ${e.account}'s API key instead, or switch models in Settings › Models."
}

/** 引在句中的服务端原话:去掉末尾一个句号(原话自带的),由引它的句子补上自己的「。」;省略号(截短的「…」、「...」)不动。 */
private fun quoted(detail: String): String =
    when {
        detail.endsWith("..") -> detail
        detail.endsWith(".") || detail.endsWith("。") -> detail.dropLast(1)
        else -> detail
    }
