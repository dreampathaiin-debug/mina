package com.meenai.mina.cap.phone.a11y

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**Long press on the root of the floating layer window:Press and hold until the point is reached and then press and hold.,;Notification of cancellation when long press is removed midway,Lifting up after that no longer counts. */
@RunWith(AndroidJUnit4::class)
class OverlayFrameHoldTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val calls = mutableListOf<String>()
    private val hold = object : OverlayHold {
        override fun start(rawX: Float, rawY: Float) { calls += "hold-start" }
        override fun move(rawX: Float, rawY: Float) = Unit
        override fun end(rawX: Float, rawY: Float) { calls += "hold-end" }
        override fun cancel() { calls += "hold-cancel" }
    }

    private inner class Content : View(context) {
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> calls += "down"
                MotionEvent.ACTION_UP -> calls += "up"
                MotionEvent.ACTION_CANCEL -> calls += "cancel"
            }
            return true
        }
    }

    private lateinit var frame: OverlayFrame
    private val downAt = SystemClock.uptimeMillis()

    private fun ev(action: Int) = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, 50f, 50f, 0)
        .also { frame.dispatchTouchEvent(it); it.recycle() }

    /**press,Waited longer than the long press time limit(main thread empty,The time is up). */
    private fun pressAndWait() {
        instrumentation.runOnMainSync {
            frame = OverlayFrame(context).apply {
                addView(Content(), FrameLayout.LayoutParams(100, 100))
                measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
                layout(0, 0, 100, 100)
                setHold(hold, HOLD_MS)
            }
            ev(MotionEvent.ACTION_DOWN)
        }
        Thread.sleep(HOLD_MS * 4)
        instrumentation.waitForIdleSync()
    }

    @Test fun stillPressBecomesHoldAndUpEndsIt() {
        pressAndWait()
        instrumentation.runOnMainSync { ev(MotionEvent.ACTION_UP) }
        assertEquals(listOf("down", "cancel", "hold-start", "hold-end"), calls)
    }

    @Test fun clearingTheHoldMidGestureCancelsItAndALaterUpDoesNothing() {
        pressAndWait()
        instrumentation.runOnMainSync {
            frame.setHold(null, HOLD_MS)
            ev(MotionEvent.ACTION_UP)
        }
        assertEquals(listOf("down", "cancel", "hold-start", "hold-cancel"), calls)
    }

    @Test fun replacingTheHoldMidGestureCancelsTheOldOne() {
        pressAndWait()
        val other = mutableListOf<String>()
        instrumentation.runOnMainSync {
            frame.setHold(object : OverlayHold {
                override fun start(rawX: Float, rawY: Float) { other += "start" }
                override fun move(rawX: Float, rawY: Float) = Unit
                override fun end(rawX: Float, rawY: Float) { other += "end" }
                override fun cancel() { other += "cancel" }
            }, HOLD_MS)
            ev(MotionEvent.ACTION_UP)
        }
        assertEquals(listOf("down", "cancel", "hold-start", "hold-cancel"), calls)
        assertEquals(emptyList<String>(), other)
    }

    @Test fun settingTheSameHoldAgainKeepsTheGesture() {
        pressAndWait()
        instrumentation.runOnMainSync {
            //Every time you redraw, the same long press will be installed again.:Shouldn't interrupt what's going on this time
            frame.setHold(hold, HOLD_MS)
            ev(MotionEvent.ACTION_UP)
        }
        assertEquals(listOf("down", "cancel", "hold-start", "hold-end"), calls)
    }

    private companion object {
        const val HOLD_MS = 60L
    }
}
