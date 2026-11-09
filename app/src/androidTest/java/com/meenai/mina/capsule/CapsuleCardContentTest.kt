package com.meenai.mina.capsule

import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performTouchInput
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.confirm.ConfirmationCopy
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.ui.theme.MinaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleCardContentTest {

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = object : CardActions {
        override fun collapse() { calls += "collapse" }
        override fun stop(runKey: String) { calls += "stop $runKey" }
        override fun takeOver(runKey: String) { calls += "takeOver $runKey" }
        override fun resume(runKey: String) { calls += "resume $runKey" }
        override fun answer(runKey: String, text: String) { calls += "answer $runKey $text" }
        override fun approve(runKey: String, requestKey: Int) { calls += "approve $runKey $requestKey" }
        override fun decline(runKey: String, requestKey: Int) { calls += "decline $runKey $requestKey" }
        override fun openMina(conversationId: String) { calls += "open $conversationId" }
        override fun zoom(on: Boolean) { calls += "zoom $on" }
        override fun openInput() { calls += "openInput" }
        override fun draft(text: String) { calls += "draft $text" }
        override fun send() { calls += "send" }
        override fun freshChat(fresh: Boolean) { calls += "fresh $fresh" }
        override fun loadEarlier() { calls += "earlier" }
        override fun captureScreen() { calls += "capture" }
        override fun dropScreen() { calls += "drop" }
    }

    private fun run(key: String, state: TaskState, kind: CapsuleKind, line: String = "", startedAt: Long? = null, ahead: Int = 0) =
        CardRun(CapsuleRun(key, "c-$key", state, goal = "Buy two toothpastes"), kind, line, startedAt, ahead)

    private fun detail(r: CardRun) = CardView.Detail(r, CapsuleCards.content(r))

    private fun show(card: CardView, preview: Bitmap? = null, zoomed: Boolean = false, thread: ChatThread? = null, input: CardInputUi = CardInputUi()) {
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) {
                androidx.compose.runtime.CompositionLocalProvider(com.meenai.mina.ui.LocalScreenShots provides shots) {
                    CapsuleCard(card, preview, zoomed, actions, { if (it == null) null else "0:23" }, thread = thread, height = 420.dp, input = input)
                }
            }
        }
    }

    /**fake screen storage:s1 is a screen of the settings page,s2 is a page that does not allow screenshots. */
    private val at = java.time.LocalDateTime.of(2026, 10, 28, 19, 42).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val shots = com.meenai.mina.ui.ScreenShots { _, id ->
        when (id) {
            "s1" -> com.meenai.mina.ui.ShownScreen(com.meenai.mina.core.userscreen.UserScreen("s1", at, "com.android.settings", "set up"),
                Bitmap.createBitmap(90, 200, Bitmap.Config.ARGB_8888).asImageBitmap())
            "s2" -> com.meenai.mina.ui.ShownScreen(com.meenai.mina.core.userscreen.UserScreen("s2", at, "com.example.bank", "bank", blocked = true), null)
            else -> null
        }
    }

    private fun idleThread(vararg userTexts: String) = CapsuleThreads.build(
        userTexts.mapIndexed { i, t -> PastTurn(com.meenai.mina.core.agent.Turn(t, "", "succeeded", runId = "r$i")) },
        CapsuleThreads.PAGE, emptyList(),
    )

    @Test fun threadShowsScreenMessageWithTagAndWords() {
        show(CardView.Chat("c1", title = "t"), thread = idleThread("[[screen:s1]]", "[[screen:s1]]\?", "[[screen:s2]]"))
        val tag = "Your current screen · Settings · 19:42"
        //The card stops at the latest few sentences:()And the screen that doesn’t let you take screenshots is in front of you,The first screen is above, it hasn’t been released yet.
        compose.waitUntil(5_000) { compose.onAllNodesWithText(tag, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.onAllNodesWithTag("screen-thumb", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Are there any pitfalls in Article 3??", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(com.meenai.mina.ui.ScreenCopy.BLOCKED, useUnmergedTree = true).assertIsDisplayed()
        //
        compose.onAllNodesWithText("[[screen:", substring = true, useUnmergedTree = true).assertCountEquals(0)
        //Scroll up to the first sentence:Only sent the screen,
        compose.onNodeWithTag("capsule-thread").performScrollToIndex(0)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(tag, useUnmergedTree = true)[0].assertIsDisplayed()
        compose.onAllNodesWithText("[[screen:", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun cameraButtonLeftOfInputCapturesScreen() {
        show(CardView.Chat("c1", title = "t"), thread = idleThread())
        compose.onNodeWithTag("capsule-camera").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("capture"), calls) }
        val camera = compose.onNodeWithTag("capsule-camera").fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithTag("capsule-input").fetchSemanticsNode().boundsInRoot
        assertTrue("The screenshot button is on the left side of the input box", camera.right <= field.left)
    }

    @Test fun attachmentChipShowsScreenAndCanBeSentOrRemoved() {
        val screen = com.meenai.mina.core.userscreen.UserScreen("", at, "com.android.settings", "set up")
        val pending = PendingScreen(com.meenai.mina.core.userscreen.CapturedScreen(byteArrayOf(1), screen),
            Bitmap.createBitmap(90, 200, Bitmap.Config.ARGB_8888).asImageBitmap())
        show(CardView.Chat("c1", title = "t"), thread = idleThread(), input = CardInputUi(screen = pending))
        compose.onNodeWithTag("capsule-attachment").assertIsDisplayed()
        compose.onNodeWithText(com.meenai.mina.ui.ScreenCopy.TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(" 19:42", useUnmergedTree = true).assertIsDisplayed()
        //You can send it directly with only screenshots and no writing.
        compose.onNodeWithTag("capsule-send").performClick()
        compose.onNodeWithTag("capsule-attachment-remove").performClick()
        compose.runOnIdle { assertEquals(listOf("send", "drop"), calls) }
    }

    @Test fun blockedChipSaysNotCaptured() {
        val screen = com.meenai.mina.core.userscreen.UserScreen("", at, "com.example.bank", "bank", blocked = true)
        show(CardView.Chat("c1", title = "t"), thread = idleThread(),
            input = CardInputUi(screen = PendingScreen(com.meenai.mina.core.userscreen.CapturedScreen(null, screen), null)))
        compose.onNodeWithText(com.meenai.mina.ui.ScreenCopy.BLOCKED, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun chat(r: CardRun) = CardView.Chat(r.run.conversationId, r, CapsuleCards.content(r))

    /** [n]Q&A round,Answers for each round [lines]OK. */
    private fun history(n: Int, lines: Int = 1) = CapsuleThreads.build(
        (1..n).map { i -> PastTurn(com.meenai.mina.core.agent.Turn("question $i", (1..lines).joinToString("\n") { "answer $ith $it line" }, "succeeded", runId = "r$i")) },
        CapsuleThreads.PAGE, emptyList(),
    )

    @Test fun runningShowsStepsElapsedStopAndLink() {
        show(detail(run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "Searching for toothpaste", startedAt = 0L)))
        compose.onNodeWithText("Buy two toothpastes").assertIsDisplayed()
        compose.onNodeWithText("Working · 0:23").assertIsDisplayed()
        compose.onNodeWithText("Searching for toothpaste").assertIsDisplayed()
        //(),
        compose.onAllNodesWithText(CardCopy.STOP).assertCountEquals(0)
        compose.onNodeWithContentDescription(CardCopy.STOP).performClick()
        compose.onNodeWithText(CardCopy.SEE_PROCESS).performClick()
        compose.onNodeWithContentDescription(CardCopy.COLLAPSE).performClick()
        assertEquals(listOf("stop a", "open c-a", "collapse"), calls)
    }

    @Test fun previewShowsAndZooms() {
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        show(detail(run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "")), preview = frame)
        compose.onNodeWithContentDescription(CardCopy.SCREEN).performClick()
        assertEquals(listOf("zoom true"), calls)
    }

    @Test fun zoomedClosesOnTap() {
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        show(detail(run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "")), preview = frame, zoomed = true)
        compose.onNodeWithTag("capsule-zoom").performClick()
        assertEquals(listOf("zoom false"), calls)
    }

    @Test fun noPreviewWithoutFrames() {
        show(detail(run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "")))
        compose.onAllNodesWithTag("capsule-screen").assertCountEquals(0)
    }

    @Test fun takeoverHasTakeOverAndStop() {
        show(detail(run("a", TaskState.WaitingTakeover("payment", "Please enter payment password"), CapsuleKind.NeedTakeover, "Needed from you: Payment")))
        compose.onNodeWithText("Please enter payment password").assertIsDisplayed()
        compose.onNodeWithText(CardCopy.TAKE_OVER).performClick()
        compose.onNodeWithText(CardCopy.STOP).performClick()
        assertEquals(listOf("takeOver a", "stop a"), calls)
    }

    @Test fun queuedTakeoverCannotTakeOverYet() {
        show(detail(run("a", TaskState.WaitingTakeover("login", "Please log in"), CapsuleKind.NeedTakeover, "Requires you: Login", ahead = 1)))
        compose.onNodeWithText(CapsuleCards.QUEUED_AHEAD).assertIsDisplayed()
        compose.onAllNodesWithTag("capsule-card").assertCountEquals(1)
        compose.onNodeWithText(CardCopy.TAKE_OVER).assertDoesNotExist()
    }

    //Only operations that cost money require consent:The only words on the card are "I only agree this time" and "I don't agree."
    @Test fun confirmOffersOnceOrDecline() {
        val r = run("a", TaskState.Idle, CapsuleKind.NeedConfirm)
        show(CardView.Detail(r, CardContent.Confirm("I want your consent:Place an order", "Amount:¥19.9", 9)))
        compose.onNodeWithText(ConfirmationCopy.NOTE).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.APPROVE_ONCE_ONLY).performClick()
        compose.onNodeWithText(CardCopy.DECLINE).performClick()
        assertEquals(listOf("approve a 9", "decline a 9"), calls)
    }

    @Test fun questionOptionsAnswer() {
        show(detail(run("a", TaskState.WaitingInput("Which one do you want?", listOf("big", "small")), CapsuleKind.NeedAnswer)))
        compose.onNodeWithText("small").performClick()
        assertEquals(listOf("answera small"), calls)
    }

    @Test fun questionInputOpensOnTap() {
        show(detail(run("a", TaskState.WaitingInput("What time does it leave?"), CapsuleKind.NeedAnswer)))
        compose.onNodeWithTag("capsule-input").performClick()
        assertEquals(listOf("openInput"), calls)
    }

    @Test fun openInputTypesAndSends() {
        var ui by mutableStateOf(CardInputUi(open = true))
        val typing = object : CardActions by actions {
            override fun draft(text: String) { ui = ui.copy(draft = text) }
        }
        val card = detail(run("a", TaskState.Running(1, "n"), CapsuleKind.Running, ""))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, null, false, typing, { null }, input = ui) }
        }
        compose.onNodeWithTag("capsule-field").performTextInput("Change to mint flavor")
        compose.onNodeWithContentDescription(CardCopy.SEND).performClick()
        assertEquals("Change to mint flavor", ui.draft)
        assertEquals(listOf("send"), calls)
    }

    @Test fun noInputWhileTakeoverOrConfirm() {
        show(detail(run("a", TaskState.WaitingTakeover("login", "Please log in"), CapsuleKind.NeedTakeover, "Requires you: Login")))
        compose.onAllNodesWithTag("capsule-input").assertCountEquals(0)
    }

    @Test fun idleCardContinuesOrStartsFresh() {
        show(CardView.Chat("c1", title = "buy toothpaste"), thread = history(1))
        compose.onNodeWithText("Then talk about "buying toothpaste"").assertIsDisplayed()
        compose.onNodeWithText("Answer 1 line 1").assertIsDisplayed()
        compose.onNodeWithText(CapsuleCards.HINT_START).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.NEW_CHAT).performClick()
        assertEquals(listOf("fresh true"), calls)
    }

    @Test fun heldResumesOnlyWhenResumable() {
        show(detail(run("a", TaskState.Held(com.meenai.mina.core.agent.GateReason("x", "Waiting for you to open positioning", resumable = true)), CapsuleKind.Held)))
        compose.onNodeWithText("Waiting for you to open positioning").assertIsDisplayed()
        compose.onNodeWithText(CardCopy.RESUME).performClick()
        assertEquals(listOf("resume a"), calls)
    }

    @Test fun finishedRunIsAChatCardWithItsConversation() {
        val r = run("a", TaskState.Succeeded("Ordered toothpaste"), CapsuleKind.Done)
        val past = listOf(PastTurn(com.meenai.mina.core.agent.Turn("morning", "Good morning", "succeeded", runId = "r0")))
        val thread = CapsuleThreads.build(past, CapsuleThreads.PAGE, listOf(r)) { listOf("Shopping") }
        show(chat(r), thread = thread)
        //The previous back and forth, the line where the app was operated, and the full text of the answer are all there.;No steps and small pictures
        //The title of the conversation is the first sentence "Morning",The bubble below is also "morning":both places
        compose.onAllNodesWithText("morning").assertCountEquals(2)
        compose.onNodeWithText("✓Shopping· Done").assertIsDisplayed()
        compose.onNodeWithText("Ordered toothpaste").assertIsDisplayed()
        compose.onAllNodesWithTag("capsule-screen").assertCountEquals(0)
        compose.onNodeWithText(CapsuleCards.HINT_CONTINUE).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.BACK_TO_MINA).performClick()
        assertEquals(listOf("open c-a"), calls)
    }

    @Test fun chatInputOpensOnTapAndStreamingChatHasStop() {
        val r = run("a", TaskState.Running(1, "n"), CapsuleKind.Running)
        show(chat(r), thread = CapsuleThreads.build(emptyList(), CapsuleThreads.PAGE, listOf(r)))
        compose.onNodeWithTag("capsule-input").performClick()
        compose.onNodeWithContentDescription(CardCopy.STOP).performClick()
        assertEquals(listOf("openInput", "stop a"), calls)
    }

    @Test fun chatQuestionKeepsItsOptionsPinned() {
        val r = run("a", TaskState.WaitingInput("Which one do you want?", listOf("big", "small")), CapsuleKind.NeedAnswer)
        show(chat(r), thread = CapsuleThreads.build(emptyList(), CapsuleThreads.PAGE, listOf(r)))
        compose.onNodeWithText("Which one do you want?").assertIsDisplayed()
        compose.onNodeWithText("small").performClick()
        assertEquals(listOf("answera small"), calls)
    }

    @Test fun chatOpensAtTheLatestQuestionAndScrollsUpForEarlier() {
        show(CardView.Chat("c1", title = "question 1"), thread = history(25, lines = 3))
        //Start reading from the latest question when opening
        compose.onNodeWithText("question 25").assertIsDisplayed()
        compose.onNodeWithText("question 20").assertIsNotDisplayed()
        repeat(30) { compose.onNodeWithTag("capsule-thread").performTouchInput { swipeDown() } }
        compose.onNodeWithText("question 16").assertIsDisplayed()
        //Scroll to the top:Load more forward
        compose.runOnIdle { assertTrue("earlier" in calls) }
    }

    @Test fun longTakeoverScrollsWhileButtonsStay() {
        val body = (1..60).joinToString("\n") { "No. $it line" }
        show(detail(run("a", TaskState.WaitingTakeover("login", body), CapsuleKind.NeedTakeover, "Requires you: Login")))
        //The entire description is a text node:Judging by height and position - the scroll area is shorter than the full text,Swipe the text up and follow it up,Button doesn't move
        val area = compose.onNodeWithTag("capsule-scroll").fetchSemanticsNode()
        val text = compose.onNodeWithText("Line 1", substring = true).fetchSemanticsNode()
        org.junit.Assert.assertTrue("capped: ${area.size.height} < ${text.size.height}", area.size.height < text.size.height)
        val before = text.positionInRoot.y
        repeat(3) { compose.onNodeWithTag("capsule-scroll").performTouchInput { swipeUp() } }
        val after = compose.onNodeWithText("Line 1", substring = true).fetchSemanticsNode().positionInRoot.y
        org.junit.Assert.assertTrue("scrolled: $after < $before", after < before)
        compose.onNodeWithText(CardCopy.TAKE_OVER).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.SEE_PROCESS).assertIsDisplayed()
    }

    @Test fun chatCardIsFixedWhateverTheContent() {
        var card by mutableStateOf<CardView>(chat(run("b", TaskState.Succeeded("alright"), CapsuleKind.Done)))
        var thread by mutableStateOf(history(1))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, null, false, actions, { null }, thread = thread, height = 420.dp) }
        }
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
        //No matter how long I go back and forth, it’s still this high,roll inside
        thread = history(10, lines = 8)
        compose.waitForIdle()
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
        //The same goes for idle cards
        card = CardView.Chat(null)
        thread = ChatThread(emptyList())
        compose.waitForIdle()
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
    }

    @Test fun taskCardWrapsItsContentAndTheChatCardDoesNot() {
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        val r = run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "")
        var card by mutableStateOf<CardView>(CardView.Detail(r, CardContent.Working(listOf("Open shopping", "Search toothpaste", "Choose the first one"))))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, frame, false, actions, { null }, thread = history(1), height = 420.dp) }
        }
        val task = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().size.height
        val screen = compose.onNodeWithTag("capsule-screen").fetchSemanticsNode().size.height
        val full = with(compose.density) { 420.dp.roundToPx() }
        //Add three steps to small screen:Shorter than conversation cards,Leave no space below,The middle section is at least the entire small picture.
        assertTrue("wraps: $task < $full", task < full)
        assertTrue("fits the preview: $task > $screen", task > screen)
        compose.onNodeWithTag("capsule-screen").assertIsDisplayed()
        compose.onNodeWithText("Choose the first one").assertIsDisplayed()
        //Change to conversation cards:fixed height
        card = chat(run("a", TaskState.Succeeded("alright"), CapsuleKind.Done))
        compose.waitForIdle()
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
    }

    @Test fun longTaskCardIsCappedAndScrollsWhileButtonsStay() {
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        val body = (1..60).joinToString("\n") { "No. $it line" }
        show(detail(run("a", TaskState.WaitingTakeover("login", body), CapsuleKind.NeedTakeover, "Requires you: Login")), preview = frame)
        //No matter how long the description is,Task cards can be at most as high as dialogue cards,The text rolls inside,Buttons and links are still visible
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
        val area = compose.onNodeWithTag("capsule-scroll").fetchSemanticsNode()
        val text = compose.onNodeWithText("Line 1", substring = true).fetchSemanticsNode()
        assertTrue("capped: ${area.size.height} < ${text.size.height}", area.size.height < text.size.height)
        compose.onNodeWithTag("capsule-screen").assertIsDisplayed()
        compose.onNodeWithText(CardCopy.TAKE_OVER).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.SEE_PROCESS).assertIsDisplayed()
    }

    /**Don’t draw it in the list(Already got out, not lined up to come in)Not even visible. */
    private fun offScreen(text: String) {
        val nodes = compose.onAllNodesWithText(text)
        if (nodes.fetchSemanticsNodes().isNotEmpty()) nodes[0].assertIsNotDisplayed()
    }

    private fun working(n: Int) = CardView.Detail(
        run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, ""), CardContent.Working((1..n).map { "No. $it step" }),
    )

    @Test fun manyStepsFitThePreviewHeightNewestAtTheBottom() {
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        var card by mutableStateOf<CardView>(working(30))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, frame, false, actions, { null }, height = 420.dp) }
        }
        val steps = compose.onNodeWithTag("capsule-steps").fetchSemanticsNode().size.height
        val screen = compose.onNodeWithTag("capsule-screen").fetchSemanticsNode().size.height
        val cardHeight = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().size.height
        //No matter how many steps there are, they are still as high as the small screen,The card is not stretched
        assertTrue("steps fit the preview: $steps <= $screen", steps <= screen + 1)
        assertTrue("card not stretched: $cardHeight < ${with(compose.density) { 420.dp.roundToPx() }}", cardHeight < with(compose.density) { 420.dp.roundToPx() })
        compose.onNodeWithText("Step 30").assertIsDisplayed()
        offScreen("Step 1")
        //At the bottom comes a new step:follow it
        card = working(31)
        compose.waitForIdle()
        compose.onNodeWithText("Step 31").assertIsDisplayed()
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(with(compose.density) { cardHeight.toDp() })
        //Scroll up to the earliest step
        repeat(10) { compose.onNodeWithTag("capsule-steps").performTouchInput { swipeDown() } }
        compose.onNodeWithText("Step 1").assertIsDisplayed()
        //As I was flipping through the pages, a new step came:Don't pull it back to the bottom
        card = working(32)
        compose.waitForIdle()
        compose.onNodeWithText("Step 1").assertIsDisplayed()
        offScreen("Step 32")
    }

    @Test fun stepsWithoutPreviewAreCappedAndFewStaysCompact() {
        var card by mutableStateOf<CardView>(working(2))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, null, false, actions, { null }, height = 420.dp) }
        }
        val few = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().size.height
        compose.onNodeWithText("Step 1").assertIsDisplayed()
        card = working(40)
        compose.waitForIdle()
        val many = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().size.height
        val full = with(compose.density) { 420.dp.roundToPx() }
        //When there are few steps, press the content;If there are too many, it will only reach the height of the small screen calculated according to the aspect ratio of the main screen.,Not reaching the highest level
        assertTrue("few is compact: $few < $many", few < many)
        assertTrue("many is capped: $many < $full", many < full)
        compose.onNodeWithText("Step 40").assertIsDisplayed()
        offScreen("Step 1")
    }

    @Test fun chatAnswerRendersMarkdownInsideTheCard() {
        val answer = "##result\n\n**toothpaste**Order placed\n\n|commodity |quantity |unit price |Subtotal |Remark |\n|---|---|---|---|---|\n|Mint flavored toothpaste extra large family size | 2 | ¥9.95 | ¥19.9 |Delivered to your doorstep tomorrow |\n\n[Order details](https://example.com/order)"
        val past = listOf(PastTurn(com.meenai.mina.core.agent.Turn("buy toothpaste", answer, "succeeded", runId = "r1")))
        show(CardView.Chat("c1", title = "buy toothpaste"), thread = CapsuleThreads.build(past, CapsuleThreads.PAGE, emptyList()))
        //The mark is no longer exposed as it is
        compose.onNodeWithText("result").assertExists()
        compose.onNodeWithText("Toothpaste has been ordered").assertExists()
        compose.onNodeWithText("Mint flavored toothpaste extra large family size").assertExists()
        compose.onAllNodesWithText("|", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("**", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Order details", substring = true).assertCountEquals(1)
        //For wide tables, slide left and right in your section.,The card does not widen
        val card = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().boundsInRoot
        val thread = compose.onNodeWithTag("capsule-thread").fetchSemanticsNode().boundsInRoot
        assertTrue("thread inside card: $thread in $card", thread.right <= card.right && thread.left >= card.left)
    }

    @Test fun headerReportsItsHandleAndDraggingItTapsNothing() {
        var handle: android.graphics.Rect? = null
        val card = detail(run("a", TaskState.Running(1, "n"), CapsuleKind.Running, ""))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) {
                CapsuleCard(card, null, false, actions, { null }, height = 420.dp, onHandle = { handle = it })
            }
        }
        compose.runOnIdle { assertTrue("header bounds reported", handle?.isEmpty == false) }
        //Drag on title bar(Including dragging from the collapse button):No folding, no clicking of any buttons
        compose.onNodeWithTag("capsule-header").performTouchInput { swipeDown() }
        compose.onNodeWithContentDescription(CardCopy.COLLAPSE).performTouchInput { swipe(center, center + Offset(0f, 300f)) }
        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }
}
