package com.meenai.mina.selfcheck

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.about.Diagnostics
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.core.selfcheck.CheckStatus
import com.meenai.mina.core.selfcheck.SelfCheckReport
import com.meenai.mina.core.selfcheck.SelfCheckRunner
import com.meenai.mina.core.selfcheck.SelfCheckState
import com.meenai.mina.settings.SystemStatusMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *Real machine:Run the complete compatibility self-check,It’s the same as clicking “Start Self-Test” in the settings. [AndroidSelfCheck].
 *Make sure the built-in assistant is running and Mina’s accessibility is on.;Report hit logcat(Label MinaSelfCheck). By default, it only requires running to completion and each item to have a conclusion.;
 *bring `-e selfcheck.strict true`It is also required that none of them fail.
 */
@RunWith(AndroidJUnit4::class)
class SelfCheckDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val strict = InstrumentationRegistry.getArguments().getString("selfcheck.strict") == "true"

    @Test
    fun wholeSelfCheckCompletes() = runBlocking<Unit> {
        //Like other real-device tests, the shared assistant connection is first occupied to facilitate barrier-free reconnection.(The system will be forced to stop when starting the test. App)
        DeviceSupport.shell()
        DeviceSupport.service()
        val monitor = SystemStatusMonitor(context, PhoneServices.of(context).shellControl)
        val runner = SelfCheckRunner(AndroidSelfCheck(context) { monitor.refresh(); monitor.status.value })
        val s = withContext(Dispatchers.Default) { runner.run() }
        val report = SelfCheckReport.render(s, Diagnostics::sanitize)
        report.lines().forEach { Log.i(TAG, it) }

        assertEquals(SelfCheckState.Phase.Done, s.phase)
        assertTrue(report, s.results.all { it.status.final })
        assertTrue(report, s.elapsedMs < SelfCheckRunner.TOTAL_BUDGET_MS + 30_000)
        if (strict) assertTrue(report, s.results.none { it.status == CheckStatus.Fail })
    }

    private companion object {
        const val TAG = "MinaSelfCheck"
    }
}
