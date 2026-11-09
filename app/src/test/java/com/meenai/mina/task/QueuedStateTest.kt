package com.meenai.mina.task

import com.meenai.mina.capsule.CapsuleCards
import com.meenai.mina.capsule.CapsuleKind
import com.meenai.mina.capsule.CapsuleRun
import com.meenai.mina.capsule.CardContent
import com.meenai.mina.capsule.CardRun
import com.meenai.mina.core.agent.GateReason
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.ui.ChatCopy
import com.meenai.mina.ui.ChatViewModel
import com.meenai.mina.ui.Exchange
import com.meenai.mina.ui.ExchangeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** 排在开始队列里、还没开始的任务:各处都写排队中,不写已暂停,也不给「继续」(点了也开始不了)。 */
class QueuedStateTest {
    @get:Rule val texts = EnglishTexts()

    private val why = "Queued: 1 task ahead"
    private val queued = TaskState.Held(GateReason.queued(why))

    @Test fun queuedIsNotResumable() {
        assertFalse(queued.reason.resumable)
        assertFalse(queued.reason.needsYou)
    }

    @Test fun chatCardShowsQueuedNotPaused() {
        val ex = ChatViewModel.reduce(Exchange("订会议室", emptyList(), ExchangeStatus.Working, live = true), queued)
        assertEquals(ExchangeStatus.Queued(why), ex.status)
        assertFalse(ChatCopy.paused(ex.status))
        assertEquals("Queued", ChatCopy.progressLine(ex))
        assertEquals("Queued" to ChatCopy.Tone.Accent, ChatCopy.subtitle(ex, null))
    }

    // 不能点继续的等待(比如等你在屏幕上选一下)在对话里也不给继续
    @Test fun chatCardOffersContinueOnlyWhenResumable() {
        val ex = Exchange("x", emptyList(), ExchangeStatus.Working, live = true)
        assertEquals(ExchangeStatus.Paused("挪走了", true), ChatViewModel.reduce(ex, TaskState.Held(TaskGate.paused("挪走了"))).status)
        assertEquals(ExchangeStatus.Paused("选一下", false), ChatViewModel.reduce(ex, TaskState.Held(TaskGate.userStep("选一下"))).status)
    }

    @Test fun notificationSaysQueued() {
        assertEquals("Queued" to why, TaskService.describe(queued))
        assertEquals("Paused" to "挪走了", TaskService.describe(TaskState.Held(TaskGate.paused("挪走了"))))
    }

    @Test fun capsuleCardHasNoContinue() {
        assertEquals(CardContent.Held(why, false), CapsuleCards.content(CardRun(CapsuleRun("a", "c-a", queued, goal = "订会议室"), CapsuleKind.Held, "")))
    }
}
