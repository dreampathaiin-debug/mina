package com.meenai.mina.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.cap.web.KeptPage
import com.meenai.mina.capsule.CapsuleCard
import com.meenai.mina.capsule.CapsuleContent
import com.meenai.mina.capsule.CapsuleKind
import com.meenai.mina.capsule.CapsuleView
import com.meenai.mina.capsule.CardActions
import com.meenai.mina.capsule.CardView
import com.meenai.mina.ui.theme.MinaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**The page left running in the background is on the interface:line of dialogue, capsule, unfolded card(Live view, remaining time, source dialogue, two buttons). */
@RunWith(AndroidJUnit4::class)
class KeptPageUiTest {

    @get:Rule val compose = createComposeRule()

    private val page = KeptPage(1, "c1", "r1", "Open this course,Start playing from episode 3,Don't close it", "course.example.com", null, "Section 3",
        System.currentTimeMillis(), System.currentTimeMillis() + 2 * 3_600_000L - 30_000)
    private val calls = mutableListOf<String>()
    private val actions = object : CardActions {
        override fun collapse() { calls += "collapse" }
        override fun stop(runKey: String) {}
        override fun takeOver(runKey: String) {}
        override fun resume(runKey: String) {}
        override fun answer(runKey: String, text: String) {}
        override fun approve(runKey: String, requestKey: Int) {}
        override fun decline(runKey: String, requestKey: Int) {}
        override fun openMina(conversationId: String) { calls += "open $conversationId" }
        override fun zoom(on: Boolean) { calls += "zoom $on" }
        override fun openInput() {}
        override fun draft(text: String) {}
        override fun send() {}
        override fun freshChat(fresh: Boolean) {}
        override fun loadEarlier() {}
        override fun captureScreen() {}
        override fun dropScreen() {}
        override fun closeKeptPage() { calls += "closeKept" }
    }

    @Test fun chatRowShowsHostTimeAndCloses() {
        var closed = 0
        compose.setContent { MinaTheme(dark = false, reduceMotion = true) { KeptLine(page) { closed++ } } }
        compose.onNodeWithText("This page remains running in the background").assertIsDisplayed()
        compose.onNodeWithText("course.example.com · Automatically shut down after 2 hours").assertIsDisplayed()
        compose.onNodeWithTag("kept-close").performClick()
        assertEquals(1, closed)
    }

    @Test fun capsuleSaysBackgroundPageRunning() {
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) {
                CapsuleContent(CapsuleView.Shown(CapsuleKind.KeptPage, "The background web page is running", conversationId = "c1", pill = true), onTap = {}, onStop = {}, onHandBack = {})
            }
        }
        compose.onNodeWithText("The background web page is running").assertIsDisplayed()
        compose.onNodeWithTag("capsule-live-dot", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun cardShowsLiveViewTimeSourceAndButtons() {
        val frame = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) {
                CapsuleCard(CardView.Kept(page), frame, zoomed = false, actions = actions, elapsed = { null }, height = 520.dp)
            }
        }
        compose.onNodeWithText("Backend web page").assertIsDisplayed()
        compose.onNodeWithText("2 hours left").assertIsDisplayed()
        compose.onNodeWithTag("kept-live").assertIsDisplayed()
        compose.onNodeWithText("course.example.com").assertIsDisplayed()
        compose.onNodeWithText("From the conversation "Click on this course", substring = true).assertIsDisplayed()
        compose.onNodeWithText("real time picture", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Open conversation").performClick()
        compose.onNodeWithTag("kept-close").performClick()
        assertEquals(listOf("open c1", "closeKept"), calls)
    }
}
