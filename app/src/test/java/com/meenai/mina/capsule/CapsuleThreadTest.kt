package com.meenai.mina.capsule

import org.junit.Rule
import com.meenai.mina.i18n.ChineseTexts
import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.task.SteerNote
import com.meenai.mina.task.SteerState
import com.meenai.mina.ui.Exchange
import com.meenai.mina.ui.ExchangeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleThreadTest {
    @get:Rule val texts = ChineseTexts()

    private fun turn(user: String, outcome: String, status: String = "succeeded", runId: String? = null, steers: List<String> = emptyList()) =
        Turn(user, outcome, status, runId = runId, steers = steers)

    private fun live(key: String, state: TaskState, kind: CapsuleKind, runId: String? = null, draft: String? = null, line: String = "") = CardRun(
        CapsuleRun(key, "c1", state, goal = "question$key", runId = runId,
            exchange = Exchange("question$key", emptyList(), ExchangeStatus.Working, live = true, draft = draft)),
        kind, line,
    )

    private fun texts(t: ChatThread) = t.items.map {
        when (it) {
            is ThreadItem.User -> "you:AZZ"
            is ThreadItem.Note -> "Note:AZZ"
            is ThreadItem.Answer -> if (it.cut) "break:AZZ" else "answer:AZZ"
        }
    }

    // ----------How to arrange a round ----------

    @Test fun chatTurnIsBubbleAndFullAnswer() {
        //Leave the answer as is Markdown,Layout in card
        val t = CapsuleThreads.build(listOf(PastTurn(turn("Are you there", "#exist\n\n**Something happened**please say\n\n|item |value |\n|---|---|\n| a | 1 |\n"))), 10, emptyList())
        assertEquals(listOf("you:Are you there", "answer:#exist\n\n**Something happened**please say\n\n|item |value |\n|---|---|\n| a | 1 |"), texts(t))
        assertEquals("Are you there", t.title)
    }

    @Test fun appTurnCollapsesToOneLineThenItsAnswer() {
        val past = listOf(
            PastTurn(turn("buy toothpaste", "Placed an order, total ¥19.9", runId = "r1"), places = listOf("Shopping")),
            PastTurn(turn("Check ticket", "No votes left", status = "failed", runId = "r2"), places = listOf("12306", "", "map")),
            PastTurn(turn("send message", "Canceled", status = "cancelled"), places = listOf("chat")),
        )
        assertEquals(listOf(
            "you:buy toothpaste", "Note:✓Shopping· Done", "answer:Placed an order, total ¥19.9",
            "you:Check ticket", "Note:12306, browser, etc. · Failed", "answer:No votes left",
            "you:send message", "Note:Chat · Stopped",
        ), texts(CapsuleThreads.build(past, 10, emptyList())))
    }

    @Test fun plainChatFailuresSayItAndSteersAreBubbles() {
        val past = listOf(
            PastTurn(turn("Do the math", "The model did not respond", status = "failed")),
            PastTurn(turn("write a poem", "Canceled", status = "cancelled", steers = listOf("Shorter"))),
        )
        assertEquals(listOf("you:Do the math", "Note:Didn't work", "answer:The model did not respond", "you:write a poem", "you:Shorter", "Note:Stopped"), texts(CapsuleThreads.build(past, 10, emptyList())))
    }

    //The round that stopped:Below the line "Stopped" is the explanation;When the explanation is still being written, it is "I am sorting out what I have done..."
    @Test fun stoppedTurnShowsItsSummaryUnderTheNote() {
        val past = listOf(
            PastTurn(turn("See settings", "I finished reading Internet and Bluetooth before stopping.;Haven't checked the display and sound yet.", status = "cancelled", runId = "r1"), places = listOf("set up")),
            PastTurn(turn("write a poem", "Canceled", status = "cancelled", runId = "r2")),
        )
        assertEquals(listOf(
            "you:See settings", "Note:Settings · Stopped", "answer:I finished reading Internet and Bluetooth before stopping.;Haven't checked the display and sound yet.",
            "you:write a poem", "Note:Stopped", "Note:Sorting out what I have done...",
        ), texts(CapsuleThreads.build(past, 10, emptyList(), summarizing = setOf("r2"))))
        //Can't write:Leave only that line
        assertEquals(listOf("you:write a poem", "Note:Stopped"), texts(CapsuleThreads.build(past.drop(1), 10, emptyList())))
        //The same goes for the one that hasn't been included in the dialogue yet.
        val r = live("a", TaskState.Cancelled, CapsuleKind.Cancelled, runId = "r3")
        assertEquals(listOf("you:questiona", "Note:Stopped", "Note:Sorting out what I have done..."),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(r), summarizing = setOf("r3"))))
    }

    @Test fun oldEvidenceSectionIsDropped() {
        val t = CapsuleThreads.build(listOf(PastTurn(turn("What time does the door open?", "Doors open at nine o'clock\nBased on: official website"))), 10, emptyList())
        assertEquals(listOf("you:What time does the door open?", "answer:Doors open at nine o'clock"), texts(t))
    }

    @Test fun placesAreAppsAndSitesInOrder() {
        fun act(target: String?) = RunEvent.Activity(0, 1, "act", "tap", target)
        val acts = listOf(act("com.shop.app"), act("website example.com"), act("com.shop.app"), act("ls -l"), act(null), act("/workspace/a.txt"))
        assertEquals(listOf("Shopping", "example.com"), CapsuleThreads.places(acts) { if (it == "com.shop.app") "Shopping" else it })
    }

    // ----------the one in progress ----------

    @Test fun streamingAnswerGrowsAfterTheQuestion() {
        val r = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, runId = "ra", draft = "**today**")
        val t = CapsuleThreads.build(listOf(PastTurn(turn("morning", "Good morning", runId = "r0"))), 10, listOf(r))
        assertEquals(listOf("you:morning", "answer:Good morning", "you:questiona", "answer:**today**"), texts(t))
        assertTrue((t.items.last() as ThreadItem.Answer).streaming)
        //Haven't written yet:Tell me what you are doing at the moment
        val thinking = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, line = "Thinking about the next step")
        assertEquals(listOf("you:questiona", "Note:Thinking about the next step"), texts(CapsuleThreads.build(emptyList(), 10, listOf(thinking))))
    }

    @Test fun liveWaitsShowTheQuestionAndAppLine() {
        val ask = live("a", TaskState.WaitingInput("Big or small?"), CapsuleKind.NeedAnswer)
        assertEquals(listOf("you:questiona", "answer:Big or small?"), texts(CapsuleThreads.build(emptyList(), 10, listOf(ask))))
        val inApp = live("b", TaskState.Running(2, "n"), CapsuleKind.Running, draft = "look in")
        assertEquals(listOf("you:questionb", "Note:", "answer:look in"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(inApp)) { listOf("Shopping") }))
        val done = live("c", TaskState.Succeeded("alright"), CapsuleKind.Done)
        assertEquals(listOf("you:questionc", "Note:✓Shopping· Done", "answer:alright"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(done)) { listOf("Shopping") }))
    }

    @Test fun liveTailSkipsWhatIsAlreadyRecorded() {
        val recorded = live("a", TaskState.Succeeded("alright"), CapsuleKind.Done, runId = "r1")
        val notYet = live("b", TaskState.Failed("x", FailKind.Model), CapsuleKind.Failed, runId = "r2")
        val queuedCancel = live("c", TaskState.Cancelled, CapsuleKind.Cancelled)
        val going = live("d", TaskState.Running(1, "n"), CapsuleKind.Running)
        val other = live("e", TaskState.Running(1, "n"), CapsuleKind.Running).let { it.copy(run = it.run.copy(conversationId = "c2")) }
        //;The one who ends first in the round trip is on top.,
        val tail = CapsuleThreads.liveTail(listOf(going, other, notYet, recorded, queuedCancel), "c1", setOf("r1"))
        assertEquals(listOf("b", "d"), tail.map { it.run.key })
    }

    @Test fun keysStayWhenTheLiveTurnGetsRecorded() {
        val r = live("a", TaskState.Succeeded("alright"), CapsuleKind.Done, runId = "r9")
        val before = CapsuleThreads.build(emptyList(), 10, listOf(r)).items.map { it.key }
        val after = CapsuleThreads.build(listOf(PastTurn(turn("questiona", "alright", runId = "r9"))), 10, emptyList()).items.map { it.key }
        assertEquals(before, after)
    }

    // ----------Where to stop when paging and opening ----------

    @Test fun latestTenTurnsFirstThenEarlier() {
        val past = (1..25).map { PastTurn(turn("$it", "answer$it", runId = "r$it")) }
        val first = CapsuleThreads.build(past, CapsuleThreads.PAGE, emptyList())
        assertTrue(first.hasEarlier)
        assertEquals("you:16", texts(first).first())
        assertEquals("1", first.title)
        val more = CapsuleThreads.build(past, CapsuleThreads.PAGE * 2, emptyList())
        assertEquals("you:6", texts(more).first())
        //The rounds that are added forward are all in front.:The original keys of those items remain unchanged.,
        assertEquals(first.items.map { it.key }, more.items.takeLast(first.items.size).map { it.key })
        val all = CapsuleThreads.build(past, CapsuleThreads.PAGE * 3, emptyList())
        assertTrue(!all.hasEarlier)
        assertEquals(50, all.items.size)
    }

    @Test fun anchorIsTheLatestQuestion() {
        val past = listOf(PastTurn(turn("one", "First")), PastTurn(turn("two", "Second\n very long answer")))
        val t = CapsuleThreads.build(past, 10, emptyList())
        assertEquals(2, t.anchor)
        assertEquals(ThreadItem.User("p1-u0", "two"), t.items[t.anchor])
        //The ongoing question is at the end:Start reading from it
        val going = CapsuleThreads.build(past, 10, listOf(live("a", TaskState.Running(1, "n"), CapsuleKind.Running, draft = "writing")))
        assertEquals("you:questiona", texts(going)[going.anchor])
        assertEquals(0, ChatThread(emptyList()).anchor)
    }

    //If you insert words in progress, they will be followed by the original words.,The destination is written below(The same statement as the conversation page);
    @Test fun liveSteersFollowTheGoalWithWhereTheyWent() {
        val base = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, draft = "writing")
        val run = base.copy(run = base.run.copy(steers = listOf(
            SteerNote("s1", "Change to the day after tomorrow", SteerState.Joined),
            SteerNote("s2", "", SteerState.Received),
        )))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        val users = t.items.filterIsInstance<ThreadItem.User>()
        assertEquals(listOf("questiona", "Change to the day after tomorrow", ""), users.map { it.text })
        assertEquals(listOf(null, "Joined current task", "Received, next step processing"), users.map { it.note })
        assertEquals("you:", texts(t)[t.anchor])
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
    }

    @Test fun withdrawnSteersAreNotShown() {
        val base = live("a", TaskState.Running(1, "n"), CapsuleKind.Running)
        val run = base.copy(run = base.run.copy(steers = listOf(
            SteerNote("s1", "If it has been withdrawn", SteerState.Withdrawn),
            SteerNote("s2", "words left", SteerState.Received),
        )))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        assertEquals(listOf("questiona", "words left"), t.items.filterIsInstance<ThreadItem.User>().map { it.text })
    }

    //The reply interrupted by the interruption is ranked before that interruption.(Same as conversation page);The memorized round is arranged in the same order as the ongoing one.,key is not repeated
    @Test fun interruptedReplySitsAboveItsSteer() {
        val past = listOf(PastTurn(Turn("Check the weather", "It will be cloudy tomorrow", "succeeded", runId = "r1",
            steers = listOf("Change to check tomorrow", "Let's take a look the day after tomorrow"), interruptedReplies = listOf("It's sunny today,Highest", ""))))
        assertEquals(listOf("you:Check the weather", "break:It's sunny today,Highest", "you:Change to check tomorrow", "you:Let's take a look the day after tomorrow", "answer:It will be cloudy tomorrow"),
            texts(CapsuleThreads.build(past, 10, emptyList())))

        val base = live("a", TaskState.Running(2, "n"), CapsuleKind.Running, draft = "tomorrow")
        val run = base.copy(run = base.run.copy(steers = listOf(SteerNote("s1", "Change to check tomorrow", SteerState.Joined, interrupted = "It's sunny today,Highest"))))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        assertEquals(listOf("you:questiona", "break:It's sunny today,Highest", "you:Change to check tomorrow", "answer:tomorrow"), texts(t))
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
        assertEquals("you:Change to check tomorrow", texts(t)[t.anchor])
    }

    //Replies that have been finished before the interruption are ranked before that interruption.,According to the answer;Interjections that have not yet been handed over to the model are listed behind the answers being written.
    @Test fun finishedReplyAndWaitingSteerOrder() {
        val past = listOf(PastTurn(Turn("Check the weather", "It will be cloudy tomorrow", "succeeded", runId = "r1",
            steers = listOf("Change to check tomorrow"), finishedReplies = listOf("It's sunny today,Maximum 25 degrees."))))
        assertEquals(listOf("you:Check the weather", "answer:It's sunny today,Maximum 25 degrees.", "you:Change to check tomorrow", "answer:It will be cloudy tomorrow"),
            texts(CapsuleThreads.build(past, 10, emptyList())))

        val base = live("a", TaskState.Running(2, "n"), CapsuleKind.Running, draft = "More tomorrow")
        val run = base.copy(run = base.run.copy(steers = listOf(
            SteerNote("s1", "Change to check tomorrow", SteerState.Joined, finished = "It's sunny today"),
            SteerNote("s2", "Keep it short", SteerState.Received),
        )))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        assertEquals(listOf("you:questiona", "answer:It's sunny today", "you:Change to check tomorrow", "answer:More tomorrow", "you:Keep it short"), texts(t))
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
        assertEquals("you:Keep it short", texts(t)[t.anchor])
    }
}
