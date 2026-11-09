package com.meenai.mina.cap.phone.a11y

import android.accounts.AccountManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.shell.MainScreenWatch
import com.meenai.mina.core.uistate.ActionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *Real machine test:The test package is temporarily registered as an account authenticator(See TestAuthenticatorService,It is turned off by default in the list.,Turn it on here and turn it off after testing.),
 *Test package has no desktop icon,It’s the same as the system’s account login service. Its full-screen login page has no dialog theme, no password box, and no
 * excludeFromRecents,The shape is the same as a normal App page;Only the article "The package is an account authenticator without a desktop icon" can recognize it.
 *Users need to handle it manually,Should stay on home screen,Cannot be moved to Mina's screen.
 */
@RunWith(AndroidJUnit4::class)
class AuthSpillDeviceTest {
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

    private val authenticatorService = "${DeviceSupport.TEST_PACKAGE}/${TestAuthenticatorService::class.java.name}"

    @After
    fun tearDown() {
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        if (main >= 0 && shell.packageOfTask(main) == DeviceSupport.TEST_PACKAGE) DeviceSupport.sh("input keyevent 4")
        //Shut back,Wait for it to go down from the authenticator table:Other pages moved back to the test package in the test cannot be used as account login pages.
        DeviceSupport.sh("pm disable $authenticatorService")
        DeviceSupport.pollUntil(10_000) { (!testAuthenticatorListed()).takeIf { it } }
        agent.release()
    }

    private fun testAuthenticatorListed(): Boolean {
        val am = AccountManager.get(InstrumentationRegistry.getInstrumentation().targetContext)
        return runCatching { am.authenticatorTypes.any { it.packageName == DeviceSupport.TEST_PACKAGE } }.getOrDefault(false)
    }

    /**Open the authenticator for the test package,Wait for it to appear in the system's account authenticator table,This use case only makes sense. */
    private fun registerTestAuthenticator(): Boolean {
        DeviceSupport.sh("pm enable $authenticatorService")
        return DeviceSupport.pollUntil(10_000) { testAuthenticatorListed().takeIf { it } } == true
    }

    /**The test package does not have a LAUNCHER entry(Not in the list),This is what the criterion requires. */
    private fun testPackageHasNoLauncher(): Boolean {
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(DeviceSupport.TEST_PACKAGE)
        return pm.queryIntentActivities(launcher, 0).isEmpty()
    }

    @Test
    fun authenticatorFullScreenPageStaysOnTheMainScreen() = runBlocking<Unit> {
        assertTrue("the test package has a launcher entry", testPackageHasNoLauncher())
        assertTrue("the test package is not registered as an account authenticator", registerTestAuthenticator())
        screen.ensureDisplay()
        val vd = agent.display.displayId
        DeviceSupport.sh(
            "am start --display $vd -n ${DeviceSupport.TEST_PACKAGE}/${SpillTestActivity::class.java.name}" +
                " --es ${SpillTestActivity.EXTRA_AUTH} ${FullScreenAuthTestActivity::class.java.name}",
        )
        val s = DeviceSupport.pollUntil(5_000) {
            runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open auth" } }
        }
        assertNotNull("spill page did not come up", s)
        val vdBefore = shell.topTaskId(vd)
        val since = android.os.SystemClock.uptimeMillis()
        val button = s!!.elements.first { it.name == "open auth" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        println("tap result: $r")

        //The service's process may be cold,The pop-up window will appear a second or two later.;Wait until it enters the assistant's record
        val b = DeviceSupport.pollUntil(5_000) {
            val arr = shell.mainArrivals(since)
            val pkgs = arr?.getStringArray(MainScreenWatch.KEY_PACKAGE)
            if (pkgs?.contains(DeviceSupport.TEST_PACKAGE) == true) arr else null
        }
        assertNotNull("no arrival on the main screen was reported for the auth page", b)
        delay(500)
        val now = screen.snapshot()
        delay(500)
        val opened = now.opened ?: (r as? ActionResult.Ok)?.opened
        val stillThere = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
            .any { it.root?.packageName?.toString() == DeviceSupport.TEST_PACKAGE }
        println("after: opened=$opened stillOnMain=$stillThere vd top=${shell.topTaskId(vd)} (before $vdBefore)")
        assertTrue("the authenticator page was moved off the user's screen", stillThere)
        assertEquals("Mina's screen changed", vdBefore, shell.topTaskId(vd))
        assertNotNull("the auth page was not reported to the model", opened)
        assertTrue("the auth page was reported as pulled back: $opened",
            !opened!!.pulledBack && opened.onUserScreen && opened.needsUser)
    }
}
