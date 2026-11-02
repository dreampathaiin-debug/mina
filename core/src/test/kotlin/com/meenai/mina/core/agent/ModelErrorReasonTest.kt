package com.meenai.mina.core.agent

import com.meenai.mina.core.model.ModelError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelErrorReasonTest {
    /** Model not found: with a model name, say which one, where to fix it, what a model ID looks like; without one, fall back to the general wording. */
    @Test fun modelNotFoundNamesTheModel() {
        assertEquals(
            "Model “gemini” does not exist at this provider. Open Settings › Models and check the model name (use the full model ID, e.g. Gemini model names look like gemini-flash-latest) or switch to the matching preset.",
            StopRules.modelFailed(ModelError.ModelNotFound("gemini")).reason,
        )
        assertEquals("Model not found. Check the model name and Base URL in Settings › Models.", StopRules.modelFailed(ModelError.ModelNotFound()).reason)
        assertEquals("Model not found: “gemini”", ModelError.ModelNotFound("gemini").message)
    }

    /** A 404 may also be a gateway or relay saying something else: with the server's own words, quote them and don't claim the model is gone; no detail falls back to the wording above. */
    @Test fun modelNotFoundCarriesWhatTheServerSaid() {
        assertEquals(
            "Calling model “group/rpa”: the server replied: none of group/rpa's models is ready. The model name may be wrong, or the service may be temporarily unavailable. " +
                "Open Settings › Models and check the model name (use the full model ID, e.g. Gemini model names look like gemini-flash-latest) or switch to the matching preset.",
            StopRules.modelFailed(ModelError.ModelNotFound("group/rpa", "none of group/rpa's models is ready")).reason,
        )
        assertEquals(
            "The server replied: The model `x` does not exist. The model name may be wrong, or the service may be temporarily unavailable. Check the model name and Base URL in Settings › Models.",
            StopRules.modelFailed(ModelError.ModelNotFound("", "The model `x` does not exist.")).reason,
        )
        val said = StopRules.modelFailed(ModelError.ModelNotFound("m", "gone")).reason
        assertFalse(said, said.contains("does not exist"))
    }

    /** Server text quoted mid-sentence: all three detail-carrying failures drop exactly one trailing period; a shortened ellipsis stays. */
    @Test fun quotedServerTextDropsOneTrailingPeriod() {
        fun reasons(detail: String) = listOf(
            StopRules.modelFailed(ModelError.ModelNotFound("m", detail)).reason,
            StopRules.modelFailed(ModelError.ChatGptNotEligible(detail)).reason,
            StopRules.modelFailed(ModelError.AccountPlanNotAllowed("ChatGPT", detail)).reason,
        )
        reasons("Not allowed.").forEach { assertTrue(it, it.contains("Not allowed. ") && !it.contains("Not allowed..")) }
        reasons("不允许。").forEach { assertTrue(it, it.contains("不允许. ") && !it.contains("不允许。。")) }
        reasons("Not allowed").forEach { assertTrue(it, it.contains("Not allowed. ")) }
        reasons("Too long…").forEach { assertTrue(it, it.contains("Too long…. ")) }
        reasons("Wait...").forEach { assertTrue(it, it.contains("Wait.... ")) }
    }

    /** Models this account cannot use, models without tool support: say which one, where to switch, or enable it in the provider console. */
    @Test fun modelNotAvailableNamesTheModel() {
        assertEquals(
            "Model “qwen-max” is not available for this account (not enabled or no permission). Switch models in Settings › Models, or enable it in the provider's console.",
            StopRules.modelFailed(ModelError.ModelNotAvailable("qwen-max", "not activated")).reason,
        )
        assertEquals(
            "Model “gemma” does not support tool calls, and Mina needs tools to work. Switch to a model that supports tool calls in Settings › Models.",
            StopRules.modelFailed(ModelError.ToolsUnsupported("gemma")).reason,
        )
    }

    /** A cut stream has its own wording and never blames the Base URL; only a real parse failure (format mismatch) points at Base URL and API type. */
    @Test fun streamCutIsNotBlamedOnConfiguration() {
        val cut = StopRules.modelFailed(ModelError.StreamCut()).reason
        assertTrue(cut, cut.contains("upstream connection closed"))
        assertTrue(cut, cut.contains("retries"))
        assertFalse(cut, cut.contains("Base URL"))
        assertFalse(cut, cut.contains("API type"))
        assertTrue(StopRules.modelFailed(ModelError.BadResponse("streamed response is not valid JSON")).reason.contains("Check that the Base URL matches the API type"))
    }
}
