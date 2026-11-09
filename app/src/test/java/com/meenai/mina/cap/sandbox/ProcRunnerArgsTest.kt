package com.meenai.mina.cap.sandbox

import com.meenai.mina.core.workspace.SandboxTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

class ProcRunnerArgsTest {
    private val dirs = SandboxDirs(File("/data/m/files/sandbox"), File("/data/m/cache/proot-tmp"), File("/data/app/lib/arm64"), JVM_GATE, SandboxTree())
    private val ws = dirs.workspace("conv1")

    private fun List<String>.hasPair(a: String, b: String) = indices.any { it + 1 < size && this[it] == a && this[it + 1] == b }

    @Test fun argvOrderAndBinds() {
        val argv = ProcRunner.argv(dirs, ws, "sub/dir", 60, network = false, standIns = listOf("stat"),
            command = listOf("/bin/sh", "-lc", "echo hi"), devices = listOf("-b", "/dev/null"))
        assertEquals("/data/app/lib/arm64/libminarun.so", argv[0])
        assertTrue(argv.hasPair("--timeout", "60"))
        assertTrue(argv.hasPair("--net", "off"))
        val proot = argv.indexOf("/data/app/lib/arm64/libproot.so")
        assertEquals("--", argv[proot - 1])
        for (flag in listOf("--kill-on-exit", "--link2symlink", "-0")) assertTrue(flag, argv.indexOf(flag) > proot)
        assertTrue(argv.hasPair("-r", "/data/m/files/sandbox/rootfs"))
        assertTrue(argv.hasPair("-b", "/dev/null"))
        assertFalse("whole /dev must not be bound", argv.hasPair("-b", "/dev"))
        assertTrue(argv.hasPair("-b", "/data/m/files/sandbox/proc/stat:/proc/stat"))
        assertTrue(argv.hasPair("-b", "/data/m/files/sandbox/work/c-conv1:/workspace"))
        assertTrue(argv.hasPair("-b", "/data/m/files/sandbox/skills:/opt/mina/skills"))
        assertTrue(argv.hasPair("-w", "/workspace/sub/dir"))
        assertEquals(listOf("/bin/sh", "-lc", "echo hi"), argv.takeLast(3))
        assertTrue(argv.indexOf("-w") < argv.size - 3)

        val root = ProcRunner.argv(dirs, ws, "", 5, network = true, standIns = emptyList(), command = listOf("true"), devices = emptyList())
        assertTrue(root.hasPair("-w", "/workspace"))
        assertTrue(root.hasPair("--net", "on"))
    }

    @Test fun envIsMinimal() {
        val env = ProcRunner.env(dirs, null, null, "conv1")
        assertEquals(setOf("PROOT_LOADER", "PROOT_TMP_DIR", "HOME", "PATH", "LANG", "TMPDIR", "TZ", "MINA_CONVERSATION"), env.keys)
        assertEquals("/data/app/lib/arm64/libproot_loader.so", env["PROOT_LOADER"])
        assertEquals("/data/m/cache/proot-tmp", env["PROOT_TMP_DIR"])
        assertTrue(env.keys.none { it == "LD_PRELOAD" || it.startsWith("ANDROID_") })
        val bridged = ProcRunner.env(dirs, "mina-bridge-x", "tok", "conv1")
        assertEquals("mina-bridge-x", bridged["MINA_BRIDGE"]); assertEquals("tok", bridged["MINA_TOKEN"])
    }

    /**Agent only appears when given:Both sets of uppercase and lowercase variables are given.(Some tools only recognize uppercase letters),The local address is still directly connected. */
    @Test fun proxyIsPassedOnlyWhenGiven() {
        val none = ProcRunner.env(dirs, null, null, "conv1", proxy = "")
        assertTrue(none.keys.none { it.lowercase().endsWith("_proxy") })
        val p = "http://u:pw@10.0.0.2:8080"
        val env = ProcRunner.env(dirs, null, null, "conv1", proxy = p)
        for (k in listOf("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")) assertEquals(k, p, env[k])
        assertEquals(ProcRunner.NO_PROXY, env["no_proxy"])
        assertEquals(ProcRunner.NO_PROXY, env["NO_PROXY"])
    }

    @Test fun envReadsPhoneTimezoneForEveryCommand() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Shanghai", "America/New_York", "Asia/Kathmandu", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(zone, ProcRunner.env(dirs, null, null, "conv1")["TZ"])
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun fixedOffsetTimezonesKeepTheirSign() {
        //POSIX's TZ GMT+8 means 8 hours behind UTC,Can't put GMT+08:00 is given as is.
        val cases = mapOf(
            "GMT+08:00" to "Etc/GMT-8", "GMT-05:00" to "Etc/GMT+5", "GMT+05:45" to "<+0545>-05:45",
            "GMT-03:30" to "<-0330>03:30", "GMT" to "UTC", "UTC" to "UTC",
        )
        for ((id, tz) in cases) assertEquals(id, tz, ProcRunner.timezone { TimeZone.getTimeZone(id).toZoneId() })
        assertEquals("Etc/GMT-14", ProcRunner.timezone { java.time.ZoneOffset.ofHours(14) })
        assertEquals("UTC", ProcRunner.timezone { throw java.time.DateTimeException("no zone") })
    }

    @Test fun oddConversationIdsStayInsideWork() {
        for (id in listOf("../../etc", "a/b", "", "Dialogue 1")) {
            val f = dirs.workspace(id)
            assertEquals(id, dirs.work, f.parentFile)
            assertFalse(id, f.name.contains(".."))
        }
        assertEquals(dirs.workspace("x/y"), dirs.workspace("x/y"))
        //Simple names and hashed names are divided under two prefixes:「h-..." will not hit the hash directory of other conversations
        val hashed = dirs.workspace("a/b").name
        assertTrue(hashed, hashed.startsWith("h-"))
        assertEquals("c-$hashed", dirs.workspace(hashed).name)
    }
}
