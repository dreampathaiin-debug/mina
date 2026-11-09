package com.meenai.mina.cap.phone.a11y

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.ui.MainActivity
import com.meenai.mina.a11y.WebEditorTestActivity
import java.io.File
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.Verification
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *Real machine test:Web page rich text editing area. accessible SET_TEXTOnly the text on the web page has been changed,The editor cannot receive input events,
 *The app will think there is no input(For example, the "Finish" button does not light up and does not save when exiting.). Typing must allow the editor to receive real input.
 */
@RunWith(AndroidJUnit4::class)
class WebEditorInputTest {
    private val agent = DeviceSupport.newAgent()
    private val screen get() = agent.screen

    @After
    fun tearDown() = agent.release()

    private fun snapUntil(timeoutMs: Long = 8_000, test: (ScreenSnapshot) -> Boolean): ScreenSnapshot = runBlocking {
        val end = System.currentTimeMillis() + timeoutMs
        var s = screen.snapshot()
        while (!test(s) && System.currentTimeMillis() < end) {
            Thread.sleep(200)
            s = screen.snapshot()
        }
        assertTrue("screen never matched: ${s.elements.map { it.name }}", test(s))
        s
    }

    private fun status(s: ScreenSnapshot) = s.elements.firstOrNull { it.name.startsWith("inputs=") }?.name

    @Test
    fun typingReachesTheWebEditor() = runBlocking<Unit> {
        DeviceSupport.startTestActivity(agent.display.displayId, WebEditorTestActivity::class.java)
        val s = snapUntil { snap -> snap.elements.any { it.editable } }
        val field = s.elements.first { it.editable }

        val r = screen.typeText(field.ref, s.snapshotId, "Hello Mina")
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals(Verification.MATCH, (r as ActionResult.Ok).verification)
        val after = snapUntil { snap -> status(snap)?.endsWith("text=Hello Mina") == true }
        assertTrue(status(after)!!, !status(after)!!.startsWith("inputs=0"))

        //Hit it again to replace,Not at the back
        val again = screen.typeText(after.elements.first { it.editable }.ref, after.snapshotId, "second time")
        assertEquals(Verification.MATCH, (again as ActionResult.Ok).verification)
        val replaced = snapUntil { snap -> status(snap)?.endsWith("text=second time") == true }

        val cleared = screen.clearText(replaced.elements.first { it.editable }.ref, replaced.snapshotId)
        assertTrue("$cleared", cleared is ActionResult.Ok)
        snapUntil { snap -> status(snap)?.endsWith("text=") == true }
    }

    /**
     *Content originally copied by the user(Bring your own label)Return it as is after typing. Mina's interface opens on the home screen,This way the test process can read the clipboard;
     *Take a screenshot of the home screen after restoration(files/shots/clip-restored.png),Manually confirm that the "Copied" overlay does not pop up.
     */
    @Test
    fun pastingRestoresTheUsersClipboard() = runBlocking<Unit> {
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            instr.runOnMainSync { cm.setPrimaryClip(ClipData.newPlainText("user-label", "Originally copied by the user 123")) }

            DeviceSupport.startTestActivity(agent.display.displayId, WebEditorTestActivity::class.java)
            val s = snapUntil { snap -> snap.elements.any { it.editable } }
            val r = screen.typeText(s.elements.first { it.editable }.ref, s.snapshotId, "paste test")
            //Restore just happened:If the "Copied" floating layer pops up,Right now on the home screen
            val shot = instr.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
            File(ctx.getExternalFilesDir(null), "shots").mkdirs()
            File(ctx.getExternalFilesDir(null), "shots/clip-restored.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 80, it) }
            assertEquals(Verification.MATCH, (r as ActionResult.Ok).verification)
            snapUntil { snap -> status(snap)?.endsWith("text=paste test") == true }

            Thread.sleep(1500)
            var clip: ClipData? = null
            instr.runOnMainSync { clip = cm.primaryClip }
            assertEquals("user-label", clip?.description?.label?.toString())
            assertEquals("Originally copied by the user 123", clip?.getItemAt(0)?.text?.toString())

        }
    }
}
