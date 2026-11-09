package com.meenai.mina.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.voice.SpeechEngineSpec
import com.meenai.mina.voice.SpeechEvent
import com.meenai.mina.voice.SpeechSession
import com.meenai.mina.voice.VoiceSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import com.meenai.mina.voice.SpeechFailure
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**Press and hold the dialogue input field to speak:Long press the input box to bring up the "Listening" card,After releasing, the word will be connected in the input box;When you swipe up to cancel, fill in nothing. Use fake voice source,No microphone required. */
@RunWith(AndroidJUnit4::class)
class VoiceHoldTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /**fake session:Events are fed by tests;Record the number of times you release and cancel. */
    private class FakeSession : SpeechSession {
        val out = Channel<SpeechEvent>(Channel.UNLIMITED)
        override val events: Flow<SpeechEvent> = out.receiveAsFlow()
        override val live = true
        @Volatile var released = 0
        @Volatile var cancelled = 0
        override fun release() { released++ }
        override fun cancel() { cancelled++; out.close() }
    }

    private class FakeVoice : VoiceSource {
        val sessions = mutableListOf<FakeSession>()
        override fun spec(): SpeechEngineSpec = SpeechEngineSpec.System(onDevice = true)
        override fun start(language: String?): SpeechSession = FakeSession().also { sessions += it }
    }

    private val voice = FakeVoice()

    @Before fun grantMic() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
    }

    private fun composer(text: String = "") = compose.setContent {
        MinaTheme {
            var t by remember { mutableStateOf(text) }
            Box(Modifier.fillMaxSize()) {
                Composer(
                    Modifier, t, { t = it }, ComposerMode.Idle, hasHistory = false, onSend = {}, onStop = {},
                    voice = voice,
                    onVoiceText = { t = com.meenai.mina.voice.HoldToTalk.join(t, it) },
                )
            }
        }
    }

    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitNoTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    @Test fun holdShowsTheListenCardAndReleaseFillsTheField() {
        composer("help me")
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        session.out.trySend(SpeechEvent.Partial("Book tomorrow", "of tickets"))
        //Pressing for more than half a second is not considered an accidental touch.
        Thread.sleep(700)
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitUntil(5_000) { session.released == 1 }
        session.out.trySend(SpeechEvent.Final("Book tickets for tomorrow"))
        session.out.close()
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("Help me book a ticket for tomorrow")
    }

    @Test fun focusedFieldKeepsLongPressForTheSystem() {
        composer("Book a ticket for me")
        //Click:Focus on typing
        compose.onNodeWithTag("composer-field").performClick()
        compose.onNodeWithTag("composer-field").assertIsFocused()
        //After focusing, long press is the system's word selection and paste.,Don't start listening
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        Thread.sleep(800)
        compose.waitForIdle()
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(0, voice.sessions.size)
        assertEquals(0, compose.onAllNodesWithTag("voice-listen").fetchSemanticsNodes().size)
    }

    @Test fun focusedMicButtonFillsTheField() {
        composer("help me")
        compose.onNodeWithTag("composer-field").performClick()
        compose.onNodeWithTag("composer-field").assertIsFocused()
        waitTag("composer-mic")
        compose.onNodeWithTag("composer-mic").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        session.out.trySend(SpeechEvent.Partial("Book tomorrow", "of tickets"))
        Thread.sleep(700)
        compose.onNodeWithTag("composer-mic").performTouchInput { up() }
        compose.waitUntil(5_000) { session.released == 1 }
        session.out.trySend(SpeechEvent.Final("Book tickets for tomorrow"))
        session.out.close()
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("Help me book a ticket for tomorrow")
    }

    /**Recording allowed,The recognition service still reports a recording error:Bullet description(Microphone master switch, permission management);Otherwise, if the microphone cannot be turned on, it will still only prompt one sentence. */
    @Test fun audioErrorExplainsWhereToCheckButClientErrorKeepsTheOldHint() {
        composer()
        fun failWith(why: SpeechFailure) {
            compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
            waitTag("voice-listen")
            voice.sessions.last().out.trySend(SpeechEvent.Failed(why))
            compose.onNodeWithTag("composer-field").performTouchInput { up() }
        }
        failWith(SpeechFailure.MicUnavailable)
        waitTag("voice-toast")
        compose.onNodeWithText("The microphone is unavailable and may be occupied by another app.").assertExists()
        assertEquals(0, compose.onAllNodesWithTag("voice-sheet").fetchSemanticsNodes().size)
        failWith(SpeechFailure.AudioError)
        waitTag("voice-sheet")
        compose.onNodeWithText("The microphone still doesn't work").assertExists()
    }

    @Test fun slidingUpCancels() {
        composer()
        compose.onNodeWithTag("composer-field").performTouchInput { down(center) }
        waitTag("voice-listen")
        val session = voice.sessions.single()
        Thread.sleep(700)
        compose.onNodeWithTag("composer-field").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, -400f)) }
        waitTag("voice-cancel-hint")
        compose.onNodeWithTag("composer-field").performTouchInput { up() }
        compose.waitUntil(5_000) { session.cancelled == 1 }
        assertEquals(0, session.released)
        waitNoTag("voice-listen")
        compose.onNodeWithTag("composer-field").assertTextEquals("")
    }
}
