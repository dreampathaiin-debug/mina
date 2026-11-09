package com.meenai.mina.ui

import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.GateReason
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.i18n.EnglishTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** 「后台屏」那一页的排序和文字(不碰界面,也不碰真任务)。 */
class ScreensCopyTest {
    @get:Rule val texts = EnglishTexts()

    private fun row(displayId: Int, key: String = "k$displayId", foreground: String? = null, web: Boolean = false) =
        ScreenRow(key, "c$displayId", "做点什么", displayId, TaskState.Running(0, "Open 设置"), foreground, web)

    /** 建了屏的在前(按建屏先后);还没建屏(-1)的排在后面,彼此保持原来的先后。 */
    @Test fun screensAreOrderedWithUnbuiltOnesLast() {
        val rows = listOf(row(-1, "a"), row(15), row(-1, "b"), row(12))
        assertEquals(listOf("k12", "k15", "a", "b"), orderedScreens(rows).map { it.key })
        assertEquals(emptyList<ScreenRow>(), orderedScreens(emptyList()))
    }

    /** 第二行说在用哪个 App,不写屏号、像素、密度这些开发用的东西。 */
    @Test fun placeSaysWhichAppNotDisplayInternals() {
        assertEquals("Using 微信", ScreensCopy.place(row(12, foreground = "微信")))
        assertEquals("Using the browser", ScreensCopy.place(row(-1, web = true)))
        assertEquals("Hasn’t opened an app yet", ScreensCopy.place(row(-1)))
        assertEquals("Can’t see which app is open right now", ScreensCopy.place(row(12)))
        listOf(row(12, foreground = "微信"), row(12), row(-1)).map(ScreensCopy::place).forEach {
            assertFalse(it, it.contains("12") || it.contains("dpi") || it.contains("×"))
        }
    }

    /** 每种状态都有一句;等着的、要你动手的和真在干活的分得开。 */
    @Test fun everyStateHasALabel() {
        assertEquals("Running", ScreensCopy.state(TaskState.Running(1, "点了一下")))
        assertEquals("Task done", ScreensCopy.state(TaskState.Succeeded("好了")))
        assertEquals("Task not finished", ScreensCopy.state(TaskState.Failed("Error", FailKind.Model)))
        assertEquals("Task stopped", ScreensCopy.state(TaskState.Cancelled))
        val needsYou = listOf(
            TaskState.WaitingInput("密码是什么"),
            TaskState.WaitingTakeover("payment", "要你付款"),
            TaskState.Held(GateReason("waiting_user", "在系统的选择框里选一个", needsYou = true)),
        )
        assertEquals(listOf("Needs you", "Needs you", "Needs you"), needsYou.map(ScreensCopy::state))
        // 只是等条件(锁屏、助手没连上)的不算「要你处理」
        assertEquals("Waiting", ScreensCopy.state(TaskState.Held(GateReason("locked", "锁屏了"))))
        assertEquals("Waiting", ScreensCopy.state(TaskState.Idle))
    }
}
