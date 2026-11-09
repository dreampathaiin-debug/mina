package com.meenai.mina.cap.sandbox

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.core.workspace.FdSys
import com.meenai.mina.core.workspace.SandboxError
import com.meenai.mina.core.workspace.WorkspaceFileGateContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**The Android implementation of the handle runs the same set of pass rules,In addition, it was only measured on a real machine.:The FIFO is not blocked, and if you change the file after checking, it will not touch the outside. */
@RunWith(AndroidJUnit4::class)
class WorkspaceFileGateDeviceTest : WorkspaceFileGateContract() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    override val sys: FdSys = AndroidFdSys
    override fun tempDir(): File = File(context.filesDir, "gate-test-${System.nanoTime()}").apply { mkdirs() }

    @After fun cleanUp() { tmp.deleteRecursively() }

    @Test fun fifoRefusedWithoutBlocking() {
        Os.mkfifo(File(ws, "pipe").path, 0x180)
        val begin = System.currentTimeMillis()
        for (op in listOf<() -> Unit>({ gate.openRead(ws, "pipe").close() }, { gate.openWrite(ws, "pipe", append = false).close() })) {
            try { op(); fail("FIFO accepted") } catch (e: SandboxError) { assertEquals("not_a_file", e.code) }
        }
        assertTrue("took ${System.currentTimeMillis() - begin} ms", System.currentTimeMillis() - begin < 3_000)
    }

    /**After opening, change the directory to a link pointing to the outside.:Reading and writing on the handle are still the same files,External files are unaffected. */
    @Test fun swapAfterOpenDoesNotRedirect() {
        File(ws, "d").mkdirs(); File(ws, "d/a.txt").writeText("inside")
        gate.openRead(ws, "d/a.txt").use { g ->
            assertTrue(File(ws, "d").renameTo(File(ws, "d-old")))
            Os.symlink(secret.path, File(ws, "d").path)
            assertEquals("inside", String(g.read(0, 100)))
        }
        val w = gate.createNew(ws, "e/new.txt")
        File(ws, "e").renameTo(File(ws, "e-old")); Os.symlink(secret.path, File(ws, "e").path)
        w.use { it.write("x".toByteArray()) }
        assertFalse(File(secret, "new.txt").exists())
        assertEquals("x", File(ws, "e-old/new.txt").readText())
    }

    /** /proc/self/The root directory and file path read by fd come from the same source.,/data/user/0 and /data/The difference in writing of data does not affect the judgment. */
    @Test fun realPathsComeFromTheHandle() {
        File(ws, "x.txt").writeText("1")
        gate.openRead(File(context.dataDir, ws.relativeTo(context.dataDir).path), "x.txt").use { assertEquals(1L, it.size) }
    }
}
