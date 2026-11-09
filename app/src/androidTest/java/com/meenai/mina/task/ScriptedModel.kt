package com.meenai.mina.task

import com.meenai.mina.DeviceTaskState
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.verify.Verifier
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**Script model for real machine testing:Complete first every time(How many times and history of receipt),Wait for the test to be fed to the next tool call. */
class ScriptedModel : ChatModel {
    class Call(val number: Int, val messages: List<Message>)

    override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)
    private val calls = Channel<Call>(Channel.UNLIMITED)
    private val replies = Channel<List<Part>>(Channel.UNLIMITED)
    private val n = AtomicInteger()
    /**The number of pre-completion verification requests received. */
    val verifierRequests = AtomicInteger()
    /**The number of requests to write an account after stopping;return [stopSummary](Default blank,Explain and spell directly according to the record),It does not account for scripts and does not count. */
    val summaryRequests = AtomicInteger()
    @Volatile var stopSummary: String = ""
    /**Verified fixed answer;Passed by default. */
    @Volatile var verdict: String = PASS
    /**
     *Tasks initiated from the interface when proposing the first tool call,The model will be asked another time "Is the user discussing, reading or operating?";This is the model that is asked when there is no lightweight model.
     *set it,This question is always answered like this(ACT / READ / DISCUSS),It does not account for scripts and does not count.;When it is null, this question needs to be tested like other calls.
     */
    @Volatile var intent: String? = null

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        //Verification before submission of answers:Fixed answer,It does not account for scripts and does not count.
        if (system == Verifier.PROMPT) {
            verifierRequests.incrementAndGet()
            return ModelReply(listOf(Part.Text(verdict)), StopReason.EndTurn, null)
        }
        if (system == SUMMARY_PROMPT) {
            summaryRequests.incrementAndGet()
            return ModelReply(listOf(Part.Text(stopSummary)), StopReason.EndTurn, null)
        }
        //Give new conversation a title:Fixed answer,It does not account for scripts and does not count.
        if (system == TITLE_PROMPT) return ModelReply(listOf(Part.Text("test conversation")), StopReason.EndTurn, null)
        intent?.let { if (system == INTENT_PROMPT) return ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) }
        calls.send(Call(n.incrementAndGet(), messages))
        val parts = replies.receive()
        return ModelReply(parts, if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.EndTurn, null)
    }

    /** [say]It’s what the model said incidentally at this step.(Displayed on the interface below the steps). */
    fun feed(name: String, args: String = "{}", say: String? = null) =
        replies.trySend(listOfNotNull(say?.let { Part.Text(it) }, Part.ToolCall("c${n.get()}", name, args)))

    /**Reply without call:Final answer to the user,The mission ends here(If the verification fails, it will be returned once.). */
    fun answer(text: String) = replies.trySend(listOf(Part.Text(text)))

    companion object {
        private val INTENT_PROMPT by lazy { Prompts.role("intent_gate") }
        private val SUMMARY_PROMPT by lazy { Prompts.role("stop_summary") }
        private val TITLE_PROMPT by lazy { Prompts.role("conversation_title") }
        const val PASS = """{"checks": [{"criterion": "The desired result is displayed on the screen", "status": "met", "quote": null}], "verdict": "pass", "reason": "script"}"""
        const val CANNOT_VERIFY = """{"checks": [{"criterion": "The desired result is displayed on the screen", "status": "unknown", "quote": null}], "verdict": "cannot_verify", "reason": "script:Can't see results"}"""
    }

    fun awaitCall(): Call = runBlocking {
        withTimeoutOrNull(30_000) { calls.receive() } ?: error("model not called; task state ${DeviceTaskState.state.value}")
    }
}
