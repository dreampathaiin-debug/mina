package com.meenai.mina.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.R
import com.meenai.mina.i18n.AppLanguage

/**
 *Several steps for interface testing,Does not rely on the state left by the previous test:Each test activity is newly opened,But the window of the previous test(Activities, pop-ups)
 *Exiting takes time. Before clicking, wait for this test point to actually appear on the screen. There is only one,The ones you order won’t be in the old window or haven’t been laid out yet.
 */
internal fun ComposeContentTestRule.awaitOne(text: String, inDialog: Boolean = false, timeoutMs: Long = 5_000): SemanticsNodeInteraction {
    val matcher = if (inDialog) hasText(text) and hasAnyAncestor(isDialog()) else hasText(text)
    waitUntil(timeoutMs) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
    return onNode(matcher, useUnmergedTree = true)
}

/**Buttons in the pop-up window:Wait for the pop-up window to appear before clicking it. */
internal fun ComposeContentTestRule.clickInDialog(text: String) {
    awaitOne(text, inDialog = true).performClick()
    waitForIdle()
}

/**
 *Write in the dialog box input box [text]Click Send again. Send away button's own click action,Do not press the screen coordinate point:When writing, the input box gets the focus and the input method starts to pop up.,
 *The input field follows the keyboard up;Clicking on the coordinate point takes the position during the animation.,Often falls outside the input field - no click to send,
 *It was also regarded as "clicking outside the input area" and the keyboard was put away.,words stay in the box,The mission has not started. The button name is based on the current interface language of the App.
 */
internal fun ComposeContentTestRule.typeAndSend(text: String) {
    onNode(hasSetTextAction()).performTextInput(text)
    onNodeWithContentDescription(appString(R.string.chat_send)).performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
}

/**Text based on the current interface language of the app. */
internal fun appString(id: Int): String =
    AppLanguage.resources(InstrumentationRegistry.getInstrumentation().targetContext).getString(id)
