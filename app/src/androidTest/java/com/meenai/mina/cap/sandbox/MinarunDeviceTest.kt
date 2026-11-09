package com.meenai.mina.cap.sandbox

import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 *Real device use case of launcher minarun:Exit code, timeout, disconnection, clearing(The descendant of the parent process, Mina, is killed.).
 *Root file system with SandboxSmokeTestshared.
 */
@RunWith(AndroidJUnit4::class)
class MinarunDeviceTest {

    @Test
    fun exitCodePassesThrough() {
        val r = run(launcher("--timeout", "30") + proot("/bin/sh", "-c", "exit 7"))
        assertEquals(r.output, 7, r.exitCode)
    }

    @Test
    fun timeoutReturns124() {
        val r = run(launcher("--timeout", "2") + proot("/bin/sleep", "30"))
        assertEquals(r.output, 124, r.exitCode)
        assertTrue("took ${r.elapsedMs} ms", r.elapsedMs < 5_000)
    }

    @Test
    fun detachedChildrenAreKilled() {
        val marker = "1001"
        val script = "setsid sleep $marker & (sleep $marker &) ; sleep $marker"
        val r = run(launcher("--timeout", "2") + proot("/bin/sh", "-c", script))
        assertEquals(r.output, 124, r.exitCode)
        assertEquals("leftover sleep processes", emptyList<Int>(), awaitGone(marker, 1_000))
    }

    @Test
    fun parentDeathKillsWholeTree() {
        val marker = "1002"
        val inner = (launcher("--timeout", "600") + proot("/bin/sleep", marker)).joinToString(" ") { shQuote(it) }
        //"Fake parent process":Pay your own first PID,Then start the launcher in the background and wait for it
        val p = start(listOf("/system/bin/sh", "-c", "echo \$\$; $inner & wait"))
        try {
            val fakeParent = p.inputStream.bufferedReader().readLine().trim().toInt()
            val deadline = System.currentTimeMillis() + 15_000
            while (findProcesses(marker).isEmpty()) {
                if (System.currentTimeMillis() > deadline) fail("sandboxed sleep never started")
                Thread.sleep(100)
            }
            Os.kill(fakeParent, OsConstants.SIGKILL)
            assertEquals("leftover sleep processes", emptyList<Int>(), awaitGone(marker, 3_000))
        } finally {
            p.destroyForcibly()
        }
    }

