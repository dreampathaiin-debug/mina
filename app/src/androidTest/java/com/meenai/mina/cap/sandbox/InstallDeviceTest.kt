package com.meenai.mina.cap.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.core.cap.sandbox.CommandResult
import com.meenai.mina.core.cap.sandbox.SANDBOX_PACKAGES
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**True installation(Understand the built-in operating environment,Not connected to the Internet);After installation, run Python, Node and `mina`. */
@RunWith(AndroidJUnit4::class)
class InstallDeviceTest {
    private val sb = AndroidSandbox(context, SandboxState.get(context))

    private fun sh(command: String): CommandResult =
        runBlocking { sb.run("install-test", listOf("/bin/sh", "-lc", command), null, 60_000) {} }

    @Test
    fun fullInstallThenPythonAndNode() {
        assertEquals(SandboxStatus.Ready(network = false), SandboxState.get(context).status.value)
        sh("python3 -c 'print(1+1)'").let { assertEquals(it.output, 0, it.exitCode); assertEquals("2", it.output.trim()) }
        sh("node -e 'console.log(2+2)'").let { assertEquals(it.output, 0, it.exitCode); assertEquals("4", it.output.trim()) }
        sh("mina info").let { r ->
            assertEquals(r.output, 0, r.exitCode)
            Json.parseToJsonElement(r.output.trim()).jsonObject //is a JSON object
        }
    }

    /**It's installed by itself Alpine;The packages are all in the apk database,The database is consistent with the file(apk-tools3 Cannot write into the database under PRoot,So use 3.22 apk-tools 2). */
    @Test
    fun pinnedReleaseAndPackagesRecorded() {
        sh("cat /etc/alpine-release").let { assertEquals(it.output, BundledRuntime.VERSION.substringBefore('-'), it.output.trim()) }
        sh("apk --version").let { assertEquals(it.output, 0, it.exitCode); assertTrue(it.output, it.output.startsWith("apk-tools 2.")) }
        sh("apk info -e ${SANDBOX_PACKAGES.joinToString(" ")} 2>/dev/null").let { r ->
            assertEquals(r.output, 0, r.exitCode)
            assertEquals(r.output, SANDBOX_PACKAGES.toSet(), r.output.lines().filter(String::isNotBlank).toSet())
        }
        //When the Internet is turned off, the apk will complain on the standard error that the software source cache cannot be read.,Just look at standard output
        //Unpacker without extended attributes(Designed like this),So only the entries for extended attributes are missing(code x)Doesn’t count;Differences in content, permissions, and ownership will still be counted.
        sh("apk audit --system 2>/dev/null").let { r ->
            assertEquals(r.output, 0, r.exitCode)
            val drift = r.output.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("x ") }
            assertEquals(r.output, emptyList<String>(), drift)
        }
    }

    @Test
    fun networkOffAfterInstall() {
        val r = sh("python3 -c 'import socket; socket.create_connection((\"1.1.1.1\", 443), timeout=5)'")
        assertNotEquals(r.output, 0, r.exitCode)
        assertTrue(r.output, r.output.contains("PermissionError") || r.output.contains("Permission denied"))
        assertEquals(false, r.network)
    }

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        @BeforeClass
        @JvmStatic
        fun install() {
            val state = SandboxState.get(context)
            state.networkAllowed = false
            val installer = RootfsInstaller.create(context)
            runBlocking {
                installer.reset()
                installer.install { _, _ -> }
            }
            assertEquals(InstallStep.Done, state.completedStep())
        }
    }
}
