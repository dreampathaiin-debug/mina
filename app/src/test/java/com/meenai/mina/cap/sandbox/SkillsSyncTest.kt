package com.meenai.mina.cap.sandbox

import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.core.workspace.SandboxTree
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillsSyncTest {
    @get:Rule val texts = EnglishTexts()

    private val tmp: File = Files.createTempDirectory("skills-sync").toFile()
    private val dirs = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache"), File(tmp, "lib"), JVM_GATE, SandboxTree())

    private val v1 = mapOf(
        "skills/report/scripts/manifest.json" to """[{"file":"scripts/tabulate.py","runnable":true}]""".toByteArray(),
        "skills/report/scripts/tabulate.py" to "print(1)".toByteArray(),
    )

    @Test fun copiesOnceAndReplacesWhenContentChanges() = runBlocking {
        SkillsSync.sync(dirs, v1)
        val py = File(dirs.skills, "skills/report/scripts/tabulate.py")
        assertEquals("print(1)", py.readText())
        //The packaging script has changed:Replace the whole with new ones,Don’t keep the old ones
        val v2 = mapOf("skills/report/scripts/manifest.json" to "[]".toByteArray(), "skills/other/scripts/x.sh" to "echo".toByteArray())
        SkillsSync.sync(dirs, v2)
        assertFalse(py.exists())
        assertTrue(File(dirs.skills, "skills/other/scripts/x.sh").isFile)
    }

    @Test fun tamperedFilesAreResynced() = runBlocking {
        SkillsSync.sync(dirs, v1)
        val py = File(dirs.skills, "skills/report/scripts/tabulate.py")
        //The command in the sandbox changed the script:Revert to the original state next time you sync
        py.writeText("import os; os.system('evil')")
        SkillsSync.sync(dirs, v1)
        assertEquals("print(1)", py.readText())
        //Also clear the extra files.
        val extra = File(dirs.skills, "skills/report/scripts/extra.py").apply { writeText("x") }
        SkillsSync.sync(dirs, v1)
        assertFalse(extra.exists())
        //Replacing it with a link is also considered inconsistent.
        py.delete()
        java.nio.file.Files.createSymbolicLink(py.toPath(), File(tmp, "elsewhere").toPath())
        SkillsSync.sync(dirs, v1)
        assertTrue(java.nio.file.Files.isRegularFile(py.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("print(1)", py.readText())
    }

    @Test fun pathsOutsideSkillsAreSkipped() = runBlocking {
        SkillsSync.sync(dirs, mapOf("../../escape.txt" to "x".toByteArray(), "skills/a/scripts/manifest.json" to "[]".toByteArray()))
        assertFalse(File(dirs.root, "escape.txt").exists())
        assertFalse(File(tmp, "files/escape.txt").exists())
        assertTrue(File(dirs.skills, "skills/a/scripts/manifest.json").isFile)
    }

    @Test fun manifestAndScriptsCheckedAgainstBundledCopy() = runBlocking {
        val synced = mutableListOf<Int>()
        val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() },
            sync = { synced += 1; SkillsSync.sync(dirs, v1) }, skillFiles = { v1 })
        sb.prepare()
        assertEquals(listOf("scripts/tabulate.py"), sb.scriptManifest("skills/report").map { it.file })
        assertTrue(sb.scriptManifest("skills/none").isEmpty())
        assertTrue(sb.scriptManifest("../../../etc").isEmpty())
        //List changed in sandbox:doesn't work,I read the packaged copy.
        File(dirs.skills, "skills/report/scripts/manifest.json").writeText("""[{"file":"scripts/evil.py","runnable":true}]""")
        assertEquals(listOf("scripts/tabulate.py"), sb.scriptManifest("skills/report").map { it.file })
        //modified script:After resynchronization, it will be consistent with the packaged one.,Can run as usual
        File(dirs.skills, "skills/report/scripts/tabulate.py").writeText("evil")
        assertTrue(sb.verifyScript("skills/report", "scripts/tabulate.py"))
        assertEquals("print(1)", File(dirs.skills, "skills/report/scripts/tabulate.py").readText())
        //Can't recover(Sync doesn't work)Just refuse;Scripts not included in the package will also be rejected.
        val stuck = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() }, skillFiles = { v1 })
        File(dirs.skills, "skills/report/scripts/tabulate.py").writeText("evil")
        assertFalse(stuck.verifyScript("skills/report", "scripts/tabulate.py"))
        assertFalse(sb.verifyScript("skills/report", "scripts/other.py"))
    }

    @Test fun cardLineForSandboxSteps() {
        assertEquals("Working on files: python3 sum.py", com.meenai.mina.ui.StatusCopy.sandboxCommand("python3   sum.py", network = false))
        assertEquals("Working on files: ${"x".repeat(40)}… · online", com.meenai.mina.ui.StatusCopy.sandboxCommand("x".repeat(50), network = true))
        assertEquals("Running a script", com.meenai.mina.ui.StatusCopy.sandboxScript(network = false))
        assertEquals("No. 3/10 pages", com.meenai.mina.ui.StatusCopy.sandboxProgress("No. 3/10 pages", network = false))
        //Zero-width characters and characters in the rewriting direction in the command do not appear on the card.
        assertEquals("Working on files: rm a.txt", com.meenai.mina.ui.StatusCopy.sandboxCommand("rm \u202Ea\u200B.txt", network = false))
    }
}
