package com.meenai.mina.cap.sandbox

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.chat.SharedFile
import com.meenai.mina.core.cap.sandbox.CommandResult
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Collections
import java.util.UUID

/**
 *Real machine use case of bridge:in the sandbox `mina`The command is called back to Mina via an abstract socket. Use the installed sandbox(python3 and mina client are both installed during installation.);
 *Skip if not installed.
 */
@RunWith(AndroidJUnit4::class)
class BridgeDeviceTest {
    private val shared = Collections.synchronizedList(mutableListOf<SharedFile>())
    private val sb = AndroidSandbox(context, SandboxState.get(context)) { shared += it }
    private val progress = Collections.synchronizedList(mutableListOf<String>())

    private fun sh(command: String): CommandResult =
        runBlocking { sb.run("bridge-ws", listOf("/bin/sh", "-lc", command), null, 30_000) { progress += it } }

    //Use the installed sandbox(I installed it when I was pretending. python3 and mina client);Skip if not installed properly,Testing without unblocking the root file system yourself
    @Before fun needsInstalledSandbox() {
        assumeTrue("sandbox not installed", SandboxState.get(context).status.value is SandboxStatus.Ready)
        assumeTrue("python3 not installed in the rootfs",
            Files.exists(File(SandboxDirs(context).rootfs, "usr/bin/python3").toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    //Export to "Download/Mina's test files are deleted,Not left in the user's download directory
    @After fun deleteExported() {
        for (f in shared) runCatching { context.contentResolver.delete(Uri.parse(f.uri), null, null) }
        SandboxDirs(context).workspace("bridge-ws").deleteRecursively()
    }

    @Test fun progressReachesCallback() {
        val r = sh("minaprogress step 1")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals(listOf("Step 1"), progress.toList())
    }

    @Test fun shareCallsExporter() {
        //Name with random string,There will be no name collision in the download directory;Files outside the workspace are first rejected by path rules
        val name = "mina-bridge-test-${UUID.randomUUID().toString().take(8)}.txt"
        val r = sh("echo hi > $name; mina share $name; echo rc=$?; mina share /etc/passwd; echo rc=$?")
        assertTrue(r.output, r.output.contains("\"savedAs\":\"Download/Mina/$name\""))
        assertTrue(r.output, r.output.contains("rc=0"))
        assertTrue(r.output, r.output.contains("path_outside_workspace"))
        assertEquals(r.output, 1, Regex("rc=1").findAll(r.output).count())
        assertEquals(listOf("Download/Mina/$name"), shared.map { it.savedAs })
    }

    @Test fun bridgeWorksWithNetOff() {
        val r = sh("mina info")
        assertEquals(r.output, 0, r.exitCode)
        assertTrue(r.output, r.output.contains("\"network\":false"))
        assertTrue(r.output, r.output.contains("\"workspace\":\"/workspace\""))
    }

    @Test fun sessionClosedAfterCommand() {
        val name = sh("echo \$MINA_BRIDGE").output.trim()
        assertTrue(name, name.startsWith("mina-bridge-"))
        val refused = try {
            LocalSocket().use { it.connect(LocalSocketAddress(name)) }
            false
        } catch (e: IOException) {
            true
        }
        assertTrue("old bridge name still accepts connections", refused)
    }

    @Test fun wrongTokenIsRejected() {
        val r = sh("MINA_TOKEN=forged mina info")
        assertEquals(r.output, 1, r.exitCode)
        assertTrue(r.output, r.output.contains("bad_token"))
    }

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    }
}
