package com.meenai.mina.cap.sandbox

import com.meenai.mina.chat.ConversationSummary
import com.meenai.mina.core.workspace.SandboxTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**Used space:Size does not follow the link;Clean up, delete by handle, do not follow links;The dialogue when there is a mission running is unclear. */
class SandboxUsageReaderTest {
    private val tmp: File = Files.createTempDirectory("usage").toFile()
    private val dirs = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache"), File(tmp, "lib"), JVM_GATE, SandboxTree())
    private val active = mutableListOf<String>()
    private val reader = SandboxUsageReader(dirs, { listOf(ConversationSummary("a", "Check express delivery", 0), ConversationSummary("b", "Organize tables", 0)) }, { active })

    /**A large file outside the workspace,There is a link to it in the workspace(and the directory in which it is located)link. */
    private val outside = File(tmp, "outside").apply { mkdirs() }
    private val big = File(outside, "big.bin").apply { writeBytes(ByteArray(50_000)) }

    private fun workspace(id: String, bytes: Int): File = dirs.workspace(id).apply {
        mkdirs()
        File(this, "inbox").mkdirs()
        File(this, "inbox/a.txt").writeBytes(ByteArray(bytes))
        Files.createSymbolicLink(File(this, "link-file").toPath(), big.toPath())
        Files.createSymbolicLink(File(this, "link-dir").toPath(), outside.toPath())
    }

    @Test fun sizesDoNotFollowLinksAndNameTheChat() {
        workspace("a", 300)
        workspace("b", 1_000)
        File(dirs.work, "c-gone").apply { mkdirs(); File(this, "x").writeBytes(ByteArray(10)) }
        File(dirs.rootfs, "bin").apply { mkdirs(); File(this, "sh").writeBytes(ByteArray(70)) }
        active += "a"
        val u = reader.read()
        assertEquals(listOf(1_000L, 300L, 10L), u.workspaces.map { it.bytes })
        assertEquals(listOf("Organize tables", "Check express delivery", null), u.workspaces.map { it.title })
        assertEquals(listOf(false, true, false), u.workspaces.map { it.busy })
        assertNull("The dialogue was deleted and I can’t recognize it.", u.workspaces.last().conversationId)
        assertEquals(70L, u.runtime)
        assertEquals(70L + 1_310L, u.total)
    }

    @Test fun clearingDeletesOnlyTheWorkspaceNotWhatLinksPointTo() = runBlocking {
        val b = workspace("b", 1_000)
        assertTrue(reader.clear(b.name))
        assertFalse(b.exists())
        assertTrue("", big.isFile)
        assertEquals(50_000L, big.length())
    }

    @Test fun aChatWithARunningTaskIsNotCleared() = runBlocking {
        val a = workspace("a", 300)
        val b = workspace("b", 1_000)
        active += "a"
        assertFalse(reader.clear(a.name))
        assertTrue(File(a, "inbox/a.txt").isFile)
        assertEquals("One was left running", 1, reader.clearAll())
        assertTrue(a.exists())
        assertFalse(b.exists())
        assertTrue(big.isFile)
    }
}
