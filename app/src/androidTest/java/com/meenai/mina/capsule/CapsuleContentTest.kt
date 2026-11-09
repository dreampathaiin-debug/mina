package com.meenai.mina.capsule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.ui.theme.MinaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleContentTest {

    @get:Rule val compose = createComposeRule()

    private val clicks = mutableListOf<String>()

    private fun show(view: CapsuleView.Shown) {
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) {
                CapsuleContent(view, onTap = { clicks += "open" }, onStop = { clicks += "stop" }, onHandBack = { clicks += "back" })
            }
        }
    }

    @Test fun runningIsABallWithRingThatSaysWhatItDoesAndOpensOnTap() {
        show(CapsuleView.Shown(CapsuleKind.Running, "Searching for toothpaste", count = 1, runKey = "a", conversationId = "c"))
        compose.onNodeWithContentDescription("Mina is working: searching for toothpaste, click to see details").assertIsDisplayed()
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        //Don’t write that sentence on one
        compose.onNodeWithText("Searching for toothpaste").assertDoesNotExist()
        compose.onNodeWithTag("capsule-ball").performClick()
        assertEquals(listOf("open"), clicks)
    }

    @Test fun eachBallStateShowsItsMarks() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.Running, "", count = 3))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, {}, {}, {}) }
        }
        //several pieces:Write the number of pieces on the corner
        compose.onNodeWithTag("capsule-count", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
        //and other conditions:gray circle,No number of pieces
        view = CapsuleView.Shown(CapsuleKind.Held, "Wait first: Queuing up", count = 1)
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("capsule-count", useUnmergedTree = true).assertDoesNotExist()
        //I want you to deal with what you have seen.:Still one,orange circle
        view = CapsuleView.Shown(CapsuleKind.NeedConfirm, "Ask for your consent: send", count = 1, needCount = 1)
        compose.onNodeWithContentDescription("You need to agree: send, click to process").assertIsDisplayed()
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        //Done, not done, stopped:corner mark,no circle
        for (kind in listOf(CapsuleKind.Done, CapsuleKind.Failed, CapsuleKind.Cancelled)) {
            view = CapsuleView.Shown(kind, "result")
            compose.onNodeWithTag("capsule-badge", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertDoesNotExist()
        }
        //idle:only icon
        view = CapsuleView.Shown(CapsuleKind.Idle, "")
        compose.onNodeWithContentDescription(CapsuleCopy.IDLE).assertIsDisplayed()
        compose.onNodeWithTag("capsule-badge", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun pillShowsItsLineAndOpensOnTap() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.NeedTakeover, "Needed from you: Payment", pill = true))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, { clicks += "open" }, {}, {}) }
        }
        for (v in listOf(
            CapsuleView.Shown(CapsuleKind.NeedTakeover, "Needed from you: Payment", pill = true),
            CapsuleView.Shown(CapsuleKind.NeedConfirm, "Ask for your consent: send", pill = true),
            CapsuleView.Shown(CapsuleKind.NeedAnswer, "Mina asks you: Which one do you want?", pill = true),
            CapsuleView.Shown(CapsuleKind.Done, "Done · Click to see the results", pill = true),
            CapsuleView.Shown(CapsuleKind.Failed, "Failed · Click to see the reason", pill = true),
            CapsuleView.Shown(CapsuleKind.Cancelled, "Stopped", pill = true),
            CapsuleView.Shown(CapsuleKind.NeedAnswer, "1 item for you to handle · 2 items in total", count = 2, needCount = 1, pill = true),
        )) {
            view = v
            compose.onNodeWithText(v.text).assertIsDisplayed()
            compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("capsule").performClick()
        assertEquals(listOf("open"), clicks)
    }

    /**Expand, retract:Change the width of the same base,When retracted, it is as wide as a heart. */
    @Test fun pillShrinksBackToBall() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.Done, "Done · Click to see the results", pill = true))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, {}, {}, {}, side = DockSide.Left) }
        }
        val wide = compose.onNodeWithTag("capsule").getBoundsInRoot().width
        view = view.copy(pill = false)
        compose.waitForIdle()
        val ball = compose.onNodeWithTag("capsule-ball").getBoundsInRoot()
        assertTrue("ball should be narrower: ${ball.width} vs $wide", ball.width < wide)
        assertEquals(ball.height.value, ball.width.value, 1f)
        compose.onNodeWithTag("capsule-badge", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun handedOverBarHasStopAndHandBack() {
        show(CapsuleView.Shown(CapsuleKind.HandedOver, CapsuleModel.HANDED_OVER, detail = "Payment·Click on the right after completion", runKey = "a"))
        compose.onNodeWithTag("capsule-bar").assertIsDisplayed()
        compose.onNodeWithText("Payment·Click on the right after completion").assertIsDisplayed()
        compose.onNodeWithText(CapsuleCopy.STOP).performClick()
        compose.onNodeWithText("Dealt with it").performClick()
        assertEquals(listOf("stop", "back"), clicks)
    }

    /**same paragraph([CapsuleView.Shown.widthEpoch]constant)into short sentences,The capsule does not become narrow;After changing a section, the width was re-set. */
    @Test fun widthOnlyGrowsWithinEpoch() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.NeedAnswer, "Mina asks you: Which of the three stores do you want?", pill = true, widthEpoch = 1))
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, {}, {}, {}) }
        }
        val wide = compose.onNodeWithTag("capsule").getBoundsInRoot().width
        view = view.copy(text = "short")
        compose.onNodeWithTag("capsule").assertWidthIsAtLeast(wide)
        view = view.copy(widthEpoch = 2)
        compose.waitForIdle()
        val narrow = compose.onNodeWithTag("capsule").getBoundsInRoot().width
        assertTrue("new epoch should re-fit: $narrow vs $wide", narrow < wide)
    }

    /**When the stone fades away, the outer ring and corner mark are still there.;You can "move to the other side of the screen" when reading the screen. */
    @Test fun fadedBallKeepsMarksAndOffersSwapSide() {
        var swapped = 0
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) {
                CapsuleContent(
                    CapsuleView.Shown(CapsuleKind.Running, "", count = 2), {}, {}, {},
                    alpha = CapsuleFade.BALL, onSwapSide = { swapped++ },
                )
            }
        }
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("capsule-count", useUnmergedTree = true).assertIsDisplayed()
        val actions = compose.onNodeWithTag("capsule-ball").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == CapsuleCopy.SWAP_SIDE }.action() }
        assertEquals(1, swapped)
    }

    /**When reading the screen, there is an additional "Speak" instead of long press.;Press and hold to speak while the capsule lights up with blue edges. */
    @Test fun talkActionAndHoldingRing() {
        var talked = 0
        compose.setContent {
            MinaTheme(dark = false, reduceMotion = true) {
                CapsuleContent(
                    CapsuleView.Shown(CapsuleKind.Idle, ""), {}, {}, {},
                    holding = true, onTalk = { talked++ }, talkLabel = "say",
                )
            }
        }
        compose.onNodeWithTag("capsule-holding", useUnmergedTree = true).assertIsDisplayed()
        val actions = compose.onNodeWithTag("capsule-ball").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == "say" }.action() }
        assertEquals(1, talked)
    }
}
