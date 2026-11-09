package com.meenai.mina.cap.phone.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.core.uistate.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 *Real machine:There is another copy of the user available(Avatar, work information)hour,Open an app installed there on Mina’s screen,The top task belongs to that user,The home screen remains unchanged.
 *Which one to measure is determined by the parameters.:`-Pandroid.testInstrumentationRunnerArguments.copyPackage=<package name>`(Can give again copyUser=<User number>);
 *If not, use the first one listed. The system first asks which one to open,From the opening step, click on the options and identify the one you want.
 *Skip if there is no other user or the requested App is not there. only open,Don’t do anything in the app.
 */
@RunWith(AndroidJUnit4::class)
class AppCopyDeviceTest {
    private val agent = DeviceSupport.newAgent()

    @After
    fun tearDown() = agent.release()

    @Test
    fun launchesTheCopyInItsUserOnMinasDisplay() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val users = shell.appCopyUsers()?.getIntArray(ShellService.KEY_COPY_USERS)
        assumeTrue("no usable other user on this device: ${shell.appCopyUsers()?.getString(ShellService.KEY_COPY_SUMMARY)}", users != null && users.isNotEmpty())
        val args = InstrumentationRegistry.getArguments()
        val wantPackage = args.getString("copyPackage")
        val wantUser = args.getString("copyUser")?.toIntOrNull()
        val copies = agent.screen.appCopies()
        val copy = copies.firstOrNull { (wantPackage == null || it.packageName == wantPackage) && (wantUser == null || it.userId == wantUser) }
        assumeTrue("no such copy (asked for ${wantPackage ?: "any"} in user ${wantUser ?: "any"}); listed: $copies", copy != null)
        copy!!
        val main = DeviceSupport.mainScreenState(shell)
        val r = agent.screen.openAppCopy(copy.packageName, copy.userId)
        assertTrue("open ${copy.packageName} in user ${copy.userId}: $r", r is ActionResult.Ok)
        r as ActionResult.Ok
        assertEquals("result must say which copy is showing: $r", copy.userId, r.profileUser)
        val top = shell.topTaskId(agent.display.displayId)
        assertEquals(copy.packageName, shell.packageOfTask(top))
        assertEquals(copy.userId, shell.userOfTask(top))
        assertEquals(copy.userId, agent.screen.target.value?.userId)
        assertEquals("main screen changed", main, DeviceSupport.mainScreenState(shell))
        //Users who are not available will not be started.
        assertEquals(ShellService.START_USER_NOT_ALLOWED, shell.startAppInUser(agent.display.displayId, copy.packageName, 99_999, false))
    }
}
