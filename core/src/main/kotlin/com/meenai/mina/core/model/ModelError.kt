package com.meenai.mina.core.model

import com.meenai.mina.core.net.ProxyFailure

sealed class ModelError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Auth : ModelError("The API key is invalid or lacks permission")
    class QuotaExceeded : ModelError("The quota is used up")
    class RateLimited(val retryAfterMs: Long?) : ModelError("Too many requests, try again later")
    class ContextTooLong : ModelError("The conversation exceeds the model's context length")
    /**
     * 服务商那里没有这个模型(或地址不对,请求的接口不存在);[model] 是请求的模型名,不知道时为空。
     * [detail] 是服务端 JSON 报错里的原话(截短),没有为空:网关、中转服务还会用 404 说别的情况(比如它的上游模型这会儿都不可用),
     * 看原话才分得出是哪一种。
     */
    class ModelNotFound(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "Model not found" else "Model not found: “$model”")
    /**
     * 服务商有这个模型,这个账号却用不了(没开通、没权限、没买);[detail] 是服务端的原话(截短),没有为空。
     * 换一个模型或到服务商后台开通才行,重试没用。见 [ModelAccessErrors]。
     */
    class ModelNotAvailable(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "This account cannot use this model (not enabled or no permission)" else "This account cannot use model “$model” (not enabled or no permission)")
    /** 服务端明说这个模型不支持调用工具(带工具的请求被拒)。Mina 干活靠工具,它当不了主模型。 */
    class ToolsUnsupported(val model: String = "") :
        ModelError(if (model.isEmpty()) "This model does not support tool calls" else "Model “$model” does not support tool calls")
    class Network(cause: Throwable? = null) : ModelError("Network connection failed", cause)
    /**
     * 走网络代理时代理那一段出的错(连不上代理,或代理拒了账号密码),不是服务商的问题。见 [com.meenai.mina.core.net.NetProxy]。
     */
    class Proxy(val failure: ProxyFailure, cause: Throwable? = null) : ModelError(
        when (failure) {
            ProxyFailure.Unreachable -> "Could not reach the network proxy"
            ProxyFailure.AuthFailed -> "The network proxy rejected the username or password"
        },
        cause,
    )
    /**
     * 流式回复还没收尾(没有结束标记,也没有结束原因)连接就关了:多半是途中的网关、代理空闲超时或网络切换把连接掐断。
     * 回复不完整、不能用,重发同一个请求通常就好;和接口配置无关。
     */
    class StreamCut : ModelError("The reply was cut off before it finished")
    class Server(val status: Int) : ModelError("Model service error (HTTP $status)")
    class ContentRefused : ModelError("The model refused this content")
    /** ChatGPT 套餐给其他 App 用的额度到了上限:不重试,等额度恢复或换模型。 */
    class ChatGptLimitReached : ModelError("The ChatGPT quota is used up")
    /** 这个 ChatGPT 账号不能在其他 App 里用套餐额度(套餐不符、地区或账号策略不允许):不重试。 */
    class ChatGptNotEligible(val detail: String = "") :
        ModelError(if (detail.isEmpty()) "This account cannot use its ChatGPT quota in other apps" else "This account cannot use its ChatGPT quota in other apps: $detail")
    /** 登录已失效(换新令牌被拒,或用户在账号里断开了这个 App),要重新登录。[account] 是哪一家的登录(界面上的名字)。 */
    class SignInExpired(val account: String = "ChatGPT") : ModelError("Sign-in has expired, please sign in again")
    /**
     * 用账号登录的(如 xAI)接口回 HTTP 403:登录是成功的,这个订阅档位却不能调这个接口。不重试;
     * 改用这家的 API Key 才行。[account] 是哪一家,[detail] 是服务端的原话(截短),没有为空。
     */
    class AccountPlanNotAllowed(val account: String, val detail: String = "") :
        ModelError("Your ${account} plan cannot use this API")
    class BadResponse(detail: String = "", cause: Throwable? = null) :
        ModelError(if (detail.isEmpty()) "The model returned unparseable content" else "The model returned unparseable content: $detail", cause)
}
