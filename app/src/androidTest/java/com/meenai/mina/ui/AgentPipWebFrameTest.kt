package com.meenai.mina.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**The small window is sized according to the screen ratio:The left and right ends of the horizontal web page can be seen,The screen of the long-screen mobile phone can be seen at the bottom. */
@RunWith(AndroidJUnit4::class)
class AgentPipWebFrameTest {

    @get:Rule val compose = createComposeRule()

    @Test fun landscapeWebFrameShowsBothEdges() {
        val frame = Bitmap.createBitmap(1280, 800, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 0f, 320f, 800f, Paint().apply { color = Color.RED })
            drawRect(960f, 0f, 1280f, 800f, Paint().apply { color = Color.BLUE })
        }
        compose.setContent {
            AgentPip(PipUi(visible = true), frame, webContent = true, reduceMotion = true,
                topInset = 0f, bottomInset = 0f, onExpand = {}, onCollapse = {}, onAttention = {})
        }
        val pixels = compose.onNodeWithContentDescription("Mina's virtual screen").captureToImage().toPixelMap()
        val y = pixels.height / 2
        val left = pixels[pixels.width / 10, y]
        val right = pixels[pixels.width * 9 / 10, y]
        assertTrue("left side of web viewport was cropped: $left", left.red > .8f && left.blue < .2f)
        assertTrue("right side of web viewport was cropped: $right", right.blue > .8f && right.red < .2f)
        frame.recycle()
    }

    @Test fun tallPhoneFrameShowsItsBottom() {
        //Picture of long screen mobile phone(Height is approximately 2.2 times width):The bottom section is painted green,The height of the small window is determined according to the screen ratio,The bottom cannot be cut off
        val frame = Bitmap.createBitmap(360, 794, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 714f, 360f, 794f, Paint().apply { color = Color.GREEN })
        }
        compose.setContent {
            AgentPip(PipUi(visible = true), frame, webContent = false, reduceMotion = true,
                topInset = 0f, bottomInset = 0f, onExpand = {}, onCollapse = {}, onAttention = {})
        }
        val pixels = compose.onNodeWithContentDescription("Mina's virtual screen").captureToImage().toPixelMap()
        val bottom = pixels[pixels.width / 2, pixels.height - 3]
        assertTrue("bottom of phone frame was cropped: $bottom", bottom.green > .8f && bottom.red < .2f)
        frame.recycle()
    }
}
