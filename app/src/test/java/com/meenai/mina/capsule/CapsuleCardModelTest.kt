package com.meenai.mina.capsule

import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.cap.web.KeptPage
import com.meenai.mina.confirm.ConfirmationNotifier
import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.GateReason
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.gate.ConfirmationRequest
import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.ui.Exchange
import com.meenai.mina.ui.ExchangeStatus
import com.meenai.mina.ui.StepRow
import com.meenai.mina.ui.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleCardModelTest {
    @get:Rule val texts = EnglishTexts()

    private fun card(
        key: String, state: TaskState, kind: CapsuleKind = CapsuleKind.Running, line: String = "", ahead: Int = 0, behind: Int = 0,
        exchange: Exchange? = null, onScreen: Boolean = true,
    ) = CardRun(CapsuleRun(key, "c-$key", state, goal = "Target$key", exchange = exchange, onScreen = onScreen), kind, line, ahead = ahead, behind = behind)

    /**What is the card talking about?(task cards or conversation cards). */
    private fun keyOf(v: CardView?): String? = when (v) {
        is CardView.Detail -> v.run.run.key
        is CardView.Chat -> v.run?.run?.key
        else -> null
    }

    private val running = TaskState.Running(2, "note", "tap")

    // ----------Just say one thing ----------

    @Test fun keptPageCardWhenNothingElse() {
        val kept = KeptPage(3, "c-k", "r-k", "play course", "course.example.com", null, null, 0, 7_200_000)
        val v = CapsuleCards.of(emptyList(), null, idle = CardView.Chat("c-x"), kept = kept)
        assertEquals(CardView.Kept(kept), v)
        //There is no input box or stop on the card;"Open Conversation" to go to the conversation it came from
        assertNull(CapsuleCards.input(v!!))
        assertNull(CapsuleCards.stopKey(v))
        assertEquals("c-k", CapsuleCards.conversationOf(v))
        //Have a task(In progress or just finished)We still talk about tasks
        assertEquals("a", keyOf(CapsuleCards.of(listOf(card("a", running)), null, kept = kept)))
    }

    @Test fun oneRunOpensItsDetailDirectly() {
        val v = CapsuleCards.of(listOf(card("a", running)), null) as CardView.Detail
        assertEquals("a", v.run.run.key)
    }

    @Test fun severalRunsShowOnlyTheFirstInImportanceOrder() {
        //According to the order of cardRuns(Want you to handle it, in progress, just finished)Only take the first item,no list
        val need = card("n", TaskState.WaitingInput("What time is it?"), CapsuleKind.NeedAnswer)
        val done = card("d", TaskState.Succeeded("alright"), CapsuleKind.Done)
        assertEquals("n", keyOf(CapsuleCards.of(listOf(need, card("a", running), done))))
        assertEquals("a", keyOf(CapsuleCards.of(listOf(card("a", running), done))))
        //Only the ones that just ended are left:Also one,not a list
        assertEquals("d", keyOf(CapsuleCards.of(listOf(done, card("e", TaskState.Cancelled, CapsuleKind.Cancelled)))))
        assertNull(CapsuleCards.of(emptyList(), "c-a"))
    }

    // ----------Task cards or conversation cards ----------

    @Test fun taskCardOnlyWhileOperatingAScreenNow() {
        val waits = listOf(
            running to CapsuleKind.Running,
            TaskState.Held(GateReason("q")) to CapsuleKind.Held,
            TaskState.WaitingTakeover("login", "Log in") to CapsuleKind.NeedTakeover,
            TaskState.WaitingInput("What time is it?") to CapsuleKind.NeedAnswer,
            TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "Zhang San", "See you tomorrow", null, "com.chat")) to CapsuleKind.NeedConfirm,
        )
        for ((s, k) in waits) {
            assertTrue("$s on screen", CapsuleCards.of(listOf(card("a", s, k)), null) is CardView.Detail)
            //Pure chat(No virtual screen, no web page open)The same status is the dialogue card
            val chat = CapsuleCards.of(listOf(card("a", s, k, onScreen = false)), null) as CardView.Chat
            assertEquals("c-a", chat.target)
            assertEquals(CapsuleCards.content(card("a", s, k)), chat.content)
        }
    }

    @Test fun finishedAppTaskIsAChatCard() {
        //I have operated the App and received a call after completing the process.:According to the current status,It's a conversation card(It’s the same even if the screen hasn’t been released yet)
        for ((s, k) in listOf(
            TaskState.Succeeded("alright") to CapsuleKind.Done,
            TaskState.Failed("not found", FailKind.GaveUp) to CapsuleKind.Failed,
            TaskState.Cancelled to CapsuleKind.Cancelled,
        )) {
            assertTrue("$s", CapsuleCards.of(listOf(card("a", s, k, onScreen = true)), null) is CardView.Chat)
            assertTrue(!CapsuleCards.taskCard(card("a", s, k, onScreen = true)))
        }
        //The thing you are working on becomes a pure chat(The screen is gone):Change to conversation card now
        assertTrue(CapsuleCards.taskCard(card("a", running)))
        assertTrue(!CapsuleCards.taskCard(card("a", running, onScreen = false)))
    }

    @Test fun stopKeyWhileStillGoing() {
        assertEquals("a", CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", running)), null)!!))
        assertEquals("a", CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", running, onScreen = false)), null)!!))
        assertEquals("a", CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", TaskState.WaitingInput("What time is it?"), CapsuleKind.NeedAnswer, onScreen = false)), null)!!))
        assertNull(CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", TaskState.Succeeded("alright"), CapsuleKind.Done)), null)!!))
        assertNull(CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", TaskState.WaitingTakeover("login", "Log in"), CapsuleKind.NeedTakeover)), null)!!))
        assertNull(CapsuleCards.stopKey(CardView.Chat("c1")))
    }

    // ----------What to put in each state ----------

    @Test fun workingListsAllStepsEndingWithTheCurrentLine() {
        val ex = Exchange("g", listOf("Open shopping", "Click the search box", "Enter toothpaste", "See the results").map { StepRow(1, it, "", StepState.Ok) }, ExchangeStatus.Working, live = true)
        assertEquals(listOf("Open shopping", "Click the search box", "Enter toothpaste", "See the results", "Pick one with high sales volume"), CapsuleCards.steps(ex, "Pick one with high sales volume"))
        //That sentence at this moment is the last step:Not repeated
        assertEquals(listOf("Open shopping", "Click the search box", "Enter toothpaste", "See the results"), CapsuleCards.steps(ex, "See the results"))
        assertEquals(listOf("Thinking about the next step"), CapsuleCards.steps(null, "Thinking about the next step"))
        assertEquals(CardContent.Working(listOf("Thinking")), CapsuleCards.content(card("a", running, line = "Thinking")))
    }

    @Test fun takeoverSaysWhyWithPaymentAndQueue() {
        val s = TaskState.WaitingTakeover("payment", "Please enter payment password", item = "toothpaste", amount = "¥19.9", address = "Home")
        val first = CapsuleCards.content(card("a", s, CapsuleKind.NeedTakeover, "Needed from you: Payment", behind = 2)) as CardContent.Takeover
        assertEquals("Needed from you: Payment", first.need)
        assertEquals("Please enter payment password\nItem: toothpaste\nAmount: ¥19.9\nAddress: Home", first.body)
        assertEquals("After this, 2 more are waiting for you", first.queueLine)
        assertTrue(first.canTakeOver)
        val queued = CapsuleCards.content(card("b", s, CapsuleKind.NeedTakeover, ahead = 1)) as CardContent.Takeover
        assertEquals(CapsuleCards.QUEUED_AHEAD, queued.queueLine)
        assertTrue(!queued.canTakeOver)
        assertNull((CapsuleCards.content(card("c", s, CapsuleKind.NeedTakeover)) as CardContent.Takeover).queueLine)
    }

    @Test fun confirmUsesTheChatCardCopy() {
        val s = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.order, "shop", "Two sticks of toothpaste", "¥19.9", "com.shop"))
        val ex = Exchange("g", emptyList(), ExchangeStatus.NeedsConfirmation("I want your consent:Place an order", "shopping in\namount:¥19.9", 42), live = true)
        assertEquals(CardContent.Confirm("I want your consent:Place an order", "shopping in\namount:¥19.9", 42), CapsuleCards.content(card("a", s, CapsuleKind.NeedConfirm, exchange = ex)))
        //I can agree even if I haven’t answered this question yet.:The number is the same as that used for conversation pages and notifications.
        val raw = CapsuleCards.content(card("a", s, CapsuleKind.NeedConfirm)) as CardContent.Confirm
        assertEquals(ConfirmationNotifier.requestKey(s), raw.key)
        assertTrue(raw.body.contains("Amount: ¥19.9"))
    }

    @Test fun questionHeldAndResults() {
        assertEquals(CardContent.Question("Which one do you want?", listOf("big", "Small")), CapsuleCards.content(card("a", TaskState.WaitingInput("Which one do you want?", listOf("big", "", "Small")))))
        assertEquals(CardContent.Question("What time is it?", emptyList()), CapsuleCards.content(card("a", TaskState.WaitingInput("What time is it?"))))
        assertEquals(CardContent.Held("Queuing", true), CapsuleCards.content(card("a", TaskState.Held(GateReason("q", "Queuing", resumable = true)))))
        assertEquals(CardContent.Held("On hold", false), CapsuleCards.content(card("a", TaskState.Held(GateReason("q")))))
        assertEquals(CardContent.Result(CapsuleKind.Done), CapsuleCards.content(card("a", TaskState.Succeeded("Placed an order"))))
        assertEquals(CardContent.Result(CapsuleKind.Failed), CapsuleCards.content(card("a", TaskState.Failed("not found", FailKind.GaveUp))))
        assertEquals(CardContent.Result(CapsuleKind.Cancelled), CapsuleCards.content(card("a", TaskState.Cancelled)))
    }

    @Test fun workingStepsAreCappedKeepingTheNewest() {
        val ex = Exchange("g", (1..80).map { StepRow(1, "No. $it step", "", StepState.Ok) }, ExchangeStatus.Working, live = true)
        val steps = CapsuleCards.steps(ex, "Thinking about the next step")
        assertEquals(CapsuleCards.STEPS, steps.size)
        assertEquals("No. 32 step", steps.first())
        assertEquals("Thinking about the next step", steps.last())
    }

    @Test fun screenOnlyBesideWorkTakeoverAndHeld() {
        assertTrue(CapsuleCards.showsScreen(CardContent.Working(emptyList())))
        assertTrue(CapsuleCards.showsScreen(CardContent.Takeover("", "", null, true)))
        assertTrue(CapsuleCards.showsScreen(CardContent.Held("", false)))
        assertTrue(!CapsuleCards.showsScreen(CardContent.Question("", emptyList())))
        assertTrue(!CapsuleCards.showsScreen(CardContent.Result(CapsuleKind.Done)))
    }

    // ----------Input box ----------

    @Test fun inputGoesToTheRightPlace() {
        fun inputOf(state: TaskState, kind: CapsuleKind) = CapsuleCards.input(CapsuleCards.of(listOf(card("a", state, kind)), null)!!)?.kind
        assertEquals(CardInputKind.Steer, inputOf(running, CapsuleKind.Running))
        assertEquals(CardInputKind.Steer, inputOf(TaskState.Held(GateReason("q")), CapsuleKind.Held))
        assertEquals(CardInputKind.Answer, inputOf(TaskState.WaitingInput("What time is it?", listOf("8 o'clock")), CapsuleKind.NeedAnswer))
        assertEquals(CardInputKind.Continue, inputOf(TaskState.Succeeded("alright"), CapsuleKind.Done))
        assertEquals(CardInputKind.Continue, inputOf(TaskState.Failed("x", FailKind.Model), CapsuleKind.Failed))
        //Deal with that matter first when I want you to take over or agree to it,No input box
        assertNull(inputOf(TaskState.WaitingTakeover("login", "Log in"), CapsuleKind.NeedTakeover))
        val confirm = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "Zhang San", "See you tomorrow", null, "com.chat"))
        assertNull(inputOf(confirm, CapsuleKind.NeedConfirm))
        assertEquals(CardInput(CapsuleCards.HINT_START, CardInputKind.Start), CapsuleCards.input(CardView.Chat(null)))
        assertEquals(CardInput(CapsuleCards.HINT_CONTINUE, CardInputKind.Continue), CapsuleCards.input(CapsuleCards.of(listOf(card("a", TaskState.Succeeded("alright"), CapsuleKind.Done)), null)!!))
        //You can also interrupt when answering in pure chat;Deal with that matter first when I ask for your consent
        assertEquals(CardInputKind.Steer, CapsuleCards.input(CapsuleCards.of(listOf(card("a", running, onScreen = false)), null)!!)?.kind)
        assertNull(CapsuleCards.input(CapsuleCards.of(listOf(card("a", confirm, CapsuleKind.NeedConfirm, onScreen = false)), null)!!))
    }

    @Test fun afterSendingTheCardFollowsThatConversation() {
        val done = card("a", TaskState.Succeeded("alright"), CapsuleKind.Done)
        val other = card("b", running)
        //After sending the message, the new task has not started yet:The only conversation that followed was the one that ended
        assertEquals("a", keyOf(CapsuleCards.of(listOf(other, done), follow = "c-a")))
        //New mission is up:ongoing priority
        val again = card("a2", running).let { it.copy(run = it.run.copy(conversationId = "c-a")) }
        assertEquals("a2", keyOf(CapsuleCards.of(listOf(other, done, again), follow = "c-a")))
        //There are no tasks in the following conversation.:Take the first item in order
        assertEquals("b", keyOf(CapsuleCards.of(listOf(other, done), follow = "c-gone")))
    }

    @Test fun idleCardWhenNoRuns() {
        val idle = CardView.Chat("c1", title = "buy toothpaste")
        assertEquals(idle, CapsuleCards.of(emptyList(), null, idle = idle))
        assertEquals("c1", idle.target)
        assertNull(idle.copy(fresh = true).target)
        //Do not display idle cards when there are tasks
        assertTrue(CapsuleCards.of(listOf(card("a", running)), null, idle = idle) is CardView.Detail)
    }

    /**
     *The conversation the user is currently in takes priority over other conversations that have ended and are still pending.:Otherwise, after opening a new conversation on the conversation page,Clicking on the capsule still shows the result card of the old conversation.
     *The one that just ended in the same conversation still counts.,Ongoing tasks still take priority.
     */
    @Test fun idleCardOfTheConversationOnScreenBeatsAnEndedRunFromAnother() {
        val ended = card("a", TaskState.Succeeded("alright"), CapsuleKind.Done)
        val fresh = CardView.Chat(null, fresh = true, current = true)
        assertEquals(fresh, CapsuleCards.of(listOf(ended), null, idle = fresh))
        //A new conversation has been sent and is running:Task cards remain the same
        assertTrue(CapsuleCards.of(listOf(card("r", running)), null, idle = fresh) is CardView.Detail)
        //The one that just ended in the same conversation:show it(with results),Do not replace with empty card
        assertEquals("a", keyOf(CapsuleCards.of(listOf(ended), null, idle = CardView.Chat("c-a", current = true))))
        //Don’t grab the “Recent Conversations” card at the bottom of your pocket:It is not what the user is currently experiencing
        assertEquals("a", keyOf(CapsuleCards.of(listOf(ended), null, idle = CardView.Chat("c-z", title = "Others"))))
        //There are no tasks, and there is still one page in the background.:It’s still the same page of cards,Not knocked out by free cards
        val kept = KeptPage(3, "c-k", "r-k", "play course", "course.example.com", null, null, 0, 7_200_000)
        assertEquals(CardView.Kept(kept), CapsuleCards.of(emptyList(), null, idle = fresh, kept = kept))
    }
}
