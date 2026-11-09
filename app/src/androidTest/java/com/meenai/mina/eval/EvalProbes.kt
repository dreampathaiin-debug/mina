package com.meenai.mina.eval

import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.AndroidScreen
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.core.eval.APP_CAPABILITIES
import com.meenai.mina.core.eval.AppCapabilities
import com.meenai.mina.core.eval.PROBE_ALLOWLIST
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.cap.phone.display.AgentDisplay
import com.meenai.mina.core.cap.web.WebUrlPolicy
import com.meenai.mina.cap.web.PageScript
import com.meenai.mina.cap.web.WebHost
import com.meenai.mina.cap.web.WebSites
import com.meenai.mina.cap.web.WebTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 *The reviewer's own read-only view of the device:Whitelist probe, preconditions, open the app on the virtual screen to find words after the task is completed.
 * [sh]Execute command as shell;[loggedIn]It is a package name list file manually marked "logged in"(One package name per line).
 */
class EvalProbes(private val sh: (String) -> String, private val loggedIn: File) {

    /**Execute only [PROBE_ALLOWLIST]command in;No commands can be written into the task file. */
    fun run(name: String): String {
        val cmd = PROBE_ALLOWLIST[name] ?: error("probe not in allowlist: $name")
        return sh(cmd).trim()
    }

    /**
     *This phone can handle certain things App(See [APP_CAPABILITIES]):Check by standard Intent,The ones processed by default are ranked first.
     *Commands are spelled out only from the ability table,The task file cannot be written. No standard Intent has the ability to return null.
     */
    fun appCandidates(capability: String): List<String> = APP_CAPABILITIES[capability].orEmpty()
        .flatMap { i -> AppCapabilities.commands(i).flatMap { cmd -> AppCapabilities.packagesIn(runCatching { sh(cmd) }.getOrDefault("")) } }
        .distinct()

    /**
     * `installed:<package name>`,`app:<ability>`,`signedIn:<Package name or website>`(old way of writing `login:<package name>`,`site:<hostname>`).
     *Login status:The website only recognizes the "user has logged in in Mina" in the site registration form.(Logged in in the settings "Website Login");App only recognizes manual marking([loggedIn]),
     *The evaluator does not determine the login status of the App for the user.(There is no reliable method for all apps). Questions that are not logged in will be skipped.,The reason is written as "not logged in".
     */
    fun requirementMet(req: String): Pair<Boolean, String> {
        val kind = req.substringBefore(':')
        val pkg = req.substringAfter(':', "")
        when (kind) {
            "site" -> return siteSignedIn(pkg)?.let { if (it) true to "" else false to "Not logged in:$pkg has not been logged in in the "Website Login" setting" }
                ?: (false to "The precondition is written incorrectly:$req")
            "signedIn" -> return if (siteSignedIn(pkg) == true || appMarked(pkg)) true to ""
                else false to "Not logged in:$pkg(The website must be logged in in the "Website Login" setting,App should be marked in ${loggedIn.name})"
            "app" -> return if (appCandidates(pkg).isNotEmpty()) true to "" else false to "There is nothing on this phone that can handle "$pkg" App"
        }
        if (!PACKAGE.matches(pkg)) return false to "The precondition is written incorrectly:$req"
        return when (kind) {
            "installed" ->
                if (sh("pm path $pkg").contains("package:")) true to "" else false to "Not pretending $pkg"
            "login" -> if (appMarked(pkg)) true to "" else false to "Not logged in:$pkg is not marked as logged in(AZZ)"
            else -> false to "Unknown preconditions:$req"
        }
    }

    /**Manually marked as logged in App(One package name per line). */
    private fun appMarked(pkg: String) = runCatching { loggedIn.readLines().map { it.trim() } }.getOrDefault(emptyList()).contains(pkg)

    /**The website has been logged in in the "Website Login" setting:Only the same registrable domain name counts(When the registrable domain name cannot be recognized, the host name must be exactly the same.);It is null if the writing method is not a website. */
    private fun siteSignedIn(site: String): Boolean? {
        val host = WebUrlPolicy.host("https://$site") ?: return null
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val want = WebUrlPolicy.registrable(host) ?: host
        return WebSites.registry(ctx).list().any { it.loggedInByUser && (WebUrlPolicy.registrable(it.host) ?: it.host) == want }
    }

    /**Cold boot on a new virtual screen [pkg](Or use it to open deep links),Read the screen until you see [text]or timeout. */
    suspend fun screenHas(pkg: String, text: String, deepLink: String?): Boolean {
        require(PACKAGE.matches(pkg)) { "bad package: $pkg" }
        val shell = DeviceSupport.shell()
        val metrics = DisplayMetrics().also {
            InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val display = AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        try {
            val screen = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(display))
            if (deepLink == null) {
                if (screen.openApp(pkg, cold = true) !is ActionResult.Ok) return false
            } else {
                //The link is only used when data is passed to am,You cannot use quotation marks to spell out other commands.
                require('\'' !in deepLink && '\n' !in deepLink) { "bad deep link" }
                sh("am start --display ${display.displayId} -a android.intent.action.VIEW -d '$deepLink' -p $pkg")
            }
            val want = text.replace(WS, "")
            val end = System.currentTimeMillis() + SCREEN_WAIT_MS
            while (true) {
                val seen = screen.snapshot().elements.joinToString("") { it.name }.replace(WS, "")
                if (want in seen) return true
                if (System.currentTimeMillis() > end) return false
                delay(500)
            }
        } finally {
            display.release()
        }
    }

    /**
     *Open in a new background web tab [url],Read entire page of text(at most [WEB_PAGES]part),try to find [text](ignore whitespace).
     *Use mina web configuration,The login status is the same as that used by Agent.
     */
    suspend fun webHas(url: String, text: String): Boolean {
        require(WebUrlPolicy.checkNavigable(url) == null) { "bad url: $url" }
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        val metrics = DisplayMetrics().also {
            ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        lateinit var host: WebHost
        lateinit var tab: WebTab
        withContext(Dispatchers.Main) {
            host = WebHost(ctx, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            tab = WebTab(ctx, "probe", "eval")
            host.attach(tab)
        }
        try {
            tab.load(url)
            val page = PageScript(tab)
            page.install()
            val want = text.replace(WS, "")
            var offset = 0
            repeat(WEB_PAGES) {
                val o = runCatching { Json.parseToJsonElement(page.readPage(30_000, offset)).jsonObject }.getOrNull() ?: return false
                val got = (o["text"] as? JsonPrimitive)?.content.orEmpty().replace(WS, "")
                if (want in got) return true
                offset = (o["nextOffset"] as? JsonPrimitive)?.intOrNull ?: return false
            }
            return false
        } finally {
            withContext(Dispatchers.Main + NonCancellable) {
                tab.destroy()
                host.close()
            }
        }
    }

    private companion object {
        const val WEB_PAGES = 5
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        val WS = Regex("\\s+")
        const val SCREEN_WAIT_MS = 10_000L
    }
}
