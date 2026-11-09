package com.meenai.mina.cap.phone.a11y

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.display.AgentDisplay
import com.meenai.mina.cap.phone.display.LazyAgentDisplay
import com.meenai.mina.shell.IMinaShell
import com.meenai.mina.cap.phone.shell.ShellBinding
import com.meenai.mina.cap.phone.PhoneServices
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.meenai.mina.a11y.MinaAccessibilityService

/**Real machine test sharing:shell connections, accessibility services, virtual screens AndroidScreen. Requires the built-in assistant to be running(Paired, wireless debugging is on)And Mina accessibility services are turned on. */
object DeviceSupport {
    const val SETTINGS = "com.android.settings"
    const val TEST_PACKAGE = "com.meenai.mina.test"
    private var connection: ShellBinding? = null
    private val phone get() = PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext)

    /**
     *Share the shell connection in the process with the App and always occupy it:Binding and then unbinding each other will cause the party that unbinds first to end the shell service.,
     *The binder in the other party's hand becomes invalid.
     */
    @Synchronized
    fun shell(): IMinaShell {
        val c = connection ?: phone.sharedShell.acquire().also {
            connection = it
            rebindAccessibility()
        }
        return awaitShell(c)
    }

    /**Wait for the connection to get the living assistant;Fail with current status before waiting,Prompt to pair first. After the assistant is killed, it will take a while for the watchdog to restart.,The dead binder may still be held before the disconnect callback arrives. */
    fun awaitShell(c: ShellBinding, timeoutMs: Long = 30_000): IMinaShell = runBlocking {
        withTimeoutOrNull(timeoutMs) { c.shell.filterNotNull().first { it.asBinder().pingBinder() } }
    } ?: error(
        "Mina's phone helper is not running (status=${phone.shellControl.status.value}): " +
            "pair it once in Mina (Set up mobile operations) with Wireless debugging on, keep Wireless debugging on, then rerun",
    )

    /**Service dropped(The previous test ended the binding of this process, etc.)Prompt the system to rebind first,Wait for it to connect. */
    fun service(): ScreenAccessibility {
        if (ScreenAccessibility.instance.value == null) rebindAccessibility()
        return runBlocking {
            withTimeoutOrNull(10_000) { ScreenAccessibility.instance.filterNotNull().first() }
        } ?: error("Mina accessibility service is not enabled")
    }

    class Agent(val display: AgentDisplay, val screen: AndroidScreen) {
        fun release() = display.release()
    }

    fun newAgent(): Agent {
        val shell = shell()
        val d = AgentDisplay(shell, 720, 1600, 320)
        return Agent(d, AndroidScreen(service(), shell, ready(d)))
    }

    /**Already built screen:Test yourself by building a screen first(Maybe put it up first App),No need to wait to read the screen phone_launch_app. */
    fun ready(d: AgentDisplay): LazyAgentDisplay = LazyAgentDisplay(d.width, d.height) { d }.also { runBlocking { it.get() } }

    /**
     *When starting the test, the system will forcefully stop the system. App,The accessibility service is then recorded as crashed and will no longer be automatically reconnected.
     *When it's opened, take it off the open list and put it back,Prompt the system to rebind;It won't move if it's not turned on.
     */
    private fun rebindAccessibility() {
        if (ScreenAccessibility.instance.value != null) return
        val key = "enabled_accessibility_services"
        val mina = "${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/${MinaAccessibilityService::class.java.name}"
        val enabled = sh("settings get secure $key").trim().split(':')
        if (mina !in enabled) return
        try {
            sh("settings put secure $key ${enabled.filter { it != mina }.joinToString(":").ifEmpty { "\"\"" }}")
            Thread.sleep(500)
        } finally {
            sh("settings put secure $key ${enabled.joinToString(":")}")
        }
    }

    fun sh(cmd: String): String {
        //Do not suppress other accessibility services,Otherwise Mina's service will be suspended during the test period
        val ua = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val pfd = ua.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /**Open the page in the test package on the virtual screen(Give by category,The class name is consistent with the one registered in the list). */
    fun startTestActivity(displayId: Int, activity: Class<out android.app.Activity>) {
        sh("am start --display $displayId -n $TEST_PACKAGE/${activity.name}")
    }

    /**
     *Home screen status:The top-level focus screen of the system, the focus window and focus Activity of screen 0, and the top-most task of the home screen.
     *,None of this should change.
     */
    fun mainScreenState(shell: IMinaShell): String {
        val topDisplay = sh("dumpsys window").lines().map { it.trim() }
            .firstOrNull { it.startsWith("mTopFocusedDisplayId=") }
        checkNotNull(topDisplay) { "mTopFocusedDisplayId not found in dumpsys window" }
        val section = sh("dumpsys window displays").substringAfter("Display: mDisplayId=0", "")
            .substringBefore("Display: mDisplayId=").lines().map { it.trim() }
        val focus = section.firstOrNull { it.startsWith("mCurrentFocus=") }
        checkNotNull(focus) { "mCurrentFocus not found for display 0" }
        val focusedApp = section.firstOrNull { it.startsWith("mFocusedApp=") }
            ?.let { Regex("u0 (\\S+)").find(it)?.groupValues?.get(1) }
        return "$topDisplay $focus focusedApp=$focusedApp topTask=${shell.topTaskId(android.view.Display.DEFAULT_DISPLAY)}"
    }

    fun <T : Any> pollUntil(timeoutMs: Long, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            Thread.sleep(100)
        }
        return block()
    }
}
