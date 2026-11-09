package com.meenai.mina.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.core.selfcheck.Advice
import com.meenai.mina.core.selfcheck.CheckResult
import com.meenai.mina.core.selfcheck.CheckStatus
import com.meenai.mina.core.selfcheck.DeviceFacts
import com.meenai.mina.core.selfcheck.SelfCheck
import com.meenai.mina.core.selfcheck.SelfCheckState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**set up ›Restoration and Repair ›Compatibility self-test:Page without real self-check,Look at what is displayed in each state and who the button is given to. */
@RunWith(AndroidJUnit4::class)
class SelfCheckScreenTest {

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val facts = DeviceFacts("Acme", "Phone 7", "14", 34, listOf("arm64-v8a"), 8L shl 30, 1080, 2400, true, "0.1.21", "abc1234")

    private fun show(state: SelfCheckState, busy: Boolean = false) {
        compose.setContent {
            SelfCheckContent(
                state, busy, onStart = { calls += "start" }, onCancel = { calls += "cancel" },
                onCopy = { calls += "copy" }, onGithub = { calls += "github" }, onBack = { calls += "back" },
            )
        }
    }

    @Test
    fun idleShowsStartAndEveryCheck() {
        show(SelfCheckState())
        compose.onNodeWithTag("selfcheck-start").performClick()
        assertEquals(listOf("start"), calls)
        compose.onNodeWithText(SelfCheckCopy.name(SelfCheck.APP_COPIES)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun startIsBlockedWhileATaskRuns() {
        show(SelfCheckState(), busy = true)
        compose.onNodeWithTag("selfcheck-start").assertIsNotEnabled()
    }

    @Test
    fun runningShowsCancel() {
        show(SelfCheckState(SelfCheckState.Phase.Running))
        compose.onNodeWithTag("selfcheck-cancel").performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun endedShowsReportAndBothButtons() {
        val results = SelfCheck.entries.map { CheckResult(it, CheckStatus.Pass, "ok") }.toMutableList()
        results[SelfCheck.STAYS_ALIVE.ordinal] = CheckResult(SelfCheck.STAYS_ALIVE, CheckStatus.Fail, "frames stopped after 6 s", Advice.FramesStopped)
        show(SelfCheckState(SelfCheckState.Phase.Done, results, facts, 40_000))
        compose.onNodeWithText(SelfCheckCopy.advice(Advice.FramesStopped)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("selfcheck-report").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("selfcheck-copy").performScrollTo().performClick()
        compose.onNodeWithTag("selfcheck-github").performScrollTo().performClick()
        assertEquals(listOf("copy", "github"), calls)
    }
}
