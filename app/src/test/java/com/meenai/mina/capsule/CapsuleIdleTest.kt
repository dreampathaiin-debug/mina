package com.meenai.mina.capsule

import com.meenai.mina.chat.CurrentChat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 *Which conversation will follow the card when you click on the idle icon?:The one where the user is in the app([CurrentChat]),If there is no record, the most recent one will be used.(The bottom line read by the caller),
 *A new dialogue that has not yet been spoken is a new dialogue card. pure logic,Does not read disk.
 */
class CapsuleIdleTest {
    private val recent = CardView.Chat("c-old", title = "Book tickets")

    @Test fun noRecordFallsBackToTheMostRecentConversation() {
        assertEquals(recent, CapsuleIdle.chat(null, recent))
        //not a single dialogue:New conversation card
        assertEquals(CardView.Chat(null), CapsuleIdle.chat(null, null))
    }

    @Test fun aBrandNewConversationIsAFreshCard() {
        val card = CapsuleIdle.chat(CurrentChat.Fresh, recent)
        assertEquals(CardView.Chat(null, fresh = true), card)
        //no number:A new one is opened by the caller when speaking in a card
        assertEquals(null, card.target)
    }

    @Test fun theConversationOnScreenWins() {
        val asked = mutableListOf<String>()
        val card = CapsuleIdle.chat(CurrentChat.Open("c-new"), recent) { id -> asked += id; "Check express delivery" }
        assertEquals(CardView.Chat("c-new", title = "Check express delivery", current = true), card)
        //Just check its title:The title of the one at the bottom of the pocket is not useful.
        assertEquals(listOf("c-new"), asked)
        //Remember it is "the one the user is in right now",Accordingly, the card side will not let old tasks come over.
        assertEquals(true, card.current)
    }

    /**Conversation without title(Already have a number, but haven’t decided on a name yet)continue it as usual,Just don’t write a title on the top of the card. */
    @Test fun aConversationWithoutATitleIsStillContinued() {
        val card = CapsuleIdle.chat(CurrentChat.Open("c-new"), recent) { null }
        assertEquals(CardView.Chat("c-new", title = null, current = true), card)
        assertEquals("c-new", card.target)
    }
}
