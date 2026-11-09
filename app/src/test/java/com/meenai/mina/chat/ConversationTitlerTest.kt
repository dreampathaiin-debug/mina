package com.meenai.mina.chat

import com.meenai.mina.core.agent.Turn
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationTitlerTest {
    @get:Rule val tmp = TemporaryFolder()

    /**Model for replying to fixed text;[fail]Wrong throw,[hang]Never looked back. */
    private class Titler(private val reply: String = "", private val fail: Boolean = false, private val hang: Boolean = false) : ChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 8_000, reasoning = false)
        val requests = mutableListOf<List<Message>>()
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            requests += messages
            if (fail) error("offline")
            if (hang) delay(60_000)
            return ModelReply(listOf(Part.Text(reply)), StopReason.EndTurn, null)
        }
    }

    private fun store() = ConversationStore(tmp.newFolder("c"))
    private fun text(m: Titler) = m.requests.single().flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString("") { it.text }

    @Test fun titlesTheFirstMessageOnce() = runBlocking {
        val s = store()
        val id = s.newConversation()
        val m = Titler("Sichuan food nearby")
        ConversationTitler.ensure(s, id, "Show me the highest-rated Sichuan cuisine near you") { listOf(m) }
        assertEquals("Sichuan food nearby", s.title(id))
        //do it again(The model changed its expression)No more changes or requests
        val again = Titler("another name")
        ConversationTitler.ensure(s, id, "second sentence") { listOf(again) }
        assertEquals("Sichuan food nearby", s.title(id))
        assertTrue(again.requests.isEmpty())
    }

    @Test fun promptCarriesOnlyTheFirstMessage() = runBlocking {
        val m = Titler("Book high-speed rail tickets")
        ConversationTitler.generate(listOf(m), "Book your high-speed rail tickets for tomorrow")
        assertEquals("<message>\nbook high-speed rail tickets for tomorrow\n</message>", text(m))
    }

    /**The first sentence asked on the browser page:The page description is not handed over to the model that created the title.,Just give the user what to say. */
    @Test fun pageContextIsNotPartOfTheTitleInput() = runBlocking {
        val m = Titler("Pick headphones")
        val first = com.meenai.mina.core.cap.web.AttachedPage.compose(
            com.meenai.mina.core.cap.web.AttachedPage.Page("https://shop.example.com/s", "Search results"), "Pick the one with the best reviews")
        assertEquals("Pick headphones", ConversationTitler.generate(listOf(m), first))
        assertTrue(text(m).contains("Pick the one with the best reviews"))
        assertFalse(text(m).contains("shop.example.com"))
    }

    @Test fun notRegeneratedOnceTheConversationHasTurns() = runBlocking {
        val s = store()
        val id = s.newConversation()
        s.append(id, Turn("Check the weather", "", "succeeded"))
        val m = Titler("")
        ConversationTitler.ensure(s, id, "Check the weather") { listOf(m) }
        assertNull(s.title(id))
        assertTrue(m.requests.isEmpty())
        //Old dialogue has no title,Still the first sentence in the list
        assertEquals("Check the weather", s.list().single().title)
    }

    @Test fun failureLeavesNoTitle() = runBlocking {
        val s = store()
        val id = s.newConversation()
        ConversationTitler.ensure(s, id, "Check the weather") { listOf(Titler(fail = true)) }
        assertNull(s.title(id))
        s.append(id, Turn("Check the weather", "", "succeeded"))
        assertEquals("Check the weather", s.list().single().title)
    }

    @Test fun fallsBackToTheNextModelAndTimesOut() = runBlocking {
        assertEquals("", ConversationTitler.generate(listOf(Titler(fail = true), Titler("")), "Check the weather"))
        assertNull(ConversationTitler.generate(listOf(Titler(hang = true)), "Check the weather", timeoutMs = 50))
        assertNull(ConversationTitler.generate(emptyList(), "Check the weather"))
    }

    @Test fun screenOnlyMessageIsNotTitled() = runBlocking {
        val m = Titler("Screen")
        val line = com.meenai.mina.core.userscreen.UserScreenMessage.line("abc")
        assertNull(ConversationTitler.generate(listOf(m), line))
        assertTrue(m.requests.isEmpty())
        //When adding a postscript, only give the postscript to the model.
        ConversationTitler.generate(listOf(m), line + "\nwhat is this")
        assertEquals("<message>\nwhat is this\n</message>", text(m))
    }

    @Test fun cleansTheReply() {
        assertEquals("Sichuan food nearby", ConversationTitler.clean(""Nearby Sichuan food""))
        assertEquals("Sichuan food nearby", ConversationTitler.clean("Title: Sichuan Cuisine Nearby."))
        assertEquals("Book train", ConversationTitler.clean("\"Book train\"\n redundant explanation"))
        assertEquals(16, ConversationTitler.clean("one".repeat(40))!!.length)
        assertNull(ConversationTitler.clean("  \n "))
        assertNull(ConversationTitler.clean("「」"))
    }

    @Test fun titleRoundTripsAndShowsInTheList() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        assertFalse(a.setTitle(id, "  "))
        assertTrue(a.setTitle(id, "Sichuan food nearby"))
        assertFalse(a.setTitle(id, "Change it"))
        a.append(id, Turn("Show me the highest-rated Sichuan cuisine near you", "good", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals("Sichuan food nearby", b.title(id))
        assertEquals("Sichuan food nearby", b.list().single().title)
        //Title does not enter conversation file,You can still read the turns as usual
        assertEquals(listOf("Show me the highest-rated Sichuan cuisine near you"), b.turns(id).map { it.userText })
        assertNull(b.title("does not exist"))
    }
}
