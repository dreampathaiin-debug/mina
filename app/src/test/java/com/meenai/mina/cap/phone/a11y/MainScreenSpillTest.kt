package com.meenai.mina.cap.phone.a11y

import com.meenai.mina.cap.phone.a11y.MainScreenSpill.InUse
import com.meenai.mina.cap.phone.a11y.MainScreenSpill.PullBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenSpillTest {
    private val since = 10_000L
    private val window = 6_000L
    private val home = setOf("com.home", "com.meenai.mina")

    private fun arrival(
        task: Int = 7, pkg: String? = "com.chat", at: Long = since + 500, from: Int = MainScreenSpill.NEW,
        sameTask: Boolean = false, standard: Boolean = true, now: Int = MainScreenSpill.MAIN, user: Int = 0,
    ) = MainScreenSpill.Arrival(task, pkg, user, at, from, sameTask, standard, now)

    private fun decide(vararg a: MainScreenSpill.Arrival, topBefore: Int = 3, taps: List<MainScreenSpill.Tap> = emptyList()) =
        MainScreenSpill.decide(a.toList(), since, window, topBefore, home, taps)

    @Test fun newTaskOnTheMainScreenRightAfterTheActionIsPulledBack() {
        assertEquals(listOf(PullBack(arrival())), decide(arrival()))
    }

    @Test fun backgroundTaskOnTheMainScreenBroughtToFrontIsPulledBackToo() {
        //The system brings the existing background tasks of that app to the foreground.,No new task
        val a = arrival(from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(a)), decide(a))
    }

    @Test fun outsideTheWindowIsNotThisAction() {
        assertTrue(decide(arrival(at = since - 1)).isEmpty())
        assertTrue(decide(arrival(at = since + window + 1)).isEmpty())
    }

    @Test fun launcherMinaAndNonStandardPagesAreNotSpills() {
        assertTrue(decide(arrival(pkg = "com.home")).isEmpty())
        assertTrue(decide(arrival(pkg = "com.meenai.mina")).isEmpty())
        assertTrue(decide(arrival(standard = false)).isEmpty())
        assertTrue(decide(arrival(pkg = null)).isEmpty())
    }

    @Test fun taskMovedFromAnotherDisplayIsATakeoverNotASpill() {
        //take over(This task or other tasks)It is to move the task from Mina's screen to the home screen.
        assertTrue(decide(arrival(from = 5)).isEmpty())
    }

    @Test fun taskNoLongerOnTheMainScreenIsLeftAlone() {
        assertTrue(decide(arrival(now = 5)).isEmpty())
        assertTrue(decide(arrival(now = -1)).isEmpty())
    }

    @Test fun userTappingTheMainScreenMeansTheUserOpenedIt() {
        val other = MainScreenSpill.Tap(since + 400, "com.other")
        assertTrue(decide(arrival(at = since + 900), taps = listOf(other)).isEmpty())
        //Clicks before the action do not count
        assertEquals(1, decide(arrival(at = since + 900), taps = listOf(MainScreenSpill.Tap(since - 100, "com.other"))).size)
    }

    @Test fun userTappingThePopupItselfLeavesItThere() {
        //After the pop-up window appears, the user clicks on it(Click OK on the screen):users are using it,Don't move
        val a = arrival(at = since + 900)
        assertEquals(listOf(InUse(a)), decide(a, taps = listOf(MainScreenSpill.Tap(since + 1_500, "com.chat"))))
        //Clicked on the window of another app:Can't tell,Move back as usual
        assertEquals(1, decide(a, taps = listOf(MainScreenSpill.Tap(since + 1_500, "com.other"))).size)
    }

    @Test fun previousTopComingBackIsNotASpill() {
        //After removing the one that came out,It turns out that the top one is exposed again
        assertTrue(decide(arrival(task = 3, from = MainScreenSpill.MAIN), topBefore = 3).isEmpty())
    }

    @Test fun newPageInTheAppTheUserIsUsingIsReportedNotMoved() {
        val a = arrival(task = 3, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(InUse(a)), decide(a, topBefore = 3))
    }

    @Test fun systemQuestionIsLeftForTheUserAndNothingElseIs() {
        val leave = MainScreenSpill.Handling.LeaveForUser
        val move = MainScreenSpill.Handling.Move
        //system selector,Or the options in the system package window are correct and two copies are installed. App:Leave it to the user to choose,Never move, never reopen
        assertEquals(leave, MainScreenSpill.handling(systemPackage = true, systemPicker = true, optionsMatch = false))
        assertEquals(leave, MainScreenSpill.handling(systemPackage = true, systemPicker = false, optionsMatch = true))
        //Common system pages such as system settings, common App:move back
        assertEquals(move, MainScreenSpill.handling(systemPackage = true, systemPicker = false, optionsMatch = false))
        assertEquals(move, MainScreenSpill.handling(systemPackage = false, systemPicker = false, optionsMatch = true))
    }

    @Test fun appPickedByTheUserIsWhatCameUpAfterTheQuestionClosed() {
        val gone = since + 20_000
        val chooser = arrival(task = 8, pkg = "android", at = since + 300)
        val picked = arrival(task = 9, at = gone - 200)
        val pick = { list: List<MainScreenSpill.Arrival> -> MainScreenSpill.picked(list, 8, gone, 1_000, 3_000, 3, home) }
        assertEquals(picked, pick(listOf(chooser, picked)))
        //It also counts if the user has just clicked on the option.(Then it’s a choice)
        assertEquals(picked, MainScreenSpill.picked(listOf(picked), 8, gone, 1_000, 3_000, 3, home))
        //The original one exposed after closing the selection box does not count.:The user closed the question
        assertEquals(null, pick(listOf(chooser, arrival(task = 3, at = gone, from = MainScreenSpill.MAIN))))
        //The selection box that popped up a long time ago was not selected.
        assertEquals(null, pick(listOf(arrival(task = 9, at = gone - 5_000))))
        //The app that was originally in the background of the home screen was brought to the foreground.:That's the one chosen
        val existing = arrival(task = 12, at = gone + 400, from = MainScreenSpill.MAIN, user = 128)
        assertEquals(existing, pick(listOf(chooser, existing)))
    }

    @Test fun pickedAppNeedsTheUserForAPasswordOrASecureUnreadablePage() {
        assertTrue(MainScreenSpill.needsUser(passwordField = true, readable = true, secureWindow = false))
        assertTrue(MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = true))
        //Apps that cannot read anything are not considered to require user processing,Move back as usual
        assertEquals(false, MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = false))
        assertEquals(false, MainScreenSpill.needsUser(passwordField = false, readable = true, secureWindow = false))
    }

    @Test fun accountServiceWithoutLauncherIsAUserStep() {
        val auth = setOf("com.example.accounts", "com.example.chat")
        //System account login service:The authenticator is registered and there is no desktop icon.,The account adding and verification code pages that pop up on the home screen fill the whole screen, and there is no password box left to the user.
        assertTrue(MainScreenSpill.authenticatorUi("com.example.accounts", auth, hasLauncher = false))
    }

    @Test fun authenticatorAppWithLauncherIsMovedBackAsBefore() {
        val auth = setOf("com.example.accounts", "com.example.chat")
        //The chat app has registered an authenticator for synchronized contacts,but it has desktop icon:Conversation and sharing pages pop up on the home screen and move back as usual,Not left because of the authenticator
        assertFalse(MainScreenSpill.authenticatorUi("com.example.chat", auth, hasLauncher = true))
        //Not an authenticator package,No desktop icons
        assertFalse(MainScreenSpill.authenticatorUi("com.example.helper", auth, hasLauncher = false))
    }

    @Test fun oneVerdictPerTaskInOrder() {
        val chooser = arrival(task = 8, pkg = "android", at = since + 300)
        val first = arrival(task = 7, at = since + 200)
        val again = arrival(task = 7, at = since + 600, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(chooser), PullBack(again)), decide(again, first, chooser))
    }
}
