package com.meenai.mina.capsule

import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.cap.web.KeptPage
import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.GateReason
import com.meenai.mina.core.agent.RunProgress
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.gate.ConfirmationRequest
import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.takeover.Takeover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleModelTest {
    @get:Rule val texts = EnglishTexts()

    private val model = CapsuleModel(reasonLabel = { if (it == "payment") "Payment" else null })

    private fun inputs(
        vararg runs: CapsuleRun,
        mode: CapsuleMode = CapsuleMode.DuringTasks,
        foreground: Boolean = false,
        interactive: Boolean = true,
        service: Boolean = true,
        cardOpen: Boolean = false,
        kept: KeptPage? = null,
    ) = CapsuleInputs(runs.toList(), mode, foreground, interactive, service, cardOpen, kept)

    private val kept = KeptPage(1, "c-k", "r-k", "Open this course,Don't close it", "course.example.com", null, null, 0, 7_200_000)

    private fun run(key: String, state: TaskState, stage: Takeover.Stage = Takeover.Stage.None) = CapsuleRun(key, "c-$key", state, stage)
    private fun running(said: String? = null, step: Int = 3, reason: String? = null, progress: RunProgress? = null) =
        TaskState.Running(step, "note", "tap", progress, reason, narration = said)
    private val takeover = TaskState.WaitingTakeover("payment", "Please enter payment password")
    private val confirm = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "Zhang San", "See you tomorrow", null, "com.chat"))

    private fun shown(v: CapsuleView): CapsuleView.Shown = v as? CapsuleView.Shown ?: error("expected shown, got $v")

    // ----------when not to show ----------

    @Test fun hiddenWhenOffForegroundLockedOrNoService() {
        val r = run("a", running("Searching for toothpaste"))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, mode = CapsuleMode.Off), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, foreground = true), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, interactive = false), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, service = false), 0))
        assertEquals("Searching for toothpaste", shown(model.update(inputs(r), 0)).text)
    }

    @Test fun noTasksHiddenDuringTasksIdleIconWhenAlways() {
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 0))
        assertEquals(CapsuleKind.Idle, shown(model.update(inputs(mode = CapsuleMode.Always), 0)).kind)
        //Even if it is always displayed, it will still be displayed in the Mina interface and will not be displayed when the screen is locked.
        assertEquals(CapsuleView.Hidden, model.update(inputs(mode = CapsuleMode.Always, foreground = true), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(mode = CapsuleMode.Always, interactive = false), 0))
    }

    // ----------The page left running in the background ----------

    @Test fun keptPageStaysShownWhenNoTaskRuns() {
        //no task:Expand to "Backend web page is running",talking about the dialogue it comes from;This is also the case when "always displayed",Not an idle icon
        for (mode in listOf(CapsuleMode.DuringTasks, CapsuleMode.Always)) {
            val v = shown(model.update(inputs(mode = mode, kept = kept), 0))
            assertEquals(CapsuleKind.KeptPage, v.kind)
            assertEquals("Background page running", v.text)
            assertEquals("c-k", v.conversationId)
            assertTrue(v.pill)
        }
        //The switch is "never show". In Mina, it is still not displayed when the screen is locked.(Rely on notification)
        assertEquals(CapsuleView.Hidden, model.update(inputs(mode = CapsuleMode.Off, kept = kept), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(foreground = true, kept = kept), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(interactive = false, kept = kept), 0))
    }

    @Test fun runningTaskAndFreshResultComeBeforeKeptPage() {
        assertEquals(CapsuleKind.Running, shown(model.update(inputs(run("a", running("")), kept = kept), 0)).kind)
        //I’ll just stop for a moment to finish the results.,The page that remains after you stop it
        val done = run("a", TaskState.Succeeded("alright"))
        assertEquals(CapsuleKind.Done, shown(model.update(inputs(done, kept = kept), 10)).kind)
        assertEquals(CapsuleKind.KeptPage, shown(model.update(inputs(done, kept = kept), 10 + CapsuleModel.LINGER_MS)).kind)
        assertTrue(!CapsuleKind.KeptPage.active && !CapsuleKind.KeptPage.needsYou)
    }

    @Test fun modeParsesWithAlwaysDefault() {
        assertEquals(CapsuleMode.Always, CapsuleMode.parse(null))
        assertEquals(CapsuleMode.Always, CapsuleMode.parse("something-old"))
        CapsuleMode.entries.forEach { assertEquals(it, CapsuleMode.parse(it.name)) }
    }

    // ----------that line ----------

    @Test fun runningTextNarrationThenReasonThenFallback() {
        assertEquals("Searching Toothpaste", CapsuleModel.runningText(running("Searching\nToothpaste ", reason = "Find the search box")))
        assertEquals("Find the search box", CapsuleModel.runningText(running(" ", reason = "Find the search box")))
        assertEquals(CapsuleModel.THINKING, CapsuleModel.runningText(running()))
        assertEquals(CapsuleModel.PREPARING, CapsuleModel.runningText(running(step = 0)))
        assertEquals("Following a route 3/7", CapsuleModel.runningText(running("Searching for toothpaste", progress = RunProgress.Replay(3, 7))))
        assertEquals("4 steps at once", CapsuleModel.runningText(running(progress = RunProgress.Batch(4))))
    }

    @Test fun eachStateHasItsLine() {
        fun line(state: TaskState, stage: Takeover.Stage = Takeover.Stage.None) =
            shown(CapsuleModel(reasonLabel = { if (it == "payment") "Payment" else null }).update(inputs(run("a", state, stage)), 0))
        assertEquals(CapsuleKind.NeedTakeover to "Needs you: Payment", line(takeover, Takeover.Stage.Waiting).let { it.kind to it.text })
        assertEquals("Needs you: Please log in", line(TaskState.WaitingTakeover("login", "Please log in"), Takeover.Stage.Waiting).text)
        assertEquals(CapsuleKind.NeedConfirm to "Needs your OK: Send", line(confirm).let { it.kind to it.text })
        assertEquals(CapsuleKind.NeedAnswer to "Mina asks: Which color do you want?", line(TaskState.WaitingInput("Which color do you want?")).let { it.kind to it.text })
        assertEquals(CapsuleKind.Held to "On hold: you opened this App", line(TaskState.Held(GateReason("paused", "you opened this App"))).let { it.kind to it.text })
        //I want you to move your hands on your phone(Select one in the system selection box):It's up to you to handle it
        val pick = line(TaskState.Held(GateReason("waiting_user", "“Chat” is asking which one to use, please select it on the screen.", needsYou = true)))
        assertEquals(CapsuleKind.NeedStep to "Needs you: “Chat” is asking which one to use, please select it on the screen.", pick.kind to pick.text)
        assertTrue(pick.kind.needsYou)
        assertEquals("On hold", line(TaskState.Held(GateReason("waiting_unlock"))).text)
        val bar = line(takeover, Takeover.Stage.HandedOver)
        assertEquals(CapsuleKind.HandedOver, bar.kind)
        assertEquals(CapsuleModel.HANDED_OVER, bar.text)
        assertEquals("Payment · Tap the right side when done", bar.detail)
        //It has been handed back, and the cycle has not gone down yet.:no longer want you
        assertEquals(CapsuleKind.Running, line(takeover).kind)
    }

    // ----------Priority and number of pieces ----------

    @Test fun highestPriorityWinsAndCountsTasks() {
        val v = shown(model.update(inputs(run("a", running("Searching for toothpaste")), run("b", TaskState.WaitingInput("Which one do you want?"))), 0))
        assertEquals(CapsuleKind.NeedAnswer, v.kind)
        assertEquals("b", v.runKey)
        assertEquals("c-b", v.conversationId)
        assertEquals(2, v.count)
        assertEquals(1, v.needCount)
        assertEquals("1 need you · 2 in total", v.text)
    }

    @Test fun severalRunningAddCount() {
        val v = shown(model.update(inputs(run("a", running("Searching for toothpaste")), run("b", running("Watching the weather"))), 0))
        //When it's important, say which one starts first
        assertEquals("a", v.runKey)
        assertEquals("Searching for toothpaste · 2 in total", v.text)
        assertEquals(0, v.needCount)
    }

    @Test fun priorityOrder() {
        val held = TaskState.Held(GateReason("paused", "Wait a moment"))
        val all = listOf(
            run("run", running("x")), run("held", held), run("ask", TaskState.WaitingInput("?")), run("ok", confirm),
            run("need", takeover, Takeover.Stage.Waiting), run("hand", TaskState.WaitingTakeover("login", "Log in"), Takeover.Stage.HandedOver),
        )
        val expected = listOf("hand", "need", "ok", "ask", "held", "run")
        for (i in expected.indices) {
            val left = all.filter { it.key in expected.drop(i) }
            assertEquals(expected[i], shown(CapsuleModel().update(inputs(*left.toTypedArray()), 0)).runKey)
        }
    }

    @Test fun handedOverBarSaysWhoIsWaitingBehind() {
        val v = shown(model.update(inputs(
            run("a", takeover, Takeover.Stage.HandedOver), run("b", TaskState.WaitingTakeover("login", "Log in"), Takeover.Stage.Waiting),
        ), 0))
        assertEquals(CapsuleKind.HandedOver, v.kind)
        assertEquals("After this, 1 more is waiting for you", v.detail)
    }

    // ----------Pause at least 1.5 seconds for each sentence ----------

    @Test fun sentenceStaysAtLeastMinimumAndLatestWins() {
        assertEquals("one", shown(model.update(inputs(run("a", running("one"))), 0)).text)
        assertEquals("one", shown(model.update(inputs(run("a", running("two"))), 500)).text)
        val v = shown(model.update(inputs(run("a", running("three"))), 900))
        assertEquals("one", v.text)
        assertEquals(CapsuleModel.MIN_SENTENCE_MS, model.nextChangeAt)
        //Then replace it with the latest sentence at that time,The sentence in the middle does not appear
        assertEquals("three", shown(model.update(inputs(run("a", running("three"))), CapsuleModel.MIN_SENTENCE_MS)).text)
        assertNull(model.nextChangeAt)
    }

    @Test fun stateChangeSwitchesAtOnce() {
        model.update(inputs(run("a", running("one"))), 0)
        val v = shown(model.update(inputs(run("a", TaskState.WaitingInput("Which one do you want?"))), 100))
        assertEquals("Mina asks: Which one do you want?", v.text)
    }

    // ----------Only widen but not narrow ----------

    @Test fun widthEpochHoldsWithinStateAndRenewsOnChange() {
        val first = shown(model.update(inputs(run("a", running("one"))), 0)).widthEpoch
        assertEquals(first, shown(model.update(inputs(run("a", running("a longer sentence"))), 2_000)).widthEpoch)
        assertEquals(first, shown(model.update(inputs(run("a", running("short"))), 4_000)).widthEpoch)
        val need = shown(model.update(inputs(run("a", TaskState.WaitingInput("?"))), 5_000)).widthEpoch
        assertNotEquals(first, need)
        val back = shown(model.update(inputs(run("a", running("one"))), 6_000)).widthEpoch
        assertNotEquals(need, back)
        //Change one thing and reset the width
        assertNotEquals(back, shown(model.update(inputs(run("b", running("one"))), 7_000)).widthEpoch)
    }

    // ----------One is still unfolding ----------

    @Test fun runningRestsAsBallAndStepsNeverExpandIt() {
        val first = shown(model.update(inputs(run("a", running("Searching for toothpaste"))), 0))
        assertEquals(CapsuleKind.Running to false, first.kind to first.pill)
        //Changed several steps:Still one
        for ((t, said) in listOf(2_000L to "Comparing prices", 4_000L to "Looking at shipping costs", 6_000L to "Placing an order")) {
            assertTrue(!shown(model.update(inputs(run("a", running(said))), t)).pill)
        }
        assertTrue(!shown(model.update(inputs(run("a", TaskState.Held(GateReason("paused", "Wait a moment")))), 7_000)).pill)
    }

    @Test fun severalRunsCountOnTheBall() {
        val v = shown(model.update(inputs(run("a", running("x")), run("b", running("y")), run("c", TaskState.Held(GateReason("paused")))), 0))
        assertEquals(3, v.count)
        assertTrue(!v.pill)
    }

    @Test fun needYouStaysAPillUntilTheCardIsOpened() {
        model.update(inputs(run("a", running("x"))), 0)
        val need = shown(model.update(inputs(run("a", confirm)), 100))
        assertEquals(CapsuleKind.NeedConfirm to true, need.kind to need.pill)
        //Will not retract on its own,I don’t plan the next change either.
        assertNull(model.nextChangeAt)
        assertTrue(shown(model.update(inputs(run("a", confirm)), 600_000)).pill)
        //Opened the card and read it,After being folded, there is one(Still orange)
        model.update(inputs(run("a", confirm), cardOpen = true), 600_100)
        val ball = shown(model.update(inputs(run("a", confirm)), 600_200))
        assertEquals(CapsuleKind.NeedConfirm to false, ball.kind to ball.pill)
    }

    @Test fun aNewNeedExpandsAgain() {
        model.update(inputs(run("a", confirm)), 0)
        model.update(inputs(run("a", confirm), cardOpen = true), 10)
        assertTrue(!shown(model.update(inputs(run("a", confirm)), 20)).pill)
        //The same thing is replaced by another wait:Expand
        val ask = TaskState.WaitingInput("Which color do you want?")
        assertTrue(shown(model.update(inputs(run("a", ask)), 30)).pill)
        model.update(inputs(run("a", ask), cardOpen = true), 40)
        assertTrue(!shown(model.update(inputs(run("a", ask)), 50)).pill)
        //I want another one from you too:Expand,Write "2 things I need you to deal with"
        val two = shown(model.update(inputs(run("a", ask), run("b", takeover, Takeover.Stage.Waiting)), 60))
        assertEquals("2 need you · 2 in total" to true, two.text to two.pill)
    }

    @Test fun aNeedThatComesBackAfterBeingResolvedExpandsAgain() {
        model.update(inputs(run("a", confirm)), 0)
        model.update(inputs(run("a", confirm), cardOpen = true), 10)
        model.update(inputs(run("a", running("x"))), 20)
        assertTrue(shown(model.update(inputs(run("a", confirm)), 30)).pill)
    }

    // ----------After finishing ----------

    @Test fun doneExpandsThenBallWithBadgeThenGone() {
        model.update(inputs(run("a", running("x"))), 0)
        val done = shown(model.update(inputs(run("a", TaskState.Succeeded("alright"))), 1_000))
        assertEquals(CapsuleKind.Done to true, done.kind to done.pill)
        assertEquals("c-a", done.conversationId)
        assertEquals(1_000 + CapsuleModel.PILL_MS, model.nextChangeAt)
        //The task has been removed from the list,The model remembers itself;retract one,It's still done(corner mark)
        val ball = shown(model.update(inputs(), 1_000 + CapsuleModel.PILL_MS))
        assertEquals(CapsuleKind.Done to false, ball.kind to ball.pill)
        assertEquals(1_000 + CapsuleModel.LINGER_MS, model.nextChangeAt)
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 1_000 + CapsuleModel.LINGER_MS))
    }

    @Test fun afterLingerAlwaysModeReturnsToIdle() {
        model.update(inputs(run("a", TaskState.Failed("not found", FailKind.GaveUp)), mode = CapsuleMode.Always), 0)
        assertEquals(CapsuleKind.Idle, shown(model.update(inputs(mode = CapsuleMode.Always), CapsuleModel.LINGER_MS)).kind)
    }

    @Test fun endedRunStaysGoneThoughStillListed() {
        //The task list has not changed anymore,The caller keeps listing the stopped task
        val a = run("a", TaskState.Cancelled)
        assertTrue(shown(model.update(inputs(a), 0)).pill)
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), CapsuleModel.LINGER_MS))
        //Enter Mina and come out:Do not expand "Stopped" again
        assertEquals(CapsuleView.Hidden, model.update(inputs(a, foreground = true), CapsuleModel.LINGER_MS + 1_000))
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), CapsuleModel.LINGER_MS + 2_000))
        assertNull(model.nextChangeAt)
    }

    @Test fun endedWhileHiddenCountsItsTimeToo() {
        //It ended in Mina and it took 10 seconds to return to other App:Never appear again
        val a = run("a", TaskState.Succeeded("alright"))
        assertEquals(CapsuleView.Hidden, model.update(inputs(a, foreground = true), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), 60_000))
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), 60_100))
        //It ends the same when the screen is turned off or locked.
        val b = run("b", TaskState.Cancelled)
        assertEquals(CapsuleView.Hidden, model.update(inputs(b, interactive = false), 100_000))
        assertEquals(CapsuleView.Hidden, model.update(inputs(b), 200_000))
        assertEquals(CapsuleView.Hidden, model.update(inputs(b), 200_100))
    }

    @Test fun failedAndCancelledHaveTheirLines() {
        assertEquals(CapsuleKind.Failed, shown(CapsuleModel().update(inputs(run("a", TaskState.Failed("x", FailKind.Model))), 0)).kind)
        val stopped = shown(CapsuleModel().update(inputs(run("a", TaskState.Cancelled)), 0))
        assertEquals(CapsuleKind.Cancelled to "Stopped", stopped.kind to stopped.text)
    }

    @Test fun activeTaskOutranksLingeringDone() {
        model.update(inputs(run("a", TaskState.Succeeded("alright"))), 0)
        val v = shown(model.update(inputs(run("b", running("Watching the weather"))), 100))
        assertEquals(CapsuleKind.Running, v.kind)
        assertEquals("b", v.runKey)
        //The finished ones are not included in the "total N items"
        assertEquals(1, v.count)
    }

    // ----------When the card is expanded ----------

    @Test fun openCardStopsTheClockForEndedRuns() {
        model.update(inputs(run("a", running("x"))), 0)
        model.update(inputs(run("a", TaskState.Succeeded("alright")), cardOpen = true), 1_000)
        //unfolding:It doesn’t disappear even after a long time,I don’t plan the next change either.
        val v = shown(model.update(inputs(cardOpen = true), 100_000))
        assertEquals(CapsuleKind.Done, v.kind)
        assertNull(model.nextChangeAt)
        assertEquals(listOf("a"), model.cardRuns().map { it.run.key })
        //After retracting:Saw it,It's just a star with a corner mark,Then walk the rest of the time
        val after = shown(model.update(inputs(), 200_000))
        assertEquals(CapsuleKind.Done to false, after.kind to after.pill)
        assertEquals(200_000 + CapsuleModel.LINGER_MS, model.nextChangeAt)
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 200_000 + CapsuleModel.LINGER_MS))
    }

    @Test fun openCardKeepsAlreadyLingeringRunsUntilClosed() {
        model.update(inputs(run("a", TaskState.Succeeded("alright"))), 0)
        model.update(inputs(cardOpen = true), 5_000)
        assertEquals(1, model.cardRuns().size)
        model.update(inputs(cardOpen = true), 1_000_000)
        assertEquals(1, model.cardRuns().size)
        //The time that has passed before expansion will be counted.:After being put away, only LINGER_MS - 5_000
        assertTrue(!shown(model.update(inputs(), 1_000_000)).pill)
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 1_000_000 + CapsuleModel.LINGER_MS - 5_000))
    }

    @Test fun idleIconOpensTheIdleCardAfterAResultHasGone() {
        val a = run("a", TaskState.Cancelled)
        model.update(inputs(a, mode = CapsuleMode.Always), 0)
        assertEquals(CapsuleKind.Idle, shown(model.update(inputs(a, mode = CapsuleMode.Always), CapsuleModel.LINGER_MS)).kind)
        //The caller clicks the idle icon while the stopped task is still listed.:It's a free card,Not that mission
        val v = shown(model.update(inputs(a, mode = CapsuleMode.Always, cardOpen = true), CapsuleModel.LINGER_MS + 60_000))
        assertEquals(CapsuleKind.Idle, v.kind)
        val idle = CardView.Chat("c-b", title = "Book tickets")
        assertEquals(idle, CapsuleCards.of(model.cardRuns(), idle = idle))
    }

    @Test fun deletedConversationsEndedRunsLeaveAtOnce() {
        val a = run("a", TaskState.Cancelled)
        val a2 = CapsuleRun("a2", "c-a", TaskState.Succeeded("alright"))
        val b = run("b", TaskState.Succeeded("alright"))
        val r = run("r", running(""))
        model.update(inputs(a, a2, b, r), 0)
        //Remove all items that have ended in this conversation.
        model.forget("c-a")
        //Ongoing business as usual
        model.forget("c-r")
        //The caller is still listed and has not returned.
        model.update(inputs(a, a2, b, r), 100)
        assertEquals(listOf("r", "b"), model.cardRuns().map { it.run.key })
        model.forget("c-b")
        //It was still in progress when I deleted it.,After that, it ends and displays as usual.(Delete the conversation and wait until it is finished,The round it ends up re-establishing the conversation)
        val v = shown(model.update(inputs(a, a2, b, run("r", TaskState.Cancelled)), 200))
        assertEquals(CapsuleKind.Cancelled to "c-r", v.kind to v.conversationId)
        assertEquals(listOf("r"), model.cardRuns().map { it.run.key })
    }

    @Test fun deletedConversationLeavesTheOpenCardToo() {
        val a = run("a", TaskState.Cancelled)
        model.update(inputs(a), 0)
        model.update(inputs(a, cardOpen = true), 1_000)
        //(stop watch)Delete when:Take it off immediately,I won’t come back even after I put it away.
        model.forget("c-a")
        assertTrue(model.cardRuns().isEmpty())
        model.update(inputs(a, cardOpen = true), 1_100)
        assertTrue(model.cardRuns().isEmpty())
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), 5_000))
        assertNull(model.nextChangeAt)
    }

    @Test fun cardRunsActiveByPriorityThenEndedNewestFirst() {
        model.update(inputs(run("old", TaskState.Succeeded("a")), run("r", running(""))), 0)
        model.update(inputs(run("new", TaskState.Failed("x", FailKind.Model)), run("r", running("")), run("q", TaskState.WaitingInput("Which one do you want?"))), 10)
        val rows = model.cardRuns()
        assertEquals(listOf("q", "r", "new", "old"), rows.map { it.run.key })
        assertEquals(CapsuleKind.NeedAnswer to "Mina asks: Which one do you want?", rows[0].kind to rows[0].line)
        assertEquals(0L, rows[1].startedAt)
        assertNull(rows[0].startedAt)
        assertEquals(CapsuleKind.Failed, rows[2].kind)
    }

    @Test fun cardRunsKnowTakeoverQueueOrder() {
        model.update(inputs(run("a", takeover, Takeover.Stage.Waiting)), 0)
        model.update(inputs(run("a", takeover, Takeover.Stage.Waiting), run("b", takeover, Takeover.Stage.Waiting)), 10)
        val rows = model.cardRuns().associateBy { it.run.key }
        assertEquals(0 to 1, rows.getValue("a").ahead to rows.getValue("a").behind)
        assertEquals(1 to 0, rows.getValue("b").ahead to rows.getValue("b").behind)
        //I handed back the previous one.:Those at the back go to the front
        model.update(inputs(run("a", running("")), run("b", takeover, Takeover.Stage.Waiting)), 20)
        assertEquals(0, model.cardRuns().first { it.run.key == "b" }.ahead)
    }

    // ----------Take over queue ----------

    private fun queued(key: String, ahead: Int, behind: Int, stage: Takeover.Stage = Takeover.Stage.Waiting) =
        run(key, takeover, stage).copy(queueAhead = ahead, queueBehind = behind)

    @Test fun realQueueOrderBeatsFirstSeen() {
        //a appears first,:Let’s talk about capsules and cards first b
        model.update(inputs(queued("a", 1, 0)), 0)
        val v = shown(model.update(inputs(queued("a", 1, 0), queued("b", 0, 1)), 10))
        assertEquals("b", v.runKey)
        val rows = model.cardRuns()
        assertEquals(listOf("b", "a"), rows.map { it.run.key })
        assertEquals(0 to 1, rows[0].ahead to rows[0].behind)
        assertEquals(1 to 0, rows[1].ahead to rows[1].behind)
    }

    @Test fun barCountsWhoIsBehindInTheRealQueue() {
        val v = shown(model.update(inputs(queued("a", 0, 2, Takeover.Stage.HandedOver), queued("b", 1, 1)), 0))
        assertEquals("After this, 2 more are waiting for you", v.detail)
    }

    @Test fun afterHandBackTheNextOneNeedsYouAtOnce() {
        model.update(inputs(queued("a", 0, 1, Takeover.Stage.HandedOver), queued("b", 1, 0)), 0)
        //a hand it over and continue doing it:b to the front,Become "Need You" Now(throttling without waiting for another sentence)
        val v = shown(model.update(inputs(run("a", running("")), queued("b", 0, 0)), 100))
        assertEquals(CapsuleKind.NeedTakeover to "b", v.kind to v.runKey)
        assertEquals(0, model.cardRuns().first { it.run.key == "b" }.ahead)
    }

    // ----------translucent ----------

    @Test fun ballFadesAfterIdleButNeverWhenNeedingYouOrExpanded() {
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.Running, 0, pill = false, idleFor = CapsuleFade.IDLE_MS - 1))
        assertEquals(CapsuleFade.BALL, CapsuleFade.alpha(CapsuleKind.Running, 0, pill = false, idleFor = CapsuleFade.IDLE_MS))
        assertEquals(CapsuleFade.BALL, CapsuleFade.alpha(CapsuleKind.Done, 0, pill = false, idleFor = 60_000))
        //:Not light
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.Done, 0, pill = true, idleFor = 60_000))
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.NeedConfirm, 1, pill = false, idleFor = 60_000))
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.Running, 1, pill = false, idleFor = 60_000))
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.HandedOver, 0, pill = false, idleFor = 60_000))
    }

    // ----------read screen ----------

    @Test fun ballDescribesItsStatus() {
        assertEquals("Mina is working: Searching for toothpaste · 2 in total, tap for details", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Running, "Searching for toothpaste · 2 in total", count = 2)))
        assertEquals("Ask for your consent: send, tap to handle", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.NeedConfirm, "Ask for your consent: send")))
        assertEquals("Mina: Wait first: Queuing up, tap for details", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Held, "Wait first: Queuing up")))
        assertEquals("Mina Done · Click to see the results", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Done, "Done · Click to see the results")))
        assertEquals(CapsuleCopy.IDLE, CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Idle, "")))
    }
}
