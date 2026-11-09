package com.meenai.mina.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.browser.PageAskMode
import com.meenai.mina.browser.UserBrowser
import com.meenai.mina.capsule.CapsuleModel
import com.meenai.mina.capsule.CapsuleRun
import com.meenai.mina.capsule.ChatThread
import com.meenai.mina.capsule.ThreadItem
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.task.TaskGate
import com.meenai.mina.ui.theme.MinaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 *How does the "Ask Mina" section on the browser page look like?:Normal input bar →Half screen panel at work(light edge, step, stop)→After you do something on the page, you will receive a small card,
 *Top post "Continue";Mina asked you as the panel opened on its own. Not running tasks,Give the status directly.
 */
@RunWith(AndroidJUnit4::class)
class PageAskTest {

    @get:Rule val compose = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var chat: PageChat
    private val model = CapsuleModel()

    private fun card(state: TaskState) = model.cardRunOf(CapsuleRun("run-1", "conv-1", state, goal = "Pick the one with the best reviews", onScreen = true))
    private val thread = ChatThread(listOf(ThreadItem.User("u0", "Pick the one with the best reviews"), ThreadItem.Answer("a0", "Okay, let me take a look at this page first.")))

    private fun start() {
        compose.setContent {
            MinaTheme {
                val scope = rememberCoroutineScope()
                chat = remember { PageChat(ctx, UserBrowser.of(ctx), scope) }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { PageAskOverlay(chat, site = "shop.example.com", pageTitle = "Noise canceling headphones search results") }
                    if (chat.mode == PageAskMode.Bar) PageAskBar(chat)
                }
            }
        }
    }

    @Test
    fun barThenPanelThenPausedCard() {
        start()
        compose.onNodeWithTag("page-ask-bar").assertIsDisplayed()
        compose.onNodeWithText(PageAskCopy.HINT).assertIsDisplayed()

        //User asked:Panel opens,Mina is working,bright light all around,To the right of the input box is the stop key
        compose.runOnIdle {
            chat.show("conv-1", card(TaskState.Running(2, "", reason = "Clicking "Add to Cart"")), thread)
            chat.open = true
        }
        compose.onNodeWithTag("page-panel").assertIsDisplayed()
        compose.onNodeWithTag("page-glow").assertExists()
        compose.onNodeWithText(PageAskCopy.FROM_PAGE, substring = true).assertIsDisplayed()
        compose.onNodeWithTag("page-panel-open-chat").assertIsDisplayed()
        compose.onNodeWithText("Okay, let me take a look at this page first.").assertIsDisplayed()
        compose.onNodeWithTag("capsule-stop").assertIsDisplayed()

        //You do it on the page:Harvest small card,Top post "Continue",No more bright edges
        compose.runOnIdle { chat.show("conv-1", card(TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE))), thread) }
        compose.onNodeWithTag("page-card").assertIsDisplayed()
        compose.onNodeWithTag("page-paused-banner").assertIsDisplayed()
        compose.onNodeWithTag("page-card-resume").assertIsDisplayed()
        compose.onNodeWithTag("page-glow").assertDoesNotExist()
        compose.onNodeWithTag("page-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(PageAskMode.Card, chat.mode) }

        //Click on the card to expand back to the panel
        compose.onNodeWithTag("page-card").performClick()
        compose.onNodeWithTag("page-panel").assertIsDisplayed()
        compose.onNodeWithTag("page-panel-collapse").performClick()
        compose.onNodeWithTag("page-card").assertIsDisplayed()
    }

    @Test
    fun questionOpensThePanelAndEndedRunReturnsToTheBar() {
        start()
        compose.runOnIdle { chat.show("conv-1", card(TaskState.Running(1, "")), thread) }
        compose.onNodeWithTag("page-card").assertIsDisplayed()
        compose.runOnIdle { chat.show("conv-1", card(TaskState.WaitingInput("Which color do you want?", listOf("midnight black", "cloud white"))), thread) }
        compose.onNodeWithTag("page-panel").assertIsDisplayed()
        compose.onNodeWithText("midnight black").assertIsDisplayed()
        //It's over, put it away:Return to input bar
        compose.runOnIdle {
            chat.show("conv-1", card(TaskState.Succeeded("Added")), thread)
            chat.open = false
        }
        compose.onNodeWithTag("page-ask-bar").assertIsDisplayed()
    }

    /**Conversations starting on one page in the sidebar:The small label "Web Page" behind the title,The next line is the website;There is no other dialogue. */
    @Test
    fun drawerRowShowsTheWebTagAndSite() {
        compose.setContent {
            MinaTheme {
                Column {
                    ConversationRowContent(DrawerConversation("a", "Pick out noise-canceling headphones", "", false, false, true, site = "shop.example.com"))
                    ConversationRowContent(DrawerConversation("b", "Alarm clock for tomorrow morning", "", false, false, true))
                }
            }
        }
        compose.onNodeWithTag("conversation-web").assertIsDisplayed()
        compose.onNodeWithText(PageAskCopy.WEB).assertIsDisplayed()
        compose.onNodeWithText("shop.example.com").assertIsDisplayed()
        compose.onNodeWithText("Alarm clock for tomorrow morning").assertIsDisplayed()
        compose.onAllNodesWithTag("conversation-web").assertCountEquals(1)
    }
}