    @Test
    fun kill9MinarunLeavesNoSleep() {
        val marker = "1003"
        val script = "setsid sleep $marker & sleep $marker"
        val p = start(launcher("--timeout", "600") + proot("/bin/sh", "-c", script))
        try {
            val deadline = System.currentTimeMillis() + 15_000
            while (findProcesses(marker).size < 2) {
                if (System.currentTimeMillis() > deadline) fail("sandboxed sleeps never started")
                Thread.sleep(100)
            }
            val launcherPid = File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }.single {
                val args = cmdline(it)
                args.firstOrNull()?.endsWith("libminarun.so") == true && script in args
            }
            //The launcher was too late to clear the site:Relying on PRoot PDEATHSIGand PTRACE_O_EXITKILLTake away all sandbox processes
            Os.kill(launcherPid, OsConstants.SIGKILL)
            assertEquals("leftover sleep processes", emptyList<Int>(), awaitGone(marker, 3_000))
        } finally {
            p.destroyForcibly()
        }
    }

    @Test
    fun netOffBlocksTcp() {
        val r = run(launcher("--timeout", "20", "--net", "off") + proot("/bin/busybox", "nc", "-w", "2", "1.1.1.1", "80"))
        assertNotEquals(r.output, 0, r.exitCode)
        assertTrue(r.output, r.output.contains("Permission denied"))
    }

    @Test
    fun netOffAllowsUnixSocket() {
        //nc of busybox is not supported AF_UNIX;Use instead syslogd(exist /dev/log on bind)and logger(connect)Interoperability.
        // /dev/log is tied to a file in the cache directory,syslogd will replace it with a socket.
        val devLog = File(SandboxSmokeTest.prootTmp.parentFile, "minarun-test-devlog").apply { delete(); writeText("") }
        val script = "syslogd -n -O /tmp/minarun-unix.log & sleep 1; logger -t minaprobe hello-unix; sleep 1; " +
            "kill \$!; cat /tmp/minarun-unix.log; rm -f /tmp/minarun-unix.log"
        val r = run(
            launcher("--timeout", "20", "--net", "off") +
                proot("/bin/sh", "-c", script, extraBinds = listOf("${devLog.absolutePath}:/dev/log")),
        )
        assertTrue(r.output, r.output.contains("hello-unix"))
    }

    @Test
    fun netOnAllowsTcp() {
        assumeTrue("device has no network", hostReaches("1.1.1.1", 80))
        val script = "printf 'HEAD / HTTP/1.0\\r\\n\\r\\n' | nc -w 3 1.1.1.1 80"
        val r = run(launcher("--timeout", "20", "--net", "on") + proot("/bin/sh", "-c", script))
        assertTrue(r.output, r.output.contains("HTTP/"))
    }

    @Test
    fun oomScoreRaised() {
        val r = run(launcher("--timeout", "20") + proot("/bin/cat", "/proc/self/oom_score_adj"))
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("1000", r.output.trim())
    }

    @Test
    fun selfTestReportsSeccomp() {
        val r = run(listOf(SandboxSmokeTest.lib("libminarun.so"), "--self-test"))
        assertEquals(r.output, 0, r.exitCode)
        assertEquals(listOf("minarun ok", "seccomp ok"), r.output.trim().lines())
    }

    @Test
    fun prootWithoutLoaderIsRefused() {
        val r = run(launcher("--timeout", "20") + proot("/bin/true"), withLoader = false)
        assertEquals(r.output, 125, r.exitCode)
        assertTrue(r.output, r.output.trim().lines().last().startsWith("minarun: PROOT_LOADER missing"))
    }

    private data class Result(val exitCode: Int, val output: String, val elapsedMs: Long)

    private fun launcher(vararg options: String): List<String> =
        listOf(SandboxSmokeTest.lib("libminarun.so")) + options + "--"

    private fun proot(vararg command: String, extraBinds: List<String> = emptyList()): List<String> =
        listOf(
            SandboxSmokeTest.lib("libproot.so"), "--kill-on-exit", "--link2symlink", "-0",
            "-r", SandboxSmokeTest.rootfs.absolutePath,
        ) + ProotDevices.bindArgs() + listOf("-b", "/proc", "-b", "/sys") + extraBinds.flatMap { listOf("-b", it) } + listOf("-w", "/") + command

    private fun start(cmd: List<String>, withLoader: Boolean = true): Process {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().apply {
            clear()
            if (withLoader) put("PROOT_LOADER", SandboxSmokeTest.lib("libproot_loader.so"))
            put("PROOT_TMP_DIR", SandboxSmokeTest.prootTmp.absolutePath)
            put("HOME", "/root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("LANG", "C.UTF-8")
            put("TMPDIR", "/tmp")
        }
        return pb.start()
    }

    private fun run(cmd: List<String>, withLoader: Boolean = true): Result {
        val begin = System.nanoTime()
        val p = start(cmd, withLoader)
        var out = ""
        val reader = thread { out = p.inputStream.bufferedReader().readText() }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            fail("command timed out: $cmd")
        }
        val elapsedMs = (System.nanoTime() - begin) / 1_000_000
        reader.join()
        Log.i(TAG, "exit=${p.exitValue()} ${elapsedMs}ms cmd=$cmd output=$out")
        return Result(p.exitValue(), out, elapsedMs)
    }

    /**The identity of this app can see that there is a process in the command line parameter that is exactly equal to marker. */
    private fun findProcesses(marker: String): List<Int> =
        File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            dir.name.toIntOrNull()?.takeIf { cmdline(it).contains(marker) }
        }

    private fun cmdline(pid: Int): List<String> =
        runCatching { String(File("/proc/$pid/cmdline").readBytes(), Charsets.UTF_8).split('\u0000') }
            .getOrDefault(emptyList())

    private fun awaitGone(marker: String, withinMs: Long): List<Int> {
        val deadline = System.currentTimeMillis() + withinMs
        while (true) {
            val left = findProcesses(marker)
            if (left.isEmpty() || System.currentTimeMillis() > deadline) return left
            Thread.sleep(100)
        }
    }

    private fun hostReaches(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 3_000) }
    }.isSuccess

    private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        private const val TAG = "MinarunDeviceTest"

        @BeforeClass
        @JvmStatic
        fun prepare() = SandboxSmokeTest.prepareRootfs()
    }
}
