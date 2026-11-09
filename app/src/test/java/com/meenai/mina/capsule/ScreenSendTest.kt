package com.meenai.mina.capsule

import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.ui.ScreenCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**Double-click or single-click, which conversation should the screenshot be sent to, and if the screen is included, how should it be called in the list. */
class ScreenSendTest {
    @get:Rule val texts = EnglishTexts()

    @Test fun secondTapWithinTimeoutIsDouble() {
        val t = DoubleTap(300)
        assertEquals(DoubleTap.Tap.First, t.tap(1_000))
        assertEquals(DoubleTap.Tap.Double, t.tap(1_250))
        //After double-clicking, it is no longer counted as a click.
        assertFalse(t.settle(1_400))
    }

    @Test fun singleTapSettlesAfterTimeout() {
        val t = DoubleTap(300)
        assertEquals(DoubleTap.Tap.First, t.tap(1_000))
        assertFalse(t.settle(1_200))
        assertTrue(t.settle(1_300))
        assertFalse("Only counts once", t.settle(1_400))
    }

    @Test fun lateSecondTapStartsOver() {
        val t = DoubleTap(300)
        t.tap(1_000)
        assertEquals(DoubleTap.Tap.First, t.tap(1_400))
        assertEquals(DoubleTap.Tap.Double, t.tap(1_600))
    }

    @Test fun resetForgetsTheFirstTap() {
        val t = DoubleTap(300)
        t.tap(1_000)
        t.reset()
        assertFalse(t.settle(2_000))
        assertEquals(DoubleTap.Tap.First, t.tap(2_100))
    }

    private fun run(key: String, conversation: String, state: TaskState, kind: CapsuleKind, onScreen: Boolean = false) =
        CardRun(CapsuleRun(key, conversation, state, goal = "g", onScreen = onScreen), kind, "")

    @Test fun screenGoesToTheConversationTheCardShows() {
        val running = run("a", "c1", TaskState.Running(1, ""), CapsuleKind.Running, onScreen = true)
        val done = run("b", "c2", TaskState.Succeeded("good"), CapsuleKind.Done)
        assertEquals("c1", CapsuleCards.conversationOf(CapsuleCards.of(listOf(running), null)!!))
        assertEquals("c2", CapsuleCards.conversationOf(CapsuleCards.of(listOf(done), null)!!))
        //free card:Follow up on recent conversations;Newly opened by the caller when a new conversation is selected
        assertEquals("c9", CapsuleCards.conversationOf(CardView.Chat("c9", title = "t")))
        assertNull(CapsuleCards.conversationOf(CardView.Chat("c9", fresh = true)))
        assertNull(CapsuleCards.conversationOf(CardView.Chat(null)))
        //When there are several things, the card only says the one that comes first,The screen is sent to the conversation it is in
        val several = CapsuleCards.of(listOf(running, done))!!
        assertEquals(CardInputKind.Steer, CapsuleCards.input(several)?.kind)
        assertEquals("c1", CapsuleCards.conversationOf(several))
    }

    @Test fun inputKindDecidesHowTheScreenIsSent() {
        val running = run("a", "c1", TaskState.Running(1, ""), CapsuleKind.Running, onScreen = true)
        assertEquals(CardInputKind.Steer, CapsuleCards.input(CapsuleCards.of(listOf(running), null)!!)!!.kind)
        val asking = run("b", "c2", TaskState.WaitingInput("Make a note??", listOf("Make a note", "Need not")), CapsuleKind.NeedAnswer)
        assertEquals(CardInputKind.Answer, CapsuleCards.input(CapsuleCards.of(listOf(asking), null)!!)!!.kind)
        assertEquals(CardInputKind.Start, CapsuleCards.input(CardView.Chat(null))!!.kind)
    }

    @Test fun plainTitles() {
        assertEquals("Are there any pitfalls in Article 3??", ScreenCopy.plain("[[screen:s1]]\nAre there any pitfalls in Article 3??"))
        assertEquals(ScreenCopy.TAG, ScreenCopy.plain("[[screen:s1]]"))
        assertEquals("an ordinary sentence", ScreenCopy.plain("an ordinary sentence"))
    }
}
