package com.meenai.mina.cap.phone.a11y

import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.uistate.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 *Real machine test:The page on the Mina screen opens another process through a service in another process. App(Same as opening from background),The system puts it on the home screen;
 *The result of the click will be reported as opened, and it will be moved back to Mina's screen.,Return to the original top of the home screen.
 *Which app to open is determined by instrument parameters spillTargetSpecify(Default system settings);Doesn't run when it already has a task(If you move it over and then destroy it on the screen, you will lose that task.).
 *If you install two apps, you will be asked which one to open first.:The question will not be moved and will remain on the home screen.,Report picker,onUserScreen,Tasks and other user selections;
 *Instrument parameters pickOptionTested when given when the user selects on the home screen(or back to turn off),Look at the selected one being moved back to Mina's screen and which one was reported.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenSpillDeviceTest {
    //Same as in mission,The screen size and density are the same as the main screen:The moved pages do not need to be rebuilt due to size changes.(The system's selection box will turn itself off when it is rebuilt.)
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
    private val target = InstrumentationRegistry.getArguments().getString("spillTarget") ?: SETTINGS

    @After
    fun tearDown() {
        //Before moving back,Move the one left on the home screen to Mina's screen,Destroyed together with the screen
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        //Failed midway and the problem remains on the home screen:Press return to turn it off
        screen.userPick.value?.let { p -> if (main >= 0 && shell.packageOfTask(main) == p.packageName) sh("input keyevent 4") }
        if (main >= 0 && shell.packageOfTask(main) == target) shell.moveTask(main, agent.display.displayId)
        agent.release()
    }

    /**Instrument parameters allowExisting=Also runs when true(That task will be moved over and closed with the screen.,Same as crossing out recent tasks). */
    private fun noTaskYet() {
        if (InstrumentationRegistry.getArguments().getString("allowExisting") == "true") return
        assumeFalse("$target already has a task",
            sh("dumpsys activity recents").lines().any { val l = it.trim(); l.startsWith("* Recent") && " A=" in l && l.substringAfter(" A=").substringAfter(':').startsWith(target) })
    }

    private suspend fun spillPage(): com.meenai.mina.core.uistate.ScreenSnapshot {
        screen.ensureDisplay()
        sh("am start --display ${agent.display.displayId} -n $TEST_PACKAGE/${SpillTestActivity::class.java.name} --es ${SpillTestActivity.EXTRA_TARGET} $target")
        val s = pollUntil(5_000) { runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open elsewhere" } } }
        assertNotNull("spill page did not come up", s)
        return s!!
    }

    /**control:Inject a click directly onto Mina's screen without Mina's actions.,The opened one falls on the home screen.(This is the phenomenon that needs to be repaired). */
    @Test
    fun withoutTheWatchTheLaunchLandsOnTheMainScreen() = runBlocking<Unit> {
        noTaskYet()
        val s = spillPage()
        val vd = agent.display.displayId
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val b = s.elements.first { it.name == "open elsewhere" }
        val vdBefore = shell.topTaskId(vd)
        assertTrue(shell.injectTap(vd, b.centerX, b.centerY))
        val landed = pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it != mainBefore } }
        println("before: main top $mainBefore -> $landed (${landed?.let { shell.packageOfTask(it) }}), Mina screen top $vdBefore -> ${shell.topTaskId(vd)}")
        assertNotNull("launch did not land on the main screen", landed)
        assertEquals("Mina's screen changed", vdBefore, shell.topTaskId(vd))
    }

    @Test
    fun appOpenedOnTheMainScreenIsPulledBack() = runBlocking<Unit> {
        noTaskYet()
        val s = spillPage()
        val vd = agent.display.displayId
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val button = s.elements.first { it.name == "open elsewhere" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        assertTrue("expected Ok, got $r", r is ActionResult.Ok)
        val opened = (r as ActionResult.Ok).opened
        assertNotNull("tap did not report opened: $r", opened)
        val now = screen.snapshot()
        println("spill result: $r; on Mina's screen (${now.packageName}): ${now.elements.filter { it.clickable }.map { it.name }}")
        if (!opened!!.picker) {
            assertTrue("not pulled back: $opened", opened.pulledBack)
            assertEquals(target, shell.packageOfTask(shell.topTaskId(vd)))
            assertEquals("main screen top did not come back", mainBefore, pollUntil(3_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == mainBefore } })
            return@runBlocking
        }
        //The system asks on the user screen which one to open.:Don’t move, don’t reopen,Stay on the home screen and wait for user selection
        assertTrue("question was moved: $opened", !opened.pulledBack && opened.onUserScreen)
        val chooserTask = shell.topTaskId(Display.DEFAULT_DISPLAY)
        assertEquals("question is not on the main screen", opened.packageName, shell.packageOfTask(chooserTask))
        assertNotNull("run is not waiting for the user", screen.userPick.value)
        assertEquals("Mina's screen changed", SpillTestActivity::class.java.name, shell.topActivity(vd))
        //Instrument parameters pickOption=<Which number,from 0>or back:At this time, the test is as a user,Click that option on the home screen(Or press Return to close the question)
        val pick = InstrumentationRegistry.getArguments().getString("pickOption") ?: return@runBlocking
        if (pick == "back") sh("input keyevent 4")
        else {
            val label = r.chooser?.options?.firstOrNull()?.label
            val nodes = ScreenReader.contentWindows(DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY))
                .filter { it.root?.packageName?.toString() == opened.packageName }
                .flatMap { w -> ScreenReader.read(listOf(w), agent.display.width, agent.display.height).entries.map { it.element } }
                .filter { it.clickable && it.name == label }
            val option = nodes.getOrNull(pick.toInt())
            assertNotNull("no option $pick: ${nodes.map { it.name }}", option)
            sh("input tap ${option!!.centerX} ${option.centerY}")
        }
        val settled = screen.awaitUserPick()
        //The selected ones need to be handled by the user personally.:Now test when the user clicks continue
        if (settled is AndroidScreen.PickResult.NeedsUser) screen.afterUserStep(settled)
        val after = screen.snapshot()
        val top = shell.topTaskId(vd)
        println("after the user ($pick): $settled; opened=${after.opened}; Mina's screen top ${shell.packageOfTask(top)} user ${shell.userOfTask(top)}; " +
            "main top ${shell.topTaskId(Display.DEFAULT_DISPLAY)} (before $mainBefore)")
        if (pick == "back") assertTrue("not reported closed: ${after.opened}", after.opened?.closed == true)
        else {
            assertTrue("not pulled back: ${after.opened}", after.opened?.pulledBack == true && (after.opened?.inProfile != null || after.opened?.byUser == true))
            assertEquals(target, shell.packageOfTask(top))
        }
        assertEquals("main screen top did not come back", mainBefore, pollUntil(3_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == mainBefore } })
    }
}
