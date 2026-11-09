package com.meenai.mina.cap.sandbox

import com.meenai.mina.core.workspace.SandboxTree
import com.meenai.mina.core.cap.sandbox.SandboxPathContract
import com.meenai.mina.core.cap.sandbox.SandboxPort
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** AndroidSandboxPath rules run on the JVM:Change directory to temporary directory,Can't afford the process. */
class AndroidSandboxContractTest : SandboxPathContract() {
    private val tmp: File = Files.createTempDirectory("android-sandbox").toFile()
    private fun dirs() = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache/proot-tmp"), File(tmp, "lib"), JVM_GATE, SandboxTree())
    override fun sandbox(): SandboxPort = AndroidSandbox(dirs(), { SandboxStatus.Ready(false) }, { emptyList() })
    override fun outsideDir(): File = File(tmp, "outside").apply { mkdirs() }

    //Reading files, searching files, and checking upload paths do not have permission to write the tree.:If they easily create a workspace directory,When the reset is halfway through the deletion, the last rmdir will be ENOTEMPTY
    @Test fun lookingUpPathsNeverCreatesTheWorkspace() {
        val sb = AndroidSandbox(dirs(), { SandboxStatus.Ready(false) }, { emptyList() })
        assertNotNull(sb.hostRoot("fresh", "/workspace/a.txt", forWrite = false))
        sb.uploadCheck("fresh", "/workspace/a.txt")
        assertFalse(dirs().workspace("fresh").exists())
        assertFalse(dirs().work.exists())
    }
}
