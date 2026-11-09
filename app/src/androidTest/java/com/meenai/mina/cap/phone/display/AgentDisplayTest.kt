package com.meenai.mina.cap.phone.display

import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.os.Build
import android.view.Display
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.shell.IMinaShell
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.cap.phone.shell.BuiltinShellConnection
import com.meenai.mina.cap.phone.shell.ShellService
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**Real machine test:Requires the built-in assistant to be running(Paired, wireless debugging is on). */
@RunWith(AndroidJUnit4::class)
class AgentDisplayTest {
    //The entire test class shares a connection,It is consistent with the usage of holding a connection for a long time in the App.
    companion object {
        private const val SETTINGS = "com.android.settings"
        private const val SHELL_PROCESS = "com.meenai.mina:helper"
        private lateinit var connection: BuiltinShellConnection

        @BeforeClass
        @JvmStatic
        fun bindShell() {
            val phone = PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext)
            connection = BuiltinShellConnection(phone.hub) { phone.shellManager.engine.need() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.bind() }
            awaitShell()
        }

        @AfterClass
        @JvmStatic
        fun unbindShell() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.unbind() }
        }

        private fun awaitShell(): IMinaShell =
            DeviceSupport.awaitShell(connection)
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shell: IMinaShell get() = awaitShell()
    private var display: AgentDisplay? = null

    @After
    fun tearDown() {
        display?.release()
    }

    private fun newDisplay() = AgentDisplay(shell, 720, 1600, 320).also { display = it }

    @Test
    fun createsVersionAppropriatePublicDisplay() {
        val d = newDisplay()
        val dm = context.getSystemService(DisplayManager::class.java)
        val shown = dm.getDisplay(d.displayId)
        assertNotNull("display ${d.displayId} not visible to the app", shown)
        assertEquals(0, shown.flags and Display.FLAG_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue("display is not trusted", shown.flags and displayFlagTrusted() != 0)
        }
    }

    @Test
    fun startsSettingsOnDisplay() {
        val d = newDisplay()
        assertEquals(ShellService.START_OK, shell.startApp(d.displayId, SETTINGS))
        val task = pollUntil(5_000) { shell.topTaskId(d.displayId).takeIf { it != -1 } }
        assertNotNull("no task on display ${d.displayId}", task)
    }

    @Test
    fun startAppRefusesAppVisibleOnMainScreen() {
        val d = newDisplay()
        shellCommand("am start -W -a android.settings.SETTINGS --display 0")
        try {
            val mainTop = pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it != -1 } }
            assertEquals(ShellService.START_IN_USE_ON_MAIN, shell.startApp(d.displayId, SETTINGS))
            Thread.sleep(1_000)
            assertEquals("main screen top task changed", mainTop, shell.topTaskId(Display.DEFAULT_DISPLAY))
            assertEquals(-1, shell.topTaskId(d.displayId))
        } finally {
            //Test the settings you open on the home screen:Go back to desktop and end it,In order to prevent the "home screen being visible" during the ending animation from affecting subsequent use cases.
            shellCommand("input -d 0 keyevent KEYCODE_HOME")
            shellCommand("am force-stop $SETTINGS")
        }
    }

    @Test
    fun frameArrives() {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        val png = pollUntil(2_000) { d.latestFramePng() }
        assertNotNull(png)
        //PNG file header
        assertEquals(0x89.toByte(), png!![0])
        assertEquals('P'.code.toByte(), png[1])
    }

    @Test
    fun framesFlowEmits() = runBlocking {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        val frame = withTimeoutOrNull(2_000) { d.frames.filterNotNull().first() }
        assertNotNull("no preview frame within 2s", frame)
        assertTrue("preview width ${frame!!.width} > 360", frame.width <= 360)    }

    @Test
    fun releaseClearsFrames() = runBlocking {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        withTimeout(2_000) { d.frames.filterNotNull().first() }
        d.release()
        assertEquals(null, d.frames.value)
    }

    @Test
    fun movesTaskBetweenVirtualDisplays() {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        val task = pollUntil(5_000) { shell.topTaskId(d.displayId).takeIf { it != -1 } }!!
        //Move between two virtual screens,Don’t touch the user’s home screen
        val other = AgentDisplay(shell, 720, 1600, 320)
        try {
            assertTrue(shell.moveTask(task, other.displayId))
            assertEquals(task, pollUntil(3_000) { shell.topTaskId(other.displayId).takeIf { it == task } })
            assertNotEquals(task, shell.topTaskId(d.displayId))
        } finally {
            other.release()
        }
    }

    @Test
    fun coldStartDoesNotKillAppWithTaskOnAnotherScreen() {
        val d = newDisplay()
        //Use another virtual screen to represent "tasks outside the Mina screen",Don’t touch the user’s home screen
        val other = AgentDisplay(shell, 720, 1600, 320)
        try {
            assertEquals(ShellService.START_OK, shell.startApp(other.displayId, SETTINGS))
            assertNotNull(pollUntil(5_000) { shell.topTaskId(other.displayId).takeIf { it != -1 } })
            val pid = pollUntil(3_000) { shellCommand("pidof $SETTINGS").trim().takeIf { it.isNotEmpty() } }
            assertNotNull(pid)
            //The other screen is also Mina’s screen(represents another task):Directly refuse, do not force stop,Return to "Occupied by other tasks"
            assertEquals(ShellService.START_IN_USE_BY_OTHER, shell.restartApp(d.displayId, SETTINGS))
            assertEquals("settings process was killed", pid, shellCommand("pidof $SETTINGS").trim())
        } finally {
            other.release()
        }
    }

    @Test
    fun injectsInputOnDisplay() {
        val d = newDisplay()
        val mainTop = shell.topTaskId(Display.DEFAULT_DISPLAY)
        shell.startApp(d.displayId, SETTINGS)
        assertNotNull(pollUntil(5_000) { shell.topTaskId(d.displayId).takeIf { it != -1 } })
        val home = stableFrame(d)

        assertTrue(shell.injectTap(d.displayId, 360, 800))
        val afterTap = pollUntil(3_000) { d.latestFramePng()?.takeIf { !it.contentEquals(home) } }
        assertNotNull("tap had no visible effect on the virtual display", afterTap)
        val subPage = stableFrame(d)

        assertTrue(shell.injectKey(d.displayId, KeyEvent.KEYCODE_BACK))
        val afterBack = pollUntil(3_000) { d.latestFramePng()?.takeIf { !it.contentEquals(subPage) } }
        assertNotNull("BACK had no visible effect on the virtual display", afterBack)

        assertEquals("main screen top task changed", mainTop, shell.topTaskId(Display.DEFAULT_DISPLAY))
    }

    @Test
    fun mutesAndRestoresAudio() {
        val before = shellCommand("appops get $SETTINGS PLAY_AUDIO").trim()
        assertTrue(shell.setAudioMuted(SETTINGS, true))
        assertTrue(shellCommand("appops get $SETTINGS PLAY_AUDIO").contains("PLAY_AUDIO: ignore"))
        assertTrue(shell.setAudioMuted(SETTINGS, false))
        assertEquals(before, shellCommand("appops get $SETTINGS PLAY_AUDIO").trim())
    }

    /**After the helper process is killed,watchdog reboot assistant,Connect to get new services as they become available. */
    @Test
    fun reconnectsAfterShellProcessDies() {
        val before = shell
        val pid = shellPid()
        assertNotNull("no $SHELL_PROCESS process", pid)
        shellCommand("kill $pid")
        val after = pollUntil(30_000) {
            connection.shell.value?.takeIf { it.asBinder() != before.asBinder() && it.asBinder().pingBinder() }
        }
        assertNotNull("shell did not come back after its process died", after)
        assertNotEquals(pid, shellPid())
        val d = AgentDisplay(after!!, 720, 1600, 320).also { display = it }
        assertTrue(d.displayId >= 0)
    }

    private fun shellPid(): Int? = shellCommand("ps -A -o PID,NAME").lines()
        .map { it.trim().split(Regex("\\s+")) }
        .firstOrNull { it.size == 2 && it[1] == SHELL_PROCESS }
        ?.get(0)?.toIntOrNull()

    private fun shellCommand(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /**Wait for the screen to freeze(separated 500The two frames of ms are the same),Return the frame. */
    private fun stableFrame(d: AgentDisplay): ByteArray {
        var last = pollUntil(2_000) { d.latestFramePng() }!!
        repeat(10) {
            Thread.sleep(500)
            val now = d.latestFramePng()!!
            if (now.contentEquals(last)) return now
            last = now
        }
        return last
    }

    // Display.FLAG_TRUSTEDis a hidden constant(1 shl 7),When reflection cannot be obtained, its value is used.
    private fun displayFlagTrusted(): Int =
        runCatching { Display::class.java.getField("FLAG_TRUSTED").getInt(null) }.getOrDefault(1 shl 7)

    private fun <T : Any> pollUntil(timeoutMs: Long, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            Thread.sleep(50)
        }
        return block()
    }
}
