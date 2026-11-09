package com.meenai.mina.chat

import com.meenai.mina.core.agent.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun persistsAcrossInstances() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("Check the weather", "", "succeeded"))
        a.append(id, Turn("Book tickets", "Verification code\n\"make life difficult for\"", "failed"))
        val b = ConversationStore(dir)
        assertEquals(listOf(Turn("Check the weather", "", "succeeded"), Turn("Book tickets", "Verification code\n\"make life difficult for\"", "failed")), b.turns(id))
        assertEquals(emptyList<Turn>(), b.turns(b.newConversation()))
        assertNotEquals(id, b.newConversation())
    }

    /**The page left running in the background(site)And the reason why it was shut down later was saved with that round.;These two fields will not be written in rounds where no pages are left. */
    @Test fun persistsKeptPage() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("play course,Don't close it", "Started playing", "succeeded", runId = "r1", keptPage = "course.example.com"))
        a.append(id, Turn("Thanks", "You're welcome", "succeeded", runId = "r2"))
        assertEquals(listOf("course.example.com", null), ConversationStore(dir).turns(id).map { it.keptPage })
        a.updateTurn(id, "r1") { it.copy(keptPageClosed = "expired") }
        assertEquals("expired", ConversationStore(dir).turns(id).first().keptPageClosed)
        assertFalse("keptPage" in java.io.File(dir, "$id.json").readText().substringAfter("Thanks"))
    }

    /**The ability groups used in this round will be saved accordingly.(The next round starts accordingly.);If it has not been used, do not write this field. */
    @Test fun persistsUsedGroups() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("Open settings", "alright", "succeeded", groups = listOf("phone", "web")))
        a.append(id, Turn("Thanks", "You're welcome", "succeeded"))
        assertEquals(listOf(listOf("phone", "web"), emptyList()), ConversationStore(dir).turns(id).map { it.groups })
        assertFalse("groups" in java.io.File(dir, "$id.json").readText().substringAfter("Thanks"))
    }

    /**The reply interrupted by the interruption is saved in one-to-one correspondence with the interruption.;Do not write this field in uninterrupted rounds.,The old file is read as empty. */
    @Test fun persistsInterruptedReplies() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("Check the weather", "It will be cloudy tomorrow", "succeeded", steers = listOf("Change to check tomorrow", "Let's take a look the day after tomorrow"), interruptedReplies = listOf("It's sunny today", "")))
        a.append(id, Turn("Thanks", "You're welcome", "succeeded", steers = listOf("good")))
        val read = ConversationStore(dir).turns(id)
        assertEquals(listOf(listOf("It's sunny today", ""), emptyList()), read.map { it.interruptedReplies })
        assertEquals(listOf("Change to check tomorrow", "Let's take a look the day after tomorrow"), read.first().steers)
        assertFalse("interruptedReplies" in java.io.File(dir, "$id.json").readText().substringAfter("Thanks"))
    }

    /**Replies that have been finished before interrupting are also saved one-to-one.;If not, this field is not written. */
    @Test fun persistsFinishedReplies() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("Check the weather", "It will be cloudy tomorrow", "succeeded", steers = listOf("Change to check tomorrow"), finishedReplies = listOf("It's sunny today,Maximum 25 degrees.")))
        a.append(id, Turn("Thanks", "You're welcome", "succeeded", steers = listOf("good")))
        assertEquals(listOf(listOf("It's sunny today,Maximum 25 degrees."), emptyList()), ConversationStore(dir).turns(id).map { it.finishedReplies })
        assertFalse("finishedReplies" in java.io.File(dir, "$id.json").readText().substringAfter("Thanks"))
    }

    /**Delete conversation:Session files, titles, moved aside damaged files, and temporary files are all deleted.,Not in the list anymore,Other conversations don’t move;After deleting, post the changes. */
    @Test fun deleteRemovesTheConversationAndItsSidecars() = kotlinx.coroutines.runBlocking {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        val other = store.newConversation()
        store.append(id, Turn("Check the weather", "", "succeeded"))
        store.setTitle(id, "")
        store.setPinned(id, true, at = 5L)
        java.io.File(dir, "$id.json.corrupt-1").writeText("x")
        java.io.File(dir, "$id.json.tmp").writeText("x")
        store.append(other, Turn("Book tickets", "alright", "succeeded"))
        val changes = mutableListOf<String>()
        val deletions = mutableListOf<String>()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.changes.collect { changes += it } }
        val gone = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.deletions.collect { deletions += it } }
        store.delete(id)
        kotlinx.coroutines.yield()
        job.cancel()
        gone.cancel()
        assertEquals(listOf(id), changes)
        assertEquals(listOf(id), deletions)
        assertEquals(listOf("$other.json"), dir.list()!!.sorted())
        assertEquals(listOf(other), store.list().map { it.id })
        assertEquals(emptyList<Turn>(), store.turns(id))
        assertEquals(null, store.title(id))
        assertEquals(null, store.pinnedAt(id))
    }

    /**It is up to the user to change the name:Even if the title comes late in the background, it will not be covered.;Once the title has been created, the user can still change it.;Leave empty unchanged. */
    @Test fun renameByUserWinsOverAutoTitle() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        store.append(id, Turn("Check the weather in Shanghai tomorrow", "", "succeeded"))
        assertTrue(store.rename(id, "weather  "))
        assertFalse(store.setTitle(id, "Shanghai weather"))
        assertEquals("weather", ConversationStore(dir).title(id))

        val other = store.newConversation()
        store.append(other, Turn("Book tickets", "alright", "succeeded"))
        assertTrue(store.setTitle(other, "Book high-speed rail tickets"))
        assertTrue(store.rename(other, "ticket home"))
        assertFalse(store.rename(other, "   "))
        assertEquals("ticket home", store.title(other))
        assertEquals(setOf("weather", "ticket home"), store.list().map { it.title }.toSet())

        store.delete(other)
        assertFalse(store.rename(other, "Change again"))
    }

    /**Pin it to the top `.pin`inside(Pin time),Reopening is still happening;List with pin time,Do not change the order of the conversation;Unpin it and it will disappear. */
    @Test fun pinPersistsAndShowsInTheList() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("one", "good", "succeeded"))
        store.append("b", Turn("two", "good", "succeeded"))
        assertTrue(store.setPinned("a", true, at = 123L))
        val again = ConversationStore(dir)
        assertEquals(123L, again.pinnedAt("a"))
        assertEquals(null, again.pinnedAt("b"))
        assertEquals(mapOf("a" to 123L, "b" to null), again.list().associate { it.id to it.pinnedAt })
        assertTrue(again.setPinned("a", false))
        assertEquals(null, again.pinnedAt("a"))
        assertFalse(java.io.File(dir, "a.pin").exists())
        //Deleted conversations will no longer be pinned back to the top
        again.delete("b")
        assertFalse(again.setPinned("b", true))
    }

    /**A supplement that arrived after deletion(A good title from the background and an ending that is added after the ending.)Don't write back the dialogue;Depositing for another round means using this number again. */
    @Test fun lateWritesDoNotReviveADeletedConversation() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        store.append(id, Turn("Check the weather", "", "succeeded", runId = "r1"))
        store.delete(id)
        assertFalse(store.setTitle(id, ""))
        store.updateTurn(id, "r1") { it.copy(outcome = "made up for") }
        assertTrue(dir.list()!!.isEmpty())

        store.append(id, Turn("Come again", "good", "succeeded"))
        assertTrue(store.setTitle(id, "Come again"))
        assertEquals(listOf("Come again"), store.list().map { it.title })
    }

    @Test fun corruptFileReadsAsEmpty() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        java.io.File(dir, "x.json").writeText("{not json")
        assertEquals(emptyList<Turn>(), s.turns("x"))
        s.append("x", Turn("a", "b", "cancelled"))
        assertEquals(1, s.turns("x").size)
    }

    @Test fun corruptFileIsMovedAsideNotOverwritten() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        java.io.File(dir, "x.json").writeText("{not json")
        s.append("x", Turn("a", "b", "cancelled"))
        val aside = dir.listFiles().orEmpty().filter { it.name.startsWith("x.json.corrupt-") }
        assertEquals(1, aside.size)
        assertEquals("{not json", aside.single().readText())
        assertEquals(listOf(Turn("a", "b", "cancelled")), s.turns("x"))
        assertEquals(listOf("x"), s.list().map { it.id })
    }

    @Test fun listsConversationsNewestFirst() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        val a = s.newConversation()
        val b = s.newConversation()
        s.append(a, Turn("Check the weather", "", "succeeded"))
        s.append(b, Turn("Book tickets", "alright", "succeeded"))
        s.append(b, Turn("Change ticket", "alright", "succeeded"))
        java.io.File(dir, "$a.json").setLastModified(1_000)
        java.io.File(dir, "$b.json").setLastModified(2_000)
        java.io.File(dir, "broken.json").writeText("{")
        assertEquals(listOf(b to "Book tickets", a to "Check the weather"), s.list().map { it.id to it.title })
    }

    @Test fun unverifiedTurnKeepsItsNote() {
        val dir = tmp.newFolder()
        val id = ConversationStore(dir).let { s -> s.newConversation().also { s.append(it, Turn("Check balance", "Balance 10 yuan", "unverified", note = "Anti-screenshot")) } }
        assertEquals(listOf(Turn("Check balance", "Balance 10 yuan", "unverified", note = "Anti-screenshot")), ConversationStore(dir).turns(id))
    }

    @Test fun turnKeepsItsRunId() {
        val dir = tmp.newFolder()
        val s = ConversationStore(dir)
        val id = s.newConversation()
        s.append(id, Turn("Check the weather", "", "succeeded", runId = "20261023-120000-abc123"))
        s.append(id, Turn("old", "good", "succeeded"))
        assertEquals(listOf("20261023-120000-abc123", null), ConversationStore(dir).turns(id).map { it.runId })
    }

    @Test fun memoryNotesStoredWithoutContentAndStripped() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val note = com.meenai.mina.core.memory.MemoryNote(com.meenai.mina.core.memory.NoteKind.added, "m1", "t1", null)
        val other = com.meenai.mina.core.memory.MemoryNote(com.meenai.mina.core.memory.NoteKind.pending, "m2", "t2", "m2")
        store.append("c1", Turn("Order takeout", "ok", "succeeded", runId = "r1"))
        store.updateTurn("c1", "r1") { it.copy(memoryNotes = listOf(note, other), usedMemory = listOf("m1", "m3")) }
        val raw = java.io.File(dir, "c1.json").readText()
        assertTrue(raw.contains("\"memoryId\":\"m1\""))
        assertFalse(raw.contains("text"))
        assertEquals(listOf(note, other), ConversationStore(dir).turns("c1").single().memoryNotes)
        store.stripMemory(setOf("m1"))
        val t = ConversationStore(dir).turns("c1").single()
        assertEquals(listOf(other), t.memoryNotes)
        assertEquals(listOf("m3"), t.usedMemory)
        store.stripMemory(null)
        assertTrue(ConversationStore(dir).turns("c1").single().memoryNotes.isEmpty())
    }

    @Test fun filesKeptPerTurn() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        val f = SharedFile("picture.png", 1234, "Download/Mina/picture.png", "content://media/external/downloads/7")
        a.append(id, Turn("Draw a picture", "alright", "succeeded"), listOf(f))
        a.append(id, Turn("Come again", "good", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals(listOf(listOf(f), emptyList()), b.records(id).map { it.second })
        assertEquals(2, b.turns(id).size)
    }

    //When adding memory reminders or deleting memories after the task is over,This round of file cards cannot be lost.
    @Test fun filesSurviveNoteUpdatesAndStrip() {
        val dir = tmp.newFolder("d")
        val store = ConversationStore(dir)
        val f = SharedFile("surface.csv", 10, "Download/Mina/surface.csv", "content://media/external/downloads/8")
        store.append("c1", Turn("Export", "alright", "succeeded", runId = "r1"), listOf(f))
        val note = com.meenai.mina.core.memory.MemoryNote(com.meenai.mina.core.memory.NoteKind.added, "m1", "t1", null)
        store.updateTurn("c1", "r1") { it.copy(memoryNotes = listOf(note)) }
        store.stripMemory(null)
        val r = ConversationStore(dir).records("c1").single()
        assertEquals(listOf(f), r.second)
        assertTrue(r.first.memoryNotes.isEmpty())
    }

    //Each dialogue written is assigned its number.:Save a round, change a round, remove memory prompts
    @Test fun announcesChangedConversations() = kotlinx.coroutines.test.runTest {
        val s = ConversationStore(tmp.newFolder("c"))
        val seen = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.changes.collect { seen += it } }
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.deletions.collect { deleted += it } }
        s.append("a", Turn("Check the weather", "", "succeeded", runId = "r1", usedMemory = listOf("m1")))
        s.append("b", Turn("Book tickets", "alright", "succeeded"))
        s.updateTurn("a", "r1") { it.copy(outcome = "Sunny to cloudy") }
        s.updateTurn("a", "missing") { it }
        s.stripMemory(setOf("m1"))
        assertEquals(listOf("a", "b", "a", "a"), seen)
        //Only post after deleting the conversation deletions
        assertEquals(emptyList<String>(), deleted)
    }

    /**From which page does the conversation begin?:Note it next to .page file,Bring in the list;Old conversations read as usual without it;Delete the conversation together. */
    @Test fun pageOriginPersistsAndIsOptional() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val plain = a.newConversation()
        a.append(plain, Turn("Check the weather", "", "succeeded"))
        val paged = a.newConversation()
        assertTrue(a.setPageOrigin(paged, PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "search")))
        a.append(paged, Turn("pick one", "good", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals(PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "search"), b.pageOrigin(paged))
        assertEquals(null, b.pageOrigin(plain))
        val listed = b.list().associateBy { it.id }
        assertEquals("shop.example.com", listed.getValue(paged).page?.host)
        assertEquals(null, listed.getValue(plain).page)
        //The format of the conversation file remains unchanged:no extra fields
        assertFalse(java.io.File(dir, "$paged.json").readText().contains("shop.example.com"))
        //broken .page as if there is no
        java.io.File(dir, "$plain.page").writeText("{not json")
        assertEquals(null, b.pageOrigin(plain))
        b.delete(paged)
        assertFalse(java.io.File(dir, "$paged.page").exists())
        assertFalse(b.setPageOrigin(paged, PageOrigin("https://a.example/", "a.example")))
    }

    /**
     *Also write down "Which conversation the user is in at the moment":After the process is restarted, the capsule will continue the conversation accordingly.,Instead of guessing the latest one based on the file time;
     *Newly opened conversations that haven’t been spoken yet will be recorded as [CurrentChat.Fresh];Clear it when you delete it;This file is not included in the conversation list.
     */
    @Test fun remembersTheConversationOnScreen() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("Check the weather", "", "succeeded"))
        assertEquals(null, a.currentChat())

        a.setCurrent(CurrentChat.Fresh)
        assertEquals(CurrentChat.Fresh, a.currentChat())
        //The newly opened conversation does not have a conversation file yet,I can still read it after restarting.
        assertEquals(CurrentChat.Fresh, ConversationStore(dir).currentChat())
        assertEquals(listOf(id), ConversationStore(dir).list().map { it.id })

        a.setCurrent(CurrentChat.Open(id))
        assertEquals(CurrentChat.Open(id), ConversationStore(dir).currentChat())
        assertEquals(CurrentChat.Open(id), ConversationStore(dir).current.value)
        //Record it in the current file next to it,Don't move the conversation file, don't enter the list
        assertEquals(setOf("$id.json", "current"), dir.list()!!.toSet())
        assertEquals(listOf(id), a.list().map { it.id })

        //delete this conversation:Clear the records pointing to it together,
        a.delete(id)
        assertEquals(null, ConversationStore(dir).currentChat())
    }
}
