package com.meenai.mina.capsule

import androidx.compose.ui.geometry.Rect
import com.meenai.mina.cap.phone.a11y.HoldGesture
import com.meenai.mina.i18n.EnglishTexts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**Press and hold the capsule to speak:Which circle your finger falls on when you release it, how to distinguish between long pressing and dragging, and the sentence that flashes on the capsule after speaking. */
class CapsuleVoiceTest {
    @get:Rule val texts = EnglishTexts()

    private val cancel = Rect(100f, 200f, 162f, 262f)
    private val edit = Rect(300f, 200f, 362f, 262f)

    @Test fun insideCancelCircleIsCancel() {
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(130f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun justOutsideCancelCircleStillCancels() {
        //Outside the circle 10px,Expansion 14Within px
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(90f, 230f, cancel, edit, slopPx = 14))
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(130f, 272f, cancel, edit, slopPx = 14))
    }

    @Test fun farFromBothCirclesIsSend() {
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(230f, 900f, cancel, edit, slopPx = 14))
        //The middle of the two circles is not close enough.
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(231f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun insideEditCircleIsEdit() {
        assertEquals(CapsuleVoice.Drop.Edit, CapsuleVoice.drop(330f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun circlesNotLaidOutYetMeanSend() {
        //The panel has not been laid out yet(The position of the circle has not been reported yet.)will not be mistakenly judged as cancellation.
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(0f, 0f, Rect.Zero, Rect.Zero, slopPx = 14))
    }

    @Test fun smallMoveThenTimeoutIsHold() {
        val g = HoldGesture(slopPx = 8f)
        assertEquals(HoldGesture.Kind.Tap, g.down())
        assertEquals(HoldGesture.Kind.Tap, g.move(5f))
        assertEquals(HoldGesture.Kind.Hold, g.timeout())
        //After long pressing, no matter how you move, it will still be long pressed.,Don't turn into a drag
        assertEquals(HoldGesture.Kind.Hold, g.move(200f))
    }

    @Test fun movedPastSlopFirstIsDragAndNeverHold() {
        val g = HoldGesture(slopPx = 8f)
        g.down()
        assertEquals(HoldGesture.Kind.Drag, g.move(20f))
        assertEquals(HoldGesture.Kind.Drag, g.timeout())
        assertEquals(HoldGesture.Kind.Drag, g.move(2f))
    }

    @Test fun newDownStartsOver() {
        val g = HoldGesture(slopPx = 8f)
        g.down()
        g.move(20f)
        assertEquals(HoldGesture.Kind.Tap, g.down())
        assertEquals(HoldGesture.Kind.Hold, g.timeout())
    }

    @Test fun sentNoteSaysWhatHappenedWithTheFirstWords() {
        assertEquals("Told the task: If you want …", CapsuleVoice.sent(CardInputKind.Steer, "If you want to go after 3pm, the second class seat will be by the window."))
        assertEquals("Answered: tomorrow mor…", CapsuleVoice.sent(CardInputKind.Answer, "tomorrow mor…"))
        assertEquals("Sent: Check the we…", CapsuleVoice.sent(CardInputKind.Continue, "Check the weather"))
        assertEquals("Sent: Check the we…", CapsuleVoice.sent(CardInputKind.Start, "Check the weather"))
    }
}
