package com.meenai.mina.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.ui.theme.MinaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**Attachments to conversation pages:「＋"How to draw the panel switches, the photos in the panel, the row above the input box to be sent, and the messages to be sent. */
@RunWith(AndroidJUnit4::class)
class ChatAttachmentsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /**fake picture: 40×Empty picture of 30,Doesn’t read the phone. */
    private val images = AttachImages { _, _ -> ImageBitmap(40, 30) }

    private fun composer(attachments: List<PendingAttachment> = emptyList(), text: String = "", onSend: () -> Unit = {}) = compose.setContent {
        MinaTheme {
            CompositionLocalProvider(LocalAttachImages provides images) {
                var t by remember { mutableStateOf(text) }
                Box(Modifier.fillMaxSize()) {
                    Composer(
                        Modifier, t, { t = it }, ComposerMode.Idle, hasHistory = false, onSend = onSend, onStop = {},
                        attachPanel = { Box(Modifier.fillMaxSize().testTag("panel-body")) },
                        attachments = attachments,
                    )
                }
            }
        }
    }

    private fun waitGone(tag: String) = compose.waitUntil(3_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    @Test fun plusOpensThePanelAndTurnsIntoClose() {
        composer()
        compose.onAllNodesWithTag("panel-body").assertCountEquals(0)
        compose.onNodeWithContentDescription(AttachCopy.ADD).performClick()
        compose.onNodeWithTag("panel-body").assertIsDisplayed()
        //Order again( ×)
        compose.onNodeWithContentDescription(AttachCopy.CLOSE).performClick()
        waitGone("panel-body")
        compose.onNodeWithContentDescription(AttachCopy.ADD).assertIsDisplayed()
    }

    @Test fun tappingTheFieldClosesThePanel() {
        composer()
        compose.onNodeWithContentDescription(AttachCopy.ADD).performClick()
        compose.onNodeWithTag("panel-body").assertIsDisplayed()
        compose.onNodeWithTag("composer-field").performClick()
        waitGone("panel-body")
    }

    @Test fun backClosesThePanelFirst() {
        composer()
        compose.onNodeWithContentDescription(AttachCopy.ADD).performClick()
        compose.onNodeWithTag("panel-body").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitGone("panel-body")
        assertEquals(false, compose.activity.isFinishing)
    }

    @Test fun trayShowsPhotosAndFileCardsAndAttachmentsAloneCanBeSent() {
        var sent = 0
        composer(
            listOf(
                PendingAttachment("content://m/1", "a.jpg", 1000),
                PendingAttachment("content://m/2", "b.png", 1000),
                PendingAttachment("content://f/1", "rental contract.pdf", 1200 * 1024),
            ),
            onSend = { sent++ },
        )
        compose.onAllNodesWithTag("tray-photo").assertCountEquals(2)
        compose.onAllNodesWithTag("tray-file").assertCountEquals(1)
        compose.onNodeWithText("rental contract.pdf").assertIsDisplayed()
        compose.onNodeWithText("1.2 MB").assertIsDisplayed()
        compose.onNodeWithContentDescription(AttachCopy.remove("a.jpg")).assertIsDisplayed()
        compose.onNodeWithContentDescription("").assertIsEnabled().performClick()
        assertEquals(1, sent)
    }

    @Test fun sendIsOffWithNothingToSend() {
        composer()
        compose.onNodeWithContentDescription("").assertIsNotEnabled()
    }

    @Test fun removingFromTheTrayCallsBack() {
        val removed = mutableListOf<String>()
        compose.setContent {
            MinaTheme {
                CompositionLocalProvider(LocalAttachImages provides images) {
                    PendingTray(listOf(PendingAttachment("content://f/9", "a.pdf", 10)), { removed += it })
                }
            }
        }
        compose.onNodeWithContentDescription(AttachCopy.remove("a.pdf")).performClick()
        assertEquals(listOf("content://f/9"), removed)
    }

    private fun panel(access: PhotoAccess, recent: List<PendingAttachment>?, selected: List<PendingAttachment> = emptyList(), onGrant: () -> Unit = {}) =
        compose.setContent {
            MinaTheme {
                CompositionLocalProvider(LocalAttachImages provides images) {
                    AttachPanel(access, recent, selected, {}, {}, {}, {}, onGrant)
                }
            }
        }

    @Test fun panelWithoutPhotoAccessExplainsAndOffersToAllow() {
        var granted = 0
        panel(PhotoAccess.None, emptyList(), onGrant = { granted++ })
        compose.onNodeWithText(AttachCopy.CAMERA).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.ALBUM).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.FILES).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.NEED_ACCESS).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.GRANT).performClick()
        assertEquals(1, granted)
        compose.onAllNodesWithTag("recent-photo").assertCountEquals(0)
    }

    @Test fun panelListsRecentPhotosWithNumberedSelection() {
        val recent = (1..6).map { PendingAttachment("content://m/$it", "p$it.jpg", 1, photo = true) }
        panel(PhotoAccess.Full, recent, selected = listOf(recent[4], recent[1]))
        compose.onNodeWithText(AttachCopy.RECENT).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.ALL).assertIsDisplayed()
        compose.onAllNodesWithTag("recent-photo").assertCountEquals(6)
        compose.onAllNodes(hasTestTag("recent-photo") and isSelected()).assertCountEquals(2)
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
    }

    @Test fun partialAccessOffersToChangeTheSelection() {
        panel(PhotoAccess.Partial, listOf(PendingAttachment("content://m/1", "p.jpg", 1, photo = true)))
        compose.onNodeWithText(AttachCopy.RECENT_PARTIAL).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.CHANGE).assertIsDisplayed()
    }

    private fun bubble(text: String) = compose.setContent {
        MinaTheme {
            CompositionLocalProvider(LocalAttachImages provides images) {
                Box(Modifier.width(360.dp)) { UserBubble(text, "c-test") }
            }
        }
    }

    @Test fun sentMessageShowsPhotosFilesAndWordsButNoPaths() {
        bubble(
            "Look at these for me\nAttachments:/workspace/inbox/a.jpg(1 KB)\nAttachments:/workspace/inbox/b.jpg(1 KB)\n" +
                "appendix:/workspace/inbox/c.webp(1 KB)\nAttachments:/workspace/inbox/rental contract (1).pdf(48 KB)",
        )
        compose.onAllNodesWithTag("sent-photo").assertCountEquals(3)
        compose.onAllNodesWithTag("sent-file").assertCountEquals(1)
        compose.onNodeWithText("rental contract (1).pdf").assertIsDisplayed()
        compose.onNodeWithText("48 KB").assertIsDisplayed()
        compose.onNodeWithText("Look at these for me").assertIsDisplayed()
        compose.onAllNodesWithText("/workspace", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("appendix:", substring = true).assertCountEquals(0)
    }

    @Test fun singlePhotoAloneHasNoWordsBubble() {
        bubble("appendix:/workspace/inbox/a.jpg(1 KB)")
        compose.onAllNodesWithTag("sent-photo").assertCountEquals(1)
        compose.onAllNodesWithText("appendix", substring = true).assertCountEquals(0)
    }

    @Test fun plainMessageIsUnchanged() {
        bubble("Remind me to bring an umbrella at 8 o'clock tomorrow morning")
        compose.onNodeWithText("Remind me to bring an umbrella at 8 o'clock tomorrow morning").assertIsDisplayed()
        compose.onAllNodesWithTag("sent-photo").assertCountEquals(0)
        compose.onAllNodesWithTag("sent-file").assertCountEquals(0)
    }
}
