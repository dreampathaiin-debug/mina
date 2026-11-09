package com.meenai.mina.cap.phone.shell

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.cap.phone.setup.BuiltinShellManager
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.meenai.mina.shell.IMinaShell

/**
 *Real machine test:The built-in assistant can be debugged wirelessly, hand over Binder, and inject clicks on your own virtual screen.,After unbinding, the assistant remains and can be used directly after rebinding.
 *premise:Android 12+,Wireless debugging is on. Match one of two:
 * -bring `-e pairingCode <6 digit code>`(Can be added `-e pairingPort <port>`),At this time, the system's "Pair using pairing code" window should be open.;
 * -Already paired with Mina's key:bring `-e assumePaired true`.
 */
@RunWith(AndroidJUnit4::class)
class BuiltinHelperDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val phone = PhoneServices.of(context)
    private val engine = phone.shellManager.engine

    private fun helperUp(): IMinaShell = runBlocking {
        when {
            args.getString("pairingCode") != null && engine.status.value != BuiltinShellStatus.Running ->
                engine.startPairing(args.getString("pairingCode")!!, args.getString("pairingPort")?.toIntOrNull())
            else -> {
                if (args.getString("assumePaired") == "true") {
                    context.getSharedPreferences("builtin_shell", Context.MODE_PRIVATE).edit().putBoolean("paired", true).commit()
                }
                engine.retry()
                assumeTrue("not paired: pass -e pairingCode <code> or -e assumePaired true", engine.status.value != BuiltinShellStatus.NotPaired)
            }
        }
        assertEquals(BuiltinShellStatus.Running, engine.status.value)
        withTimeout(5_000) { phone.hub.shell.filterNotNull().first() }
    }

    private fun poll(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(100)
        }
        return check()
    }

    @Test
    fun helperRunsAsShellDrivesItsDisplayAndSurvivesUnbind() {
        assumeTrue(Build.VERSION.SDK_INT >= BuiltinShellManager.MIN_SDK)
        val shell = helperUp()
        val ids = shell.identity()
        assertEquals("helper uid", 2000, ids[0])
        assertTrue("helper pid", ids[1] > 0 && ids[1] != android.os.Process.myPid())

        val conn = BuiltinShellConnection(phone.hub) { engine.need() }
        val main = InstrumentationRegistry.getInstrumentation()
        main.runOnMainSync { conn.bind() }
        val bound = runBlocking { withTimeout(5_000) { conn.shell.filterNotNull().first() } }
        assertSame(shell, bound)

        val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        val dm = context.getSystemService(DisplayManager::class.java)
        try {
            val displayId = bound.createDisplay(reader.surface, 720, 1280, 320)
            assertTrue("createDisplay -> $displayId", displayId > 0)
            assertNotNull("display $displayId not visible", dm.getDisplay(displayId))
            assertTrue("injectTap", bound.injectTap(displayId, 100, 100))

            //unbundle:The screen was removed,Same as end of service;The helper process and its Binder are still there
            main.runOnMainSync { conn.unbind() }
            assertNull(conn.shell.value)
            assertTrue("display not released on unbind", poll(5_000) { dm.getDisplay(displayId) == null })
            assertTrue("helper died on unbind", shell.asBinder().pingBinder())
            assertSame(shell, phone.hub.shell.value)

            //Bind again:Get the same assistant directly,Can still be used
            main.runOnMainSync { conn.bind() }
            val again = runBlocking { withTimeout(5_000) { conn.shell.filterNotNull().first() } }
            assertSame(shell, again)
            val second = again.createDisplay(reader.surface, 720, 1280, 320)
            assertTrue("createDisplay after rebind -> $second", second > 0)
            assertTrue(again.injectTap(second, 50, 50))
        } finally {
            main.runOnMainSync { conn.unbind() }
            reader.close()
        }
    }
}
