package com.meenai.mina.cap.phone.shell

import android.os.Looper
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**in reality shellCall production code under uid;It does not depend on whether the running assistant is still an old version. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class ShellClipboardDeviceTest {
    @Test
    fun shellCanRoundTripChineseClipboardAndRestoreOriginal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val classpath = listOf(instrumentation.targetContext, instrumentation.context)
            .joinToString(":") { it.applicationInfo.sourceDir }
        // executeShellCommandnot pass shell:Environment variables are passed using env,Can't write VAR=…and redirect
        val output = sh("env CLASSPATH=$classpath app_process / ${ShellClipboardProbe::class.java.name}")
        assertTrue(output, output.lineSequence().any { it == ShellClipboardProbe.PASSED })
    }
}

/** app_processLoad production code from the APK under test,It does not depend on whether the running assistant is still an old version. */
object ShellClipboardProbe {
    const val PASSED = "shell-clipboard-passed"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            check(Process.myUid() == ShellContext.SHELL_UID)
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val clipboard = ShellClipboard(ShellContext.create())
            val originalText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            val shell = ShellService(ShellContext.SHELL_UID)
            try {
                val text = "Mina Chinese input regression"
                val result = shell.setClipText(text)
                check(result == ShellService.CLIP_SET) { "setClipText returned $result" }
                check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text)
            } finally {
                //Preservation of reused production code/reduction,Do not output user clipboard contents.
                shell.restoreClip()
            }
            check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == originalText)
            println(PASSED)
            System.out.flush()
            //Not leaving System.exit:The thread left by the clipboard service will make it stuck.,Just waiting for the test
            Runtime.getRuntime().halt(0)
        } catch (t: Throwable) {
            t.printStackTrace()
            System.err.flush()
            Runtime.getRuntime().halt(1)
        }
    }
}
