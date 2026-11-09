package com.meenai.mina.ui

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.chat.DeleteImpact
import com.meenai.mina.core.agent.QuoteMessage
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 *(Pin, rename, delete,Click outside to close):;After renaming, the list will be replaced by the new name.;Pin the top row to the top.
 *There is also a long press on the conversation page to answer, and the user's words will pop up to copy. /Quote /Select text:Can be selected after selecting text,The quote goes out with the next sentence. A task environment that does not require a real machine.
 */
@RunWith(AndroidJUnit4::class)
class ChatDeleteAndSelectTest {

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ConversationStore(File(context.cacheDir, "chat-delete-test-" + UUID.randomUUID()))
    private val removed = mutableListOf<String>()
    private val started = mutableListOf<String>()

    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) { started += goal }
        override fun cancel() {}
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) {}
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
    }

    private fun vm(impact: DeleteImpact = DeleteImpact(runningTask = false, schedules = 0)) = ChatViewModel(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions, store,
        deletionImpact = { impact },
        removeConversation = { removed += it; store.delete(it) },
    )

    private fun show(vm: ChatViewModel, drawerOpen: Boolean) {
        compose.setContent {
            ChatWithDrawer(
                vm, Prerequisites.Check(emptyList()), reduceMotion = true,
                conversations = { store.list() }, conversationChanges = store.changes, conversationTitle = store::title,
                onPlans = {}, onMemory = {}, onBrowser = {}, onSettings = {}, reopen = drawerOpen,
            ) { open ->
                ChatScreen(vm, Prerequisites.Check(emptyList()), MutableStateFlow(null), MutableStateFlow(false), reduceMotion = true,
                    onOpenDrawer = open, openSettings = {})
            }
        }
        compose.waitForIdle()
    }

    @Test fun longPressARowAsksThenDeletes() {
        store.append("a", Turn("Check the weather", "", "succeeded"))
        store.setTitle("a", "")
        store.append("b", Turn("Book tickets", "alright", "succeeded"))
        val vm = vm(DeleteImpact(runningTask = true, schedules = 2))
        show(vm, drawerOpen = true)

        compose.onNodeWithText("").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-delete").performClick()
        compose.onNodeWithText("Delete this conversation?").assertExists()
        val body = compose.onNodeWithTag("delete-conversation-body").fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        assertTrue(body, "" in body && "stop" in body && "2" in body)

        //Cancel:delete nothing
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(removed.isEmpty())

        compose.onNodeWithText("").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-delete").performClick()
        compose.onNodeWithTag("confirm-delete-conversation").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { removed.isNotEmpty() }
        assertEquals(listOf("a"), removed)
        assertEquals(0, compose.onAllNodesWithText("").fetchSemanticsNodes().size)
        compose.onNodeWithText("Book tickets").assertExists()
    }

    /**Long press to pop up the menu in place:the raised row(Still the same title)and three items: pin, rename, delete;Just opening the menu changes nothing. */
    @Test fun longPressShowsTheThreeActions() {
        store.append("a", Turn("Check the weather", "", "succeeded"))
        store.setTitle("a", "")
        show(vm(), drawerOpen = true)

        compose.onNodeWithText("").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu").assertExists()
        compose.onNodeWithTag("drawer-menu-pin").assert(hasText("pin to top"))
        compose.onNodeWithTag("drawer-menu-rename").assert(hasText("Change name"))
        compose.onNodeWithTag("drawer-menu-delete").assert(hasText("delete"))
        compose.onNodeWithText("Unpin").assertDoesNotExist()
        //When the menu is open, the content behind it is not accessible.,Only the lifted line remains
        assertEquals(1, compose.onAllNodesWithText("").fetchSemanticsNodes().size)
        assertEquals("", store.title("a"))
        assertEquals(null, store.pinnedAt("a"))
    }

    /**Order outside the menuCollapse menu,Don't change anything;The row in the sidebar is still there. */
    @Test fun tappingOutsideClosesTheMenu() {
        store.append("a", Turn("Check the weather", "", "succeeded"))
        store.setTitle("a", "")
        show(vm(), drawerOpen = true)

        compose.onNodeWithText("").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-pin").assertExists()
        //lower right corner:The menu is on the left below that line,out of reach
        compose.onNodeWithTag("drawer-menu").performTouchInput { click(percentOffset(0.95f, 0.95f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("drawer-menu").assertDoesNotExist()
        compose.onNodeWithTag("drawer-menu-pin").assertDoesNotExist()
        compose.onNodeWithText("").assertExists()
        assertTrue(removed.isEmpty())
        assertEquals("", store.title("a"))
        assertEquals(null, store.pinnedAt("a"))
    }

    /**Change name:The input box is the current title,Replace with new save;Change the name of the list on the spot,What is saved is also new. */
    @Test fun renameUpdatesTheRow() {
        store.append("a", Turn("Check the weather", "", "succeeded"))
        store.setTitle("a", "")
        show(vm(), drawerOpen = true)

        compose.onNodeWithText("").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-rename").performClick()
        compose.onNodeWithTag("rename-conversation-field").assert(hasText(""))
        compose.onNodeWithTag("rename-conversation-field").performTextReplacement("   ")
        compose.onNodeWithTag("confirm-rename-conversation").assertIsNotEnabled()
        compose.onNodeWithTag("rename-conversation-field").performTextReplacement("Travel in Shanghai")
        compose.onNodeWithTag("confirm-rename-conversation").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Travel in Shanghai").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("").fetchSemanticsNodes().size)
        assertEquals("Travel in Shanghai", store.title("a"))
        //The title I came up with later will not be overwritten.
        assertTrue(!store.setTitle("a", ""))
    }

    /**pin to top:Arrange to the top of the "Top" section, with a thumbtack;Then unpin it and return to "Recent". */
    @Test fun pinMovesTheRowToTheTop() {
        store.append("a", Turn("Check the weather", "", "succeeded"))
        store.setTitle("a", "")
        store.append("b", Turn("Book tickets", "alright", "succeeded"))
        show(vm(), drawerOpen = true)
        compose.onAllNodesWithTag("conversation-pin", useUnmergedTree = true).assertCountEquals(0)

        compose.onNodeWithText("").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-pin").assert(hasText("pin to top")).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-pin", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("pin to top").assertExists()
        compose.onAllNodesWithTag("conversation-row")[0].assert(hasText(""))
        assertTrue(store.pinnedAt("a") != null)

        compose.onNodeWithText("").performTouchInput { longClick() }
        compose.onNodeWithTag("drawer-menu-pin").assert(hasText("Unpin")).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("conversation-pin", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("pin to top").assertDoesNotExist()
        assertEquals(null, store.pinnedAt("a"))
    }

    //Long press the message to pop up the menu;After selecting "Select Text", the text will be returned to the system's long-press selection.,The sidebar does not open
    @Test fun answerAndUserTextAreSelectableFromTheMenu() {
        store.append("s", Turn("How is the weather today?", "Shanghai today****,See details [](https://example.com)", "succeeded"))
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("s") }
        show(vm, drawerOpen = false)

        compose.onNode(hasText("Shanghai today", substring = true) and hasAnyAncestor(hasTestTag("selectable-answer"))).assertDoesNotExist()
        compose.onNodeWithText("Shanghai today", substring = true).performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-select").assert(hasText("Select text")).performClick()
        compose.waitForIdle()
        val answer = compose.onNode(hasText("Shanghai today", substring = true) and hasAnyAncestor(hasTestTag("selectable-answer")))
        answer.assertExists()
        answer.performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("message-menu").assertDoesNotExist()
        compose.onNodeWithTag("drawer").assertIsNotDisplayed()

        compose.onNodeWithText("How is the weather today?").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-select").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("How is the weather today?") and hasAnyAncestor(hasTestTag("selectable-user"))).assertExists()
    }

    //Long press the answer and select "Quote":A reference bar appears above the input box,Write a sentence and send it out,Quote and follow this sentence to give the task,The strip disappears;The line above the bubble shows the quote
    @Test fun longPressQuoteThenSend() {
        store.append("q", Turn("How is the weather today?", "Shanghai today****", "succeeded"))
        val vm = vm()
        compose.runOnUiThread { vm.openConversation("q") }
        show(vm, drawerOpen = false)

        compose.onNodeWithText("It’s sunny in Shanghai today").performTouchInput { longClick() }
        compose.onNodeWithTag("message-menu-copy").assert(hasText("copy"))
        compose.onNodeWithTag("message-menu-quote").assert(hasText("Quote")).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("message-menu").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("quote-chip").assertExists()

        compose.onNodeWithTag("composer-field").performTextReplacement("What about tomorrow?")
        compose.onNodeWithContentDescription("").performClick()
        compose.waitForIdle()
        assertEquals(listOf("> [Quoting your earlier reply]\n>It’s sunny in Shanghai today\n\nWhat about tomorrow?"), started)
        compose.onNodeWithTag("quote-chip").assertDoesNotExist()
        compose.onNodeWithTag("sent-quote").assertExists()

        // ×Remove quotes
        compose.runOnUiThread { vm.quote(QuoteMessage.From.You, "How is the weather today?") }
        compose.onNodeWithContentDescription("Remove quotes").performClick()
        compose.onNodeWithTag("quote-chip").assertDoesNotExist()
    }
}
