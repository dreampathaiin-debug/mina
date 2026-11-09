package com.meenai.mina.cap.web

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.meenai.mina.core.cap.web.cdp.CdpDiscovery
import com.meenai.mina.core.cap.web.cdp.CdpSession
import com.meenai.mina.core.cap.web.cdp.CdpStream
import com.meenai.mina.core.cap.web.cdp.CdpTransport
import com.meenai.mina.core.cap.web.cdp.evaluate
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.cap.web.cdp.WebDevtools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs
import com.meenai.mina.cap.phone.PhoneServices

/**
 *Real machine test:When you have a DevTools session,Screen Reading combines the element table of the page script and the accessibility tree.
 *test page(fusion.html)There are ordinary buttons, divs with only listeners, open and closed shadow buttons, buttons in same-origin frames and cross-origin frames.
 * (Cross-origin framework uses the second resource domain name fixtures2.androidplatform.net gives,Do not use the Internet;Native loopback plaintext HTTP is blocked by network security policy,Not useful).
 *DevTools session:When the entrance of the product cannot be provided,Test yourself by connecting via this process's abstract socket(Find the target by marking the page). If you can't connect, skip it.
 */
@RunWith(Parameterized::class)
class ElementFusionDeviceTest(private val mobile: Boolean) {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    /**Ben tests his own copy:Changing the session source and connection method will not affect other tests. */
    private val devtools = WebDevtools(PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext).hub::openDevtoolsSocket)
    private lateinit var host: WebHost
    private lateinit var tab: WebTab
    private lateinit var screen: WebScreen
    private var session: CdpSession? = null
    private val savedProvider = devtools.provider

    private fun fixture(name: String) = "https://appassets.androidplatform.net/assets/web-fixtures/$name"

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val dpi = ctx.resources.displayMetrics.densityDpi
        instr.runOnMainSync {
            WebView.setWebContentsDebuggingEnabled(true)
            host = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            val main = WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).build()
            val other = WebViewAssetLoader.Builder().setDomain("fixtures2.androidplatform.net").addPathHandler("/assets/", assets).build()
            tab = WebTab(ctx, "t1", "task-1", listOf(main, other), devtools = devtools)
            host.attach(tab)
        }
        screen = WebScreen({ tab }, host, SystemClock::uptimeMillis)
        //Desktop version(Simulation wide viewport, input coordinates have multiples)Run once with the mobile version
        devtools.setSiteMode(SITE, mobile)
        runBlocking {
            assertTrue("load", tab.load(fixture("fusion.html")))
            //When an error occurs in the test page's own script,The following assertions will be falsely reported as product problems.:Make sure it has finished running first
            assertTrue("fixture script did not finish", js("window.__ready === true") == "true")
        }
        if (runBlocking { devtools.awaitSession(tab) } == null && devtools.session(tab) == null) {
            session = runBlocking { connectOwn() }
            devtools.provider = { t -> if (t === tab) session else savedProvider(t) }
        }
        assumeTrue("no DevTools session for the tab", devtools.session(tab) != null)
        //Only the product's own session has site mode.:Confirm that this is really the mode to be tested
        if (session == null) {
            val vw = runBlocking { js("innerWidth") }.toDouble()
            assertTrue("mobile=$mobile innerWidth=$vw", if (mobile) vw < 1000 else vw >= 1000)
        }
    }

    @After
    fun tearDown() {
        devtools.setSiteMode(SITE, mobile = false)
        session?.close()
        instr.runOnMainSync {
            tab.destroy()
            host.close()
        }
    }

    @Test
    fun mergedSnapshotListsShadowFrameAndListenerElements() = runBlocking<Unit> {
        val s = readFused()
        val plain = s.named("Plain")
        val lis = s.named("Listener only")
        assertTrue("Listener evidence can be found:$lis", lis.clickable && lis.role == "button")
        s.named("Shadow")
        s.named("Closed shadow")
        s.named("SameChild")
        val cross = s.named("CrossChild")
        assertTrue("$cross", cross.clickable && cross.role == "button")
        assertNotNull("Elements in the cross-source framework are registered with node numbers", screen.backendNodeId(cross.ref, s.snapshotId))
        assertNotNull(screen.backendNodeId(plain.ref, s.snapshotId))
        //The center point is consistent with the amount on the page(Cross-origin in-frame button in frame 10,From 10 120x40)
        val css = JSONObject(js("(function(){var f=document.getElementById('cross').getBoundingClientRect();return {x:f.left+70,y:f.top+30,vw:innerWidth};})()"))
        val k = s.width / css.getDouble("vw")
        val ex = (css.getDouble("x") * k).toInt()
        val ey = (css.getDouble("y") * k).toInt()
        assertTrue("cross center ${cross.centerX},${cross.centerY} expected ~$ex,$ey", abs(cross.centerX - ex) <= 8 && abs(cross.centerY - ey) <= 8)
        s.elements.forEach { assertTrue("$it", it.centerX in 0 until s.width && it.centerY in 0 until s.height) }
    }

    @Test
    fun tapsReachShadowAndCrossOriginElements() = runBlocking<Unit> {
        for ((name, logged) in listOf("CrossChild" to "xclick", "Closed shadow" to "closed", "Shadow" to "shadow", "Listener only" to "lis")) {
            val s = readFused()
            val r = screen.tapRef(s.named(name).ref, s.snapshotId)
            assertTrue("$name: $r", r is ActionResult.Ok)
            val log = waitLog(logged)
            assertTrue("$name: log=$log", logged in log)
        }
    }

    @Test
    fun fusionStaysWithinBudget() = runBlocking<Unit> {
        screen.snapshot()
        val times = (1..5).map {
            val t = SystemClock.elapsedRealtime()
            screen.snapshot()
            SystemClock.elapsedRealtime() - t
        }
        Log.i(TAG, "snapshot with fusion ms=$times")
        assertTrue("$times", times.max() < 2_500)
    }

    /**After the cross-source framework is loaded and its button appears in the merged result, read again.;Wait up to 5 seconds. */
    private suspend fun readFused(): ScreenSnapshot {
        val end = SystemClock.elapsedRealtime() + 5_000
        var s = screen.snapshot()
        while (s.elements.none { it.name == "CrossChild" } && SystemClock.elapsedRealtime() < end) {
            delay(200)
            s = screen.snapshot()
        }
        Log.i(TAG, "elements: ${s.elements.map { "${it.ref} ${it.role} ${it.name} (${it.centerX},${it.centerY})" }}")
        return s
    }

    private fun ScreenSnapshot.named(name: String): UiElement {
        val e = elements.firstOrNull { it.name == name }
        assertNotNull("$name not in ${elements.map { it.role to it.name }}", e)
        return e!!
    }

    private suspend fun waitLog(want: String): String {
        val end = SystemClock.elapsedRealtime() + 3_000
        var log = ""
        while (SystemClock.elapsedRealtime() < end) {
            log = js("JSON.stringify(window.__log)")
            if (want in log) return log
            delay(100)
        }
        return log
    }

    private suspend fun js(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    /**DevTools abstract socket of WebView in this process;Mark the page,This label is found by target-by-target evaluation. */
    private suspend fun connectOwn(): CdpSession? {
        val socket = "webview_devtools_remote_${Process.myPid()}"
        val transport = LocalTransport()
        val marker = UUID.randomUUID().toString()
        js("window.__fusionMarker='$marker'")
        val targets = runCatching { CdpDiscovery.list(transport, socket) }.getOrElse {
            Log.w(TAG, "devtools socket unavailable", it)
            return null
        }
        for (t in targets) {
            val ws = t.webSocketDebuggerUrl ?: continue
            if (t.type != "page") continue
            val s = runCatching { CdpSession.connect(transport, socket, CdpDiscovery.webSocketPath(ws)) }.getOrNull() ?: continue
            val v = runCatching { s.evaluate("window.__fusionMarker||''", timeoutMs = 3_000)["value"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (v == marker) return s
            s.close()
        }
        return null
    }

    private class LocalTransport : CdpTransport {
        override fun open(endpoint: String): CdpStream {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(endpoint, LocalSocketAddress.Namespace.ABSTRACT))
            return object : CdpStream {
                override val input: InputStream = s.inputStream
                override val output: OutputStream = s.outputStream
                override fun close() {
                    runCatching { s.shutdownInput() }
                    runCatching { s.close() }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ElementFusionTest"
        private const val SITE = "appassets.androidplatform.net"

        @JvmStatic
        @Parameterized.Parameters(name = "mobile={0}")
        fun modes() = listOf(false, true)
    }
}
