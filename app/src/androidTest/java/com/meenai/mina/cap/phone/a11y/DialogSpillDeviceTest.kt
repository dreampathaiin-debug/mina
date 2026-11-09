package com.meenai.mina.cap.phone.a11y

import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.shell.MainScreenWatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *Real machine test:The background process opens a dialog-style page on the home screen(Login verification and account confirmation are pop-up windows that require users to click on the home screen to confirm.).
 *This kind of pop-up window should be left on the home screen for users;Mina's screen shouldn't be covered by it,Nor should it be moved to the background screen.
 */
@RunWith(AndroidJUnit4::class)
class DialogSpillDeviceTest {
    //Same as in mission,The screen size and density are the same as the main screen:The moved pages do not need to be rebuilt due to size changes.
    private val agent = run {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val m = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val shell = DeviceSupport.shell()
        val d = com.meenai.mina.cap.phone.display.AgentDisplay(shell, m.widthPixels, m.heightPixels, m.densityDpi)
        DeviceSupport.Agent(d, AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(d)))
    }
    private val screen get() = agent.screen
    private val shell = DeviceSupport.shell()

    @After
    fun tearDown() {
        //When failed, the pop-up window may remain on the home screen.:turn it off
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        if (main >= 0 && shell.packageOfTask(main) == DeviceSupport.TEST_PACKAGE) DeviceSupport.sh("input keyevent 4")
        agent.release()
    }

    /**Types, packages, and scope of windows on the home screen:Dialog-style windows are significantly smaller than the entire screen. */
    private fun dumpMainWindows(label: String) {
        val service = DeviceSupport.service()
        for (w in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val b = Rect()
            w.getBoundsInScreen(b)
            println("$label window type=${w.type} pkg=${w.root?.packageName} bounds=$b")
        }
    }

    @Test
    fun dialogOnTheMainScreenIsLeftForTheUser() = runBlocking<Unit> {
        //Let’s first take a look at the window of this kind of dialog box on the home screen.(Without Mina's actions)
        DeviceSupport.sh("am start -n ${DeviceSupport.TEST_PACKAGE}/${DialogSpillTestActivity::class.java.name}")
        DeviceSupport.pollUntil(5_000) {
            shell.topActivity(Display.DEFAULT_DISPLAY)?.takeIf { it.contains("DialogSpillTestActivity") }
        }
        dumpMainWindows("dialog")
        DeviceSupport.sh("input keyevent 4")
        delay(500)

        screen.ensureDisplay()
        val vd = agent.display.displayId
        DeviceSupport.sh(
            "am start --display $vd -n ${DeviceSupport.TEST_PACKAGE}/${SpillTestActivity::class.java.name}" +
                " --es ${SpillTestActivity.EXTRA_DIALOG} ${DialogSpillTestActivity::class.java.name}",
        )
        val s = DeviceSupport.pollUntil(5_000) {
            runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open dialog" } }
        }
        assertNotNull("spill page did not come up", s)
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val vdBefore = shell.topTaskId(vd)
        val since = SystemClock.uptimeMillis()
        val button = s!!.elements.first { it.name == "open dialog" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        println("tap result: $r")

        //Identify the pop-up task that pops up on the home screen from the assistant's record.(The task number remains unchanged after being moved)
        val b = shell.mainArrivals(since)
        val packages = b?.getStringArray(MainScreenWatch.KEY_PACKAGE)
        val tasks = b?.getIntArray(MainScreenWatch.KEY_TASK)
        val dialogTask = packages?.indices?.firstOrNull { packages[it] == DeviceSupport.TEST_PACKAGE }?.let { tasks?.get(it) }
        assertNotNull("no arrival on the main screen was reported for the popup", dialogTask)
        dumpMainWindows("after action")

        //Give Mina a chance to work on this task that pops up on the home screen(Didn't catch up with the action,It will be supplemented when reading the screen)
        delay(1_500)
        val now = screen.snapshot()
        delay(500)
        val opened = now.opened ?: (r as? com.meenai.mina.core.uistate.ActionResult.Ok)?.opened
        val stillThere = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
            .any { it.root?.packageName?.toString() == DeviceSupport.TEST_PACKAGE }
        println(
            "after: opened=$opened main top=${shell.topTaskId(Display.DEFAULT_DISPLAY)} " +
                "dialogTask=$dialogTask stillOnMain=$stillThere vd top=${shell.topTaskId(vd)} (before $vdBefore, main before $mainBefore)",
        )
        //The pop-up window is still on the home screen,The user can then click Confirm
        assertTrue("the dialog was moved off the user's screen", stillThere)
        //Mina’s screen is still the same page
        assertEquals("Mina's screen changed", vdBefore, shell.topTaskId(vd))
        assertNotNull("the popup was not reported to the model", opened)
        assertEquals(DeviceSupport.TEST_PACKAGE, opened!!.packageName)
        assertTrue("the popup was reported as pulled back: $opened", !opened.pulledBack && opened.onUserScreen && opened.needsUser)
    }
}
