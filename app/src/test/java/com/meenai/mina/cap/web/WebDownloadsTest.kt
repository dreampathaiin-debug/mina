package com.meenai.mina.cap.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.meenai.mina.core.cap.web.DownloadAddressPolicy
import com.meenai.mina.cap.sandbox.JVM_GATE
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import com.meenai.mina.core.workspace.SandboxTree

class WebDownloadsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var ws: File
    private val cookieCalls = mutableListOf<String>()

    /**Test server on this machine:Just let it go,Other address photo machine rules(Not allowed on this machine or LAN). */
    private fun allowServer(url: String): String? =
        if (url.startsWith(server.url("/").toString())) null else DownloadAddressPolicy.refusal(url)

    private fun downloads(maxBytes: Long = 1L shl 20, maxFiles: Int = 50, maxTotalBytes: Long = 1L shl 30, timeoutMs: Long = 60_000) = WebDownloads(
        ws, JVM_GATE, SandboxTree(), OkHttpClient(), cookies = { url -> cookieCalls += url; "sid=abc" }, scope = scope, maxBytes = maxBytes,
        maxFiles = maxFiles, maxTotalBytes = maxTotalBytes, timeoutMs = timeoutMs, addressRefusal = ::allowServer, dns = Dns.SYSTEM)

    @Before
    fun setUp() {
        ws = tmp.newFolder("c-conv")
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun WebDownloads.awaitDone(path: String, ms: Long = 5_000) = runBlocking {
        val end = System.currentTimeMillis() + ms
        while (list().first { it.file == path }.status == "running" && System.currentTimeMillis() < end) kotlinx.coroutines.delay(20)
        list().first { it.file == path }
    }

    @Test
    fun downloadLandsInWorkspaceWithCookiesUaAndReferer() {
        server.enqueue(MockResponse().setBody("hello pdf").setHeader("Content-Type", "application/pdf"))
        val d = downloads()
        val url = server.url("/files/report.pdf").toString()
        val path = d.start(url, "TestUA/1", null, "application/pdf", "https://shop.example.com/page")
        assertEquals("/workspace/downloads/report.pdf", path)
        val info = d.awaitDone(path)
        assertEquals("done", info.status)
        assertEquals(9L, info.bytes)
        assertEquals("application/pdf", info.mime)
        assertEquals("hello pdf", File(ws, "downloads/report.pdf").readText())
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("sid=abc", req.getHeader("Cookie"))
        assertEquals("TestUA/1", req.getHeader("User-Agent"))
        assertEquals("https://shop.example.com/page", req.getHeader("Referer"))
    }

    @Test
    fun maliciousFilenameSanitized() {
        server.enqueue(MockResponse().setBody("x"))
        val d = downloads()
        val path = d.start(server.url("/dl").toString(), "UA", "attachment; filename=\"../../x.sh\"", null, null)
        assertEquals("/workspace/downloads/x.sh", path)
        d.awaitDone(path)
        assertTrue(File(ws, "downloads/x.sh").isFile)
        assertFalse(File(ws, "x.sh").exists())
    }

    @Test
    fun nameRules() {
        assertEquals("report.pdf", WebDownloads.fileName("attachment; filename*=UTF-8''report.pdf; filename=\"old.pdf\"", "https://a/b", null))
        assertEquals("quarterly report.pdf", WebDownloads.fileName("attachment; filename*=UTF-8''%E5%AD%A3%E5%BA%A6%E6%8A%A5%E5%91%8A.pdf", "https://a/b", null))
        assertEquals("invoice.pdf", WebDownloads.fileName(null, "https://a.example.com/x/invoice.pdf?token=1", null))
        //Control characters, bidirectional control characters(Can disguise exe as pdf), the leading dot, and the backslash path are all removed.
        assertEquals("evilfdp.exe", WebDownloads.fileName("attachment; filename=\"evil‮fdp.exe\"", "https://a/b", null))
        assertEquals("a b.txt", WebDownloads.fileName("attachment; filename=\"a\u0000\u0007 b.txt\"", "https://a/b", null))
        assertEquals("x.sh", WebDownloads.fileName("attachment; filename=\"..\\\\..\\\\x.sh\"", "https://a/b", null))
        assertEquals("env", WebDownloads.fileName("attachment; filename=\".env\"", "https://a/b", null))
        assertEquals("download", WebDownloads.fileName("attachment; filename=\"..\"", "https://a.example.com/", null))
        //The name is too long and will be truncated by bytes.,Keep extension
        val long = WebDownloads.fileName("attachment; filename=\"AZZ.pdf\"", "https://a/b", null)
        assertTrue(long, long.endsWith(".pdf") && long.toByteArray().size <= 200)
    }

    @Test
    fun sameNameGetsANumber() {
        repeat(3) { server.enqueue(MockResponse().setBody("x$it")) }
        val d = downloads()
        val paths = (1..3).map { d.start(server.url("/a.txt").toString(), "UA", null, null, null) }
        assertEquals(listOf("/workspace/downloads/a.txt", "/workspace/downloads/a (2).txt", "/workspace/downloads/a (3).txt"), paths)
    }

    @Test
    fun oversizeAbortedAndPartialDeleted() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4096))))
        val d = downloads(maxBytes = 1024)
        val path = d.start(server.url("/big.bin").toString(), "UA", null, null, null)
        val info = d.awaitDone(path)
        assertEquals("failed", info.status)
        assertEquals("too_large", info.error)
        assertFalse(File(ws, "downloads/big.bin").exists())
    }

    @Test
    fun declaredOversizeRefusedBeforeReading() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(4096))))
        val d = downloads(maxBytes = 1024)
        val info = d.awaitDone(d.start(server.url("/big2.bin").toString(), "UA", null, null, null))
        assertEquals("too_large", info.error)
    }

    @Test
    fun statusRunningThenDone() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64 * 1024))).throttleBody(8 * 1024, 100, TimeUnit.MILLISECONDS))
        val d = downloads()
        val path = d.start(server.url("/slow.bin").toString(), "UA", null, null, null)
        assertEquals("running", d.list().single().status)
        assertEquals("done", d.awaitDone(path, 10_000).status)
        assertEquals(64L * 1024, d.list().single().bytes)
    }

    @Test
    fun httpErrorIsAFailedDownload() {
        server.enqueue(MockResponse().setResponseCode(404))
        val d = downloads()
        val info = d.awaitDone(d.start(server.url("/missing.pdf").toString(), "UA", null, null, null))
        assertEquals("failed", info.status)
        assertEquals("http_404", info.error)
        assertFalse(File(ws, "downloads/missing.pdf").exists())
    }

    /**only TLS /,Moreover, the address rules still passed before changing the route.;Normally, if it cannot be connected, it will not be replaced. */
    @Test
    fun pageFallbackOnlyForTlsFailures() {
        val host = "127.0.0.1"
        val local = { url: String -> if (url.toHttpUrlOrNull()?.host in setOf(host, server.url("/").host)) null else DownloadAddressPolicy.refusal(url) }
        val d = WebDownloads(ws, JVM_GATE, SandboxTree(), OkHttpClient(), { null }, scope, addressRefusal = local, dns = Dns.SYSTEM)
        var asked = 0
        val viaPage: suspend (Long) -> Pair<ByteArray, String?>? = { asked++; "%PDF".toByteArray() to "application/pdf" }
        //A port that only returns clear text:When connecting to it using https, the handshake fails.
        val plain = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName(host))
        val answering = Thread {
            runCatching { while (true) plain.accept().use { it.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()) } }
        }.apply { isDaemon = true; start() }
        val tls = d.awaitDone(d.start("https://$host:${plain.localPort}/a.pdf", "UA", null, null, null, viaPage = viaPage))
        plain.close(); answering.interrupt()
        assertEquals(tls.error, "done", tls.status); assertEquals(1, asked)
        //No one is listening on the port:Common network errors,bring error type,Don't change the way
        val closed = MockWebServer().apply { start() }.let { s -> s.url("/b.pdf").toString().also { s.shutdown() } }
        val net = d.awaitDone(d.start(closed, "UA", null, null, null, viaPage = viaPage))
        assertEquals("failed", net.status); assertEquals(1, asked)
        assertTrue(net.error!!, net.error!!.startsWith("network_error (") && "Exception" in net.error!!)
    }

    @Test
    fun onlyWebAddressesAreFetched() {
        val d = downloads()
        val path = d.start("file:///etc/passwd", "UA", null, null, null)
        assertEquals("failed", d.list().single { it.file == path }.status)
        assertTrue(cookieCalls.isEmpty())
    }

    @Test
    fun savedBytesFromBlobAndDataUrls() {
        val d = downloads()
        val blob = d.save("blob:https://a.example.com/123", null, "text/csv", "a,b\n1,2".toByteArray())
        assertEquals("/workspace/downloads/download.csv", blob)
        assertEquals("done", d.list().single { it.file == blob }.status)
        val data = d.dataUrl("data:text/plain;base64,aGVsbG8=", "attachment; filename=\"hi.txt\"")
        assertEquals("/workspace/downloads/hi.txt", data)
        assertEquals("hello", File(ws, "downloads/hi.txt").readText())
        val big = WebDownloads(ws, JVM_GATE, SandboxTree(), OkHttpClient(), { null }, scope, maxBlobBytes = 4)
        val refused = big.save("blob:x", null, null, ByteArray(10))
        assertEquals("too_large", big.list().single { it.file == refused }.error)
    }

    /**The name given to the page on the link(<a download>)Use it for every download,Cleaning rules remain the same;The name in the response header takes precedence,URL last. */
    @Test
    fun pageGivenNamesReachEveryDownloadPath() {
        val d = downloads()
        // blob:and data:No response headers,Don’t use the URL as a name:given by the page
        assertEquals("/workspace/downloads/report.csv", d.save("blob:https://a.example.com/1", null, "text/csv", "a".toByteArray(), suggested = "report.csv"))
        assertEquals("/workspace/downloads/note.txt", d.dataUrl("data:text/plain;base64,aGVsbG8=", null, suggested = "note.txt"))
        //The name given on the page will still be cleaned.:remove path
        assertEquals("/workspace/downloads/x.sh", d.save("blob:https://a.example.com/2", null, null, "a".toByteArray(), suggested = "../../x.sh"))
        // http:The name given on the page is ranked before the URL and after the response header.
        server.enqueue(MockResponse().setBody("1"))
        server.enqueue(MockResponse().setBody("2"))
        assertEquals("/workspace/downloads/monthly report.pdf", d.start(server.url("/dl?id=1").toString(), "UA", null, "application/pdf", null, suggested = "monthly report.pdf"))
        assertEquals("/workspace/downloads/from-header.pdf",
            d.start(server.url("/dl?id=2").toString(), "UA", "attachment; filename=\"from-header.pdf\"", null, null, suggested = "page.pdf"))
        //Ask the page for your name before registering:Asking this moment awaitNamedwaiting,Just ask and count
        val before = d.count()
        d.withPageName({ kotlinx.coroutines.delay(100); "later.bin" }) { name -> d.save("blob:https://a.example.com/3", null, null, "b".toByteArray(), name) }
        runBlocking { d.awaitNamed(2_000) }
        assertEquals(listOf("/workspace/downloads/later.bin"), d.since(before).map { it.file })
        //The page cannot ask(Error)Also download,use another name
        val before2 = d.count()
        d.withPageName({ error("page gone") }) { name -> d.save("blob:https://a.example.com/4", null, "text/csv", "c".toByteArray(), name) }
        runBlocking { d.awaitNamed(2_000) }
        assertEquals(listOf("/workspace/downloads/download.csv"), d.since(before2).map { it.file })
    }

    @Test
    fun symlinkedDownloadsDirIsRefused() {
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(File(ws, "downloads").toPath(), outside.toPath())
        server.enqueue(MockResponse().setBody("x"))
        val d = downloads()
        val info = d.awaitDone(d.start(server.url("/a.txt").toString(), "UA", null, null, null))
        assertEquals("failed", info.status)
        assertEquals("unsafe_path", info.error)
        assertTrue(outside.list()!!.isEmpty())
    }

    @Test
    fun everyRedirectHopIsChecked() {
        //Jump to LAN or other ports of this machine:Don't follow
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://192.168.1.1/router.cfg"))
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "http://127.0.0.1:1/x"))
        val d = downloads()
        val a = d.awaitDone(d.start(server.url("/a.bin").toString(), "UA", null, null, null))
        assertEquals("blocked_redirect", a.error)
        val b = d.awaitDone(d.start(server.url("/b.bin").toString(), "UA", null, null, null))
        assertEquals("blocked_redirect", b.error)
        assertTrue(File(ws, "downloads").list()!!.isEmpty())
        //Jumps on the same server follow as usual,And press the jump address band Cookie
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final/c.txt"))
        server.enqueue(MockResponse().setBody("ok"))
        val c = d.awaitDone(d.start(server.url("/c.txt").toString(), "UA", null, null, null))
        assertEquals("done", c.status)
        assertTrue(cookieCalls.any { it.endsWith("/final/c.txt") })
    }

    @Test
    fun privateAddressesAreNotFetchedAtAll() {
        val d = WebDownloads(ws, JVM_GATE, SandboxTree(), OkHttpClient(), { null }, scope)
        for (u in listOf("http://127.0.0.1:${server.port}/x", "http://localhost:${server.port}/x", "http://10.0.0.2/x", "http://[::1]/x")) {
            d.start(u, "UA", null, null, null)
            assertEquals(u, "blocked_address", d.list().last().error)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun perTaskQuotaAndTimeout() {
        repeat(2) { server.enqueue(MockResponse().setBody("12345")) }
        val d = downloads(maxFiles = 2)
        val p1 = d.start(server.url("/1.txt").toString(), "UA", null, null, null)
        val p2 = d.start(server.url("/2.txt").toString(), "UA", null, null, null)
        val p3 = d.start(server.url("/3.txt").toString(), "UA", null, null, null)
        assertEquals("done", d.awaitDone(p1).status)
        assertEquals("done", d.awaitDone(p2).status)
        assertEquals("download_limit", d.list().single { it.file == p3 }.error)

        server.enqueue(MockResponse().setBody("x".repeat(10)))
        val small = downloads(maxTotalBytes = 4)
        assertEquals("download_limit", small.awaitDone(small.start(server.url("/big.txt").toString(), "UA", null, null, null)).error)
        assertFalse(File(ws, "downloads/big.txt").exists())

        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64 * 1024))).throttleBody(1024, 200, TimeUnit.MILLISECONDS))
        val slow = downloads(timeoutMs = 500)
        val info = slow.awaitDone(slow.start(server.url("/slow2.bin").toString(), "UA", null, null, null), 10_000)
        assertEquals("timed_out", info.error)
        assertFalse(File(ws, "downloads/slow2.bin").exists())
    }

    @Test
    fun namesAreCappedAt80Characters() {
        val n = WebDownloads.fileName("attachment; filename=\"${"a".repeat(150)}.pdf\"", "https://a/b", null)
        assertEquals(80, n.length)
        assertTrue(n.endsWith(".pdf"))
        val cjk = WebDownloads.fileName("attachment; filename=\"AZZ.pdf\"", "https://a/b", null)
        assertTrue(cjk, cjk.codePointCount(0, cjk.length) <= 80 && cjk.endsWith(".pdf"))
    }

    @Test
    fun dataUrlLengthCheckedBeforeDecoding() {
        val d = WebDownloads(ws, JVM_GATE, SandboxTree(), OkHttpClient(), { null }, scope, maxBlobBytes = 30)
        d.dataUrl("data:text/plain;base64," + "A".repeat(100), null)
        assertEquals("too_large", d.list().last().error)
        d.dataUrl("data:text/plain," + "a".repeat(100), null)
        assertEquals("too_large", d.list().last().error)
        val ok = d.dataUrl("data:text/plain;base64,aGVsbG8=", "attachment; filename=\"h.txt\"")
        assertEquals("done", d.list().single { it.file == ok }.status)
    }

    @Test
    fun hardLinkedDownloadNameIsNeverWrittenThrough() {
        val outside = tmp.newFolder("outside2")
        val private = File(outside, "db").apply { writeText("keep") }
        File(ws, "downloads").mkdirs()
        Files.createLink(File(ws, "downloads/a.txt").toPath(), private.toPath())
        server.enqueue(MockResponse().setBody("new"))
        val d = downloads()
        val path = d.start(server.url("/a.txt").toString(), "UA", null, null, null)
        assertEquals("/workspace/downloads/a (2).txt", path) //Existing names are never overwritten
        d.awaitDone(path)
        assertEquals("keep", private.readText())
    }
}
