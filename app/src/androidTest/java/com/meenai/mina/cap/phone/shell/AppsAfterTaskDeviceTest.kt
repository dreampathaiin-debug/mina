package com.meenai.mina.cap.phone.shell

import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.AppCopyReader
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.shell.IMinaShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *What happens to the apps on Mina’s screen when the mission ends?(IMinaShell.finishDisplay):Stay silently,Move it to the back of the home screen and out of sight of users;
 *The same task will be moved back to Mina's screen the next time the startup portal is opened.;Close the screen that is currently speaking and marked close.;Sounds marked with keep are also left.
 *Requires the casing to be connected. The user's home screen should not change in any way(The focus and focus window remain unchanged.,The tasks left behind are invisible).
 */
@RunWith(AndroidJUnit4::class)
class AppsAfterTaskDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shell: IMinaShell get() = DeviceSupport.shell()
    private val screens = mutableListOf<ParityScreen>()
    private lateinit var probe: ShellProbe

    @Before fun setUp() { probe = ShellProbe(context) }

    @After fun tearDown() {
        screens.forEach { runCatching { it.close() } }
        //Probe tasks that stay behind the home screen:Move it to a new screen and close it with the screen(It's the road that will open next time)
        runCatching { ParityScreen(shell, 720, 1600, 320).use { s -> shell.startApp(s.id, TEST_PACKAGE); Thread.sleep(500) } }
        runCatching { probe.close() }
    }

    private fun newScreen() = ParityScreen(shell, 720, 1600, 320).also { screens += it }

    /**The focus of the home screen at the moment(Top focus screen, focus window, focus Activity),Does not include "top task":Some systems arrange tasks according to recent use. */
    private fun mainFocus(): String = DeviceSupport.mainScreenState(shell).substringBefore(" topTask=")

    private fun Bundle.kept() = getStringArray(AppsAfterTask.KEY_KEPT)?.toList().orEmpty()
    private fun Bundle.closed() = getStringArray(AppsAfterTask.KEY_CLOSED)?.toList().orEmpty()

    private fun probeOn(displayId: Int, component: String): Int {
        sh("am start --display $displayId -f 0x18000000 -n $component")
        return (probe.await { it.kind == ShellProbe.RESUMED && it.display == displayId }
            ?: error("probe did not come up on display $displayId: ${probe.all()}")).task
    }

    @Test fun quietAppStaysBehindOnMainAndReopenBringsTheSameTaskBack() {
        val s = newScreen()
        assertEquals(ShellService.START_OK, shell.startApp(s.id, TEST_PACKAGE))
        val task = probe.await { it.kind == ShellProbe.RESUMED && it.display == s.id }?.task ?: error("probe did not come up: ${probe.all()}")
        val before = mainFocus()

        val result = shell.finishDisplay(s.id, emptyArray(), emptyArray())
        assertNotNull(result)
        val note = result!!.getString(AppsAfterTask.KEY_NOTE)
        if (note?.contains(AppsAfterTask.NOTE_MOVE_UNSUPPORTED) == true) {
            //This system cannot be moved:Turn off the screen as usual
            assertEquals(listOf("$TEST_PACKAGE:${AppsAfterTask.Why.MoveUnsupported.wire}"), result.closed())
            assertNotNull(pollUntil(5_000) { true.takeIf { shell.displayOfTask(task) == -1 } })
            return
        }
        assertEquals("note=$note closed=${result.closed()}", listOf(TEST_PACKAGE), result.kept())
        assertEquals(Display.DEFAULT_DISPLAY, pollUntil(5_000) { shell.displayOfTask(task).takeIf { it == Display.DEFAULT_DISPLAY } })
        //Not in front of users:Invisible, focus has not changed
        assertFalse("kept task is visible on the main screen", shell.visibleOnMainScreen(TEST_PACKAGE, AppCopyReader.myUser))
        assertEquals("main screen focus changed", before, mainFocus())
        //in recent tasks:It’s also in the system’s task list
        //Each task in the recent task record is written as Task{… #<serial number>…}
        assertTrue("kept task is gone from recents", Regex("#$task\\b").containsMatchIn(sh("dumpsys activity recents")))

        //Open next time:Move the same task back to Mina's new screen
        val s2 = newScreen()
        probe.clear()
        assertEquals(ShellService.START_OK, shell.startApp(s2.id, TEST_PACKAGE))
        assertEquals(s2.id, pollUntil(5_000) { shell.displayOfTask(task).takeIf { it == s2.id } })
        assertNotNull("probe did not come back on ${s2.id}: ${probe.all()}",
            probe.await { it.kind == ShellProbe.RESUMED && it.display == s2.id && it.task == task })
        assertEquals("main screen focus changed", before, mainFocus())
    }

    @Test fun playingAppIsClosedByDefault() {
        val s = newScreen()
        val task = probeOn(s.id, AUDIO_PROBE)
        //It will take a while for the player to report to the audio service.
        Thread.sleep(PLAYER_SETTLE_MS)
        val result = shell.finishDisplay(s.id, emptyArray(), emptyArray())!!
        assertEquals("kept=${result.kept()}", listOf("$TEST_PACKAGE:${AppsAfterTask.Why.Playing.wire}"), result.closed())
        assertNotNull("playing task outlived the display", pollUntil(5_000) { true.takeIf { shell.displayOfTask(task) == -1 } })
    }

    @Test fun keepMarkKeepsAPlayingApp() {
        val s = newScreen()
        val task = probeOn(s.id, AUDIO_PROBE)
        Thread.sleep(PLAYER_SETTLE_MS)
        val result = shell.finishDisplay(s.id, arrayOf(TEST_PACKAGE), emptyArray())!!
        if (result.getString(AppsAfterTask.KEY_NOTE)?.contains(AppsAfterTask.NOTE_MOVE_UNSUPPORTED) == true) return
        assertEquals(listOf(TEST_PACKAGE), result.kept())
        assertEquals(Display.DEFAULT_DISPLAY, pollUntil(5_000) { shell.displayOfTask(task).takeIf { it == Display.DEFAULT_DISPLAY } })
        assertFalse(shell.visibleOnMainScreen(TEST_PACKAGE, AppCopyReader.myUser))
    }

    @Test fun closeMarkClosesAQuietApp() {
        val s = newScreen()
        val task = probeOn(s.id, ShellProbe.COMPONENT)
        val result = shell.finishDisplay(s.id, emptyArray(), arrayOf(TEST_PACKAGE))!!
        assertEquals(listOf("$TEST_PACKAGE:${AppsAfterTask.Why.Marked.wire}"), result.closed())
        assertNotNull(pollUntil(5_000) { true.takeIf { shell.displayOfTask(task) == -1 } })
    }

    @Test fun refusesDisplaysItDidNotCreate() {
        assertEquals(null, shell.finishDisplay(Display.DEFAULT_DISPLAY, emptyArray(), emptyArray()))
    }

    private companion object {
        const val AUDIO_PROBE = "com.meenai.mina.test/com.meenai.mina.shell.AudioProbeActivity"
        const val PLAYER_SETTLE_MS = 1_000L
    }
}
