package com.meenai.mina.cap.sandbox

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.core.cap.sandbox.RootfsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.tukaani.xz.XZInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 *Sandbox native program smokes on real machine:Launcher self-test, run busybox and shell pipe under PRoot.
 *Decide"nativeLibraryDirinside PRoot +Can the "Loader" solution be established on a real machine? For the root file system, use the one that comes with the APK.
 */
@RunWith(AndroidJUnit4::class)
class SandboxSmokeTest {

    @Test
    fun busyboxEchoUnderProot() {
        val r = execInRootfs("/bin/busybox", "echo", "ok")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("ok", r.output.trim())
    }

    @Test
    fun shellPipelineWorks() {
        val r = execInRootfs("/bin/sh", "-c", "echo a | tr a b")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("b", r.output.trim())
    }

    private data class Result(val exitCode: Int, val output: String)

    private fun execInRootfs(vararg command: String): Result = exec(
        listOf(
            lib("libminarun.so"), "--",
            lib("libproot.so"), "--kill-on-exit", "--link2symlink", "-0",
            "-r", rootfs.absolutePath,
        ) + ProotDevices.bindArgs() + listOf("-b", "/proc", "-b", "/sys", "-w", "/") + command,
    )

    private fun exec(cmd: List<String>): Result {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().apply {
            clear()
            put("PROOT_LOADER", lib("libproot_loader.so"))
            put("PROOT_TMP_DIR", prootTmp.absolutePath)
            put("HOME", "/root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("LANG", "C.UTF-8")
            put("TMPDIR", "/tmp")
        }
        val p = pb.start()
        //Start a new thread to read the output,Otherwise, readText will always block when the command is stuck.,The timeout judgment becomes invalid
        var out = ""
        val reader = thread { out = p.inputStream.bufferedReader().readText() }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            fail("command timed out: $cmd")
        }
        reader.join()
        Log.i(TAG, "exit=${p.exitValue()} cmd=$cmd output=$out")
        return Result(p.exitValue(), out)
    }

    companion object {
        private const val TAG = "SandboxSmokeTest"
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        internal val nativeLibDir get() = context.applicationInfo.nativeLibraryDir
        internal val rootfs get() = File(context.filesDir, "sandbox-smoke")
        internal val prootTmp get() = File(context.cacheDir, "proot-tmp")

        internal fun lib(name: String) = File(nativeLibDir, name).absolutePath

        /**Use this set of directories to test the device of the root file system yourself.,With the real installed sandbox(files/sandbox)separate:Test without deleting or changing it. */
        internal val testDirs get() = SandboxDirs(File(context.filesDir, "sandbox-test"), File(context.cacheDir, "proot-tmp-test"), File(nativeLibDir), AndroidFdSys.gate, SandboxOwner.of(context).sandboxTree)

        /**Prepare [testDirs]:Unlock the root file system,Create the directory to be bound(If the skill directory is missing, PRoot will write a warning in the output.). */
        internal fun prepareTestSandbox(): SandboxDirs = testDirs.also {
            it.skills.mkdirs()
            it.tmp.mkdirs()
            ensureRootfs(it.rootfs)
        }

        @BeforeClass
        @JvmStatic
        fun prepareRootfs() {
            Log.i(TAG, "nativeLibraryDir=$nativeLibDir")
            File(nativeLibDir).listFiles()?.forEach { Log.i(TAG, "  ${it.name} ${it.length()} bytes") }
            prootTmp.mkdirs()
            ensureRootfs(rootfs)
        }

        /**Understand the running environment that comes with the APK [dest](Not connected to the Internet);Already solved versions of the same version will not be redone.[dest]It cannot be an installed sandbox. */
        internal fun ensureRootfs(dest: File) {
            check(dest.canonicalPath != SandboxDirs(context).rootfs.canonicalPath) { "tests must not unpack over the installed sandbox" }
            val marker = File(dest, ".smoke-ready")
            if (marker.isFile && marker.readText() == BundledRuntime.VERSION) {
                ProotDevices.prepareRootfsDev(dest, AndroidFdSys.gate)
                return
            }
            if (dest.exists()) RootfsExtractor.deleteTree(dest)
            context.assets.open(BundledRuntime.ASSET).use { RootfsExtractor.extractTar(XZInputStream(it.buffered()), dest) }
            ProotDevices.prepareRootfsDev(dest, AndroidFdSys.gate)
            marker.writeText(BundledRuntime.VERSION)
        }
    }
}
