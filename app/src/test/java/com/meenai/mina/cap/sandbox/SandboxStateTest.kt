package com.meenai.mina.cap.sandbox

import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.core.workspace.SandboxTree
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SandboxStateTest {
    @get:Rule val texts = EnglishTexts()

    private val tmp: File = Files.createTempDirectory("sandbox-state").toFile()
    private val dirs = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache/proot-tmp"), File(tmp, "lib"), JVM_GATE, SandboxTree())
    private fun state(abis: List<String> = listOf("arm64-v8a")) = SandboxState(dirs, File(tmp, "files/sandbox-settings.json")) { abis }

    @Test
    fun networkDefaultsOff() {
        val s = state()
        assertFalse(s.networkAllowed)
        assertEquals(SandboxStatus.NotInstalled, s.status.value)
        s.networkAllowed = true
        assertTrue("persisted", state().networkAllowed)
    }

    @Test
    fun notArm64IsUnsupported() {
        assertTrue(state(listOf("x86_64")).status.value is SandboxStatus.Unsupported)
    }

    @Test
    fun unfinishedInstallIsNotReady() {
        val s = state()
        assertNull(s.completedStep())
        s.markStep(InstallStep.Extract)
        //Process killed mid-installation:Cannot be ready when rereading
        val again = state()
        assertEquals(InstallStep.Extract, again.completedStep())
        assertTrue(again.status.value is SandboxStatus.Broken)
    }
}
