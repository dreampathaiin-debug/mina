package com.meenai.mina.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.ui.theme.MinaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**The "Backstage Screen" page:Empty state, one screen per line, click to open the small screen(Just use fake data,Do not build a real screen). */
@RunWith(AndroidJUnit4::class)
class ScreensScreenTest {

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(rows: List<ScreenRow>) = compose.setContent {
        MinaTheme {
            ScreensContent(rows, onBack = { calls += "back" }, onOpenConversation = { calls += "open $it" })
        }
    }

    private fun row(displayId: Int, key: String = "k$displayId") =
        ScreenRow(key, "conv-$key", "Open settings and check the battery", displayId, TaskState.Running(1, "Clicked"), foreground = "set up")

    @Test fun emptyPageSaysNoTaskIsRunning() {
        show(emptyList())
        compose.onNodeWithText(ScreensCopy.EMPTY).assertIsDisplayed()
    }

    /**One task, one card:What you are doing, which app you are using, and your status are all there;Click on that line to return to the conversation it belongs to. */
    @Test fun oneCardPerTaskOpensItsConversation() {
        show(listOf(row(12), row(-1, "queued")))
        compose.onNodeWithText("Using settings").assertIsDisplayed()
        compose.onNodeWithText("Not opened yet App").assertIsDisplayed()
        compose.onAllNodesWithText("Open settings and check the battery").assertCountEquals(2)
        compose.onAllNodesWithText("Executing").assertCountEquals(2)
        compose.onNodeWithText("Using settings").performClick()
        assertEquals(listOf("open conv-k12"), calls)
    }

    /**There is no "viewing screen" in the area where the screen has not yet been built.:No screen to see,Don't give an entry that doesn't respond after clicking. */
    @Test fun aScreenThatIsNotBuiltYetHasNoPreview() {
        show(listOf(row(12), row(-1, "queued")))
        compose.onNodeWithTag("screens-preview-queued").assertDoesNotExist()
        compose.onNodeWithTag("screens-preview-k12").performScrollTo().assertIsDisplayed()
    }

    /**Click to expand and then to collapse:Change the words on the button accordingly. */
    @Test fun previewToggles() {
        show(listOf(row(12)))
        compose.onNodeWithTag("screens-preview-k12").performScrollTo().performClick()
        compose.onNodeWithText(ScreensCopy.HIDE_PREVIEW).assertExists()
        compose.onNodeWithTag("screens-preview-k12").performClick()
        compose.onNodeWithText(ScreensCopy.PREVIEW).assertExists()
        assertEquals(emptyList<String>(), calls)
    }
}
