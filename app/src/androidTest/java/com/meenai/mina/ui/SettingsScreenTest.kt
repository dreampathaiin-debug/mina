package com.meenai.mina.ui

import com.meenai.mina.core.shell.HelperRestart
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.capsule.CapsuleMode
import com.meenai.mina.core.agent.SpeedFlags
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.settings.AccessibilityState
import com.meenai.mina.settings.Problem
import com.meenai.mina.settings.SystemStatus
import com.meenai.mina.task.ParallelCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import org.junit.runner.RunWith
import com.meenai.mina.core.cap.phone.OperatingPace

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)

    private fun home(
        status: SystemStatus = ready,
        mainModel: String? = "DeepSeek",
        onProblem: (Problem) -> Unit = {},
        opened: MutableList<String> = mutableListOf(),
        showDeveloper: Boolean = true,
        usageTokens: Long? = 123_000,
    ) {
        compose.setContent {
            SettingsContent(
                status = status, androidRelease = "14", mainModel = mainModel, onBack = {}, onProblem = onProblem,
                onOpenModels = { opened += "models" }, usageTokens = usageTokens, onOpenUsage = { opened += "usage" }, onOpenPhoneOps = { opened += "phone" }, onOpenDuringWork = { opened += "work" },
                capsuleMode = CapsuleMode.DuringTasks, onOpenCapsule = { opened += "capsule" }, onOpenNotifications = { opened += "notifications" },
                onOpenMemory = { opened += "memory" },
                webSitesCount = 3, onOpenWebSites = { opened += "web" },
                sandbox = SandboxStatus.Ready(network = false), onOpenSandbox = { opened += "sandbox" },
                routes = 8, onOpenRoutes = { opened += "routes" }, onOpenRecovery = { opened += "recovery" },
                showDeveloper = showDeveloper, onOpenDeveloper = { opened += "dev" },
                versionName = "0.1.0", onOpenAbout = { opened += "about" },
            )
        }
    }

    /**The page is an entire column of scrolling content:Scroll this row into the viewport before asserting or clicking. */
    private fun scrollTo(text: String) {
        compose.awaitOne(text).performScrollTo()
        compose.waitForIdle()
    }

    @Test
    fun homeShowsGroupsStatusAndValues() {
        home()
        compose.onNodeWithText("Everything is ready").assertIsDisplayed()
        compose.onNodeWithText("basic").assertIsDisplayed()
        //The homepage does not fit on one screen(It will be longer after adding the "language" line.):Each value is first scrolled into the viewport and then viewed.
        //In order, they are model, mobile phone operation, notification, website login, operation route, computing environment, and about
        for (v in listOf("DeepSeek", "Available", "normal", "3", "8 items", "Ready", "0.1.0")) {
            scrollTo(v)
            compose.onNodeWithText(v).assertIsDisplayed()
        }
    }

    @Test
    fun homeRowsOpenTheirPages() {
        val opened = mutableListOf<String>()
        home(opened = opened)
        for (t in listOf("Model", "Token usage", "Mobile phone operation", "while working", "", "notify", "memory and skills", "Website login", "Documentation and Computing Environment", "Memorized operating routes", "Restoration and Repair", "Developer options", "about Mina")) {
            scrollTo(t)
            compose.onNodeWithText(t).performClick()
        }
        assertEquals(listOf("models", "usage", "phone", "work", "capsule", "notifications", "memory", "web", "sandbox", "routes", "recovery", "dev", "about"), opened)
    }

    /**"Token Usage" writes the total of the past 7 days;When not at all, write "not yet",This line is not displayed when statistics have not yet been calculated. */
    @Test
    fun usageRowShowsWeekTotal() {
        var tokens by mutableStateOf<Long?>(123_000)
        compose.setContent {
            SettingsContent(
                status = ready, androidRelease = "14", mainModel = "DeepSeek", onBack = {}, onProblem = {}, onOpenModels = {},
                usageTokens = tokens,
            )
        }
        compose.onNodeWithText("123,000 in the last 7 days").assertIsDisplayed()
        tokens = 0
        compose.awaitOne("not yet").assertIsDisplayed()
        tokens = null
        compose.waitForIdle()
        compose.onAllNodesWithText("Token usage").assertCountEquals(0)
    }

    /**Release builds don't show developer options. */
    @Test
    fun developerRowHiddenInRelease() {
        home(showDeveloper = false)
        compose.onAllNodesWithText("Developer options").assertCountEquals(0)
    }

    /**The status card turns into a prompt when there is a problem,button to submit the question to onProblem. */
    @Test
    fun statusCardTurnsIntoFixPrompt() {
        val clicked = mutableListOf<Problem>()
        home(
            SystemStatus(34, BuiltinShellStatus.WirelessDebuggingOff, AccessibilityState.On, notificationsAllowed = true),
            onProblem = { clicked += it },
        )
        compose.onNodeWithText("Wireless debugging is off").assertIsDisplayed()
        compose.onAllNodesWithText("Everything is ready").assertCountEquals(0)
        compose.onNodeWithText("Need to be processed").assertIsDisplayed()
        compose.awaitOne("to open").performClick()
        assertEquals(listOf<Problem>(Problem.PhoneHelper(BuiltinShellStatus.WirelessDebuggingOff)), clicked)
    }

    @Test
    fun noModelAndOldSystem() {
        home(SystemStatus(30, BuiltinShellStatus.Running, AccessibilityState.EnabledNotRunning, notificationsAllowed = false), mainModel = null)
        compose.onNodeWithText("Mobile operation is not available").assertIsDisplayed()
        compose.onNodeWithText("not set").assertIsDisplayed()
        compose.onNodeWithText("Not supported").assertIsDisplayed()
        //The operation is not available on mobile phones below Android 12:No recovery or repair
        compose.onAllNodesWithText("Restoration and Repair").assertCountEquals(0)
    }

    @Test
    fun phoneOpsPageShowsPermissionRowsAndPairing() {
        var repaired = 0
        compose.setContent {
            PhoneOpsScreen(
                SystemStatus(34, BuiltinShellStatus.WirelessDebuggingOff, AccessibilityState.On, notificationsAllowed = true),
                "14", onProblem = {}, onOpenNotifications = {}, onRepair = { repaired++ }, onUnpair = {}, onBack = {},
            )
        }
        compose.onNodeWithText("").assertIsDisplayed()
        compose.onNodeWithText("Already turned on").assertIsDisplayed()
        compose.onNodeWithText("Wireless debugging is turned off").assertIsDisplayed()
        compose.onNodeWithText("normal").assertIsDisplayed()
        compose.onNodeWithText("Android 14").assertIsDisplayed()
        compose.onNodeWithText("satisfy").assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.usageNote(HelperRestart.None)).assertExists()
        compose.onNodeWithText(PhoneSetupCopy.REPAIR).performClick()
        assertEquals(1, repaired)
    }

    /**The "Notification" row on the mobile phone operation page displays the overall status,Click it to enter the notification page. */
    @Test
    fun phoneOpsNotificationRowOpensNotifications() {
        var opened = 0
        compose.setContent {
            PhoneOpsScreen(
                ready.copy(notificationsAllowed = false), "14", onProblem = {}, onOpenNotifications = { opened++ },
                onRepair = {}, onUnpair = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Closed").assertIsDisplayed()
        compose.onNodeWithText("notify").performClick()
        assertEquals(1, opened)
    }

    /**notification page:When the master switch is enabled, a channel is turned down, or the progress is turned off,Display status of each row,Click each row to hand it over to onFix. */
    @Test
    fun notificationsPageShowsChannelsAndOpensThem() {
        val fixes = mutableListOf<Fix>()
        compose.setContent {
            NotificationsScreen(
                ready.copy(attentionImportance = 4, resultImportance = 2, progressImportance = 0), onFix = { fixes += it }, onBack = {},
            )
        }
        compose.onNodeWithText("allowed").assertIsDisplayed()
        compose.onNodeWithText("When you need to take over, confirm, and answer").assertIsDisplayed()
        compose.onNodeWithText("will pop up").assertIsDisplayed()
        compose.onNodeWithText("Only enter the notification bar").assertIsDisplayed()
        compose.onNodeWithText("Closed").assertIsDisplayed()
        compose.onNodeWithText(StatusCopy.NOTIF_FOOTER).assertExists()
        compose.onNodeWithText("need you to deal with").performClick()
        compose.onNodeWithText("Task results").performClick()
        compose.onNodeWithText("Task progress").performClick()
        assertEquals(listOf(Fix.OpenAttentionChannel, Fix.OpenResultChannel, Fix.OpenProgressChannel), fixes)
    }

    /**The main switch is off:Give "go open",All three channels appear to be closed. */
    @Test
    fun notificationsPageWhenAppOff() {
        val fixes = mutableListOf<Fix>()
        compose.setContent { NotificationsScreen(ready.copy(notificationsAllowed = false), onFix = { fixes += it }, onBack = {}) }
        compose.onAllNodesWithText("Closed").assertCountEquals(3)
        compose.onNodeWithText("to open").performClick()
        assertEquals(listOf(Fix.AllowNotifications), fixes)
    }

    @Test
    fun unpairAsksFirst() {
        var unpaired = 0
        compose.setContent { PhoneOpsScreen(ready, "14", onProblem = {}, onOpenNotifications = {}, onRepair = {}, onUnpair = { unpaired++ }, onBack = {}) }
        compose.onNodeWithText(PhoneSetupCopy.UNPAIR).performClick()
        assertEquals(0, unpaired)
        compose.clickInDialog(PhoneSetupCopy.UNPAIR_CONFIRM)
        assertEquals(1, unpaired)
    }

    /**When the parallel switch is turned on but the device does not support it:This line is grayed out,State the reason,No addition or subtraction. */
    @Test
    fun parallelDisabledShowsReason() {
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = {}, muteTarget = true, onMuteTarget = {},
                parallel = ParallelSetting(2, ParallelCapability.detect(33, lowRam = false, userMax = 2, secondDisplayFailed = false)),
                onParallelMax = {}, onBack = {},
            )
        }
        compose.onNodeWithText("simultaneous tasks").assertIsDisplayed()
        compose.onNodeWithText("Android 14 or above supports multiple tasks at the same time").assertIsDisplayed()
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("+").assertDoesNotExist()
    }

    @Test
    fun parallelMaxAdjustable() {
        val picked = mutableListOf<Int>()
        var max by mutableStateOf(2)
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = {}, muteTarget = true, onMuteTarget = {},
                parallel = ParallelSetting(max, ParallelCapability.detect(34, lowRam = false, userMax = max, secondDisplayFailed = false)),
                onParallelMax = { max = it; picked += it }, onBack = {},
            )
        }
        compose.onNodeWithText("The screen stays on").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("+").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("−").performClick()
        assertEquals(listOf(3, 2), picked)
    }

    @Test
    fun duringWorkWithoutParallelHasNoParallelRow() {
        compose.setContent {
            DuringWorkScreen(true, {}, true, {}, parallel = null, onParallelMax = {}, onBack = {})
        }
        compose.onNodeWithText("The operated app is muted").assertIsDisplayed()
        compose.onAllNodesWithText("simultaneous tasks").assertCountEquals(0)
    }

    /**"While working" two items are turned on by default,Users can turn off:When the switch is clicked, the new value is called back. */
    @Test
    fun duringWorkTogglesCanBeTurnedOff() {
        val changes = mutableListOf<String>()
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = { changes += "screen:$it" },
                muteTarget = true, onMuteTarget = { changes += "mute:$it" },
                parallel = null, onParallelMax = {}, onBack = {},
            )
        }
        compose.onNodeWithTag("work-keep-screen-on").performClick()
        compose.onNodeWithTag("work-mute-target").performClick()
        assertEquals(listOf("screen:false", "mute:false"), changes)
    }

    /**while working ›operating rhythm:fast, standard(default), choose one of three like people,Click callback new value. */
    @Test
    fun operatingPaceIsOneChoiceOfThree() {
        val picked = mutableListOf<OperatingPace>()
        compose.setContent {
            DuringWorkScreen(true, {}, true, {}, parallel = null, onParallelMax = {}, onBack = {}, onOperatingPace = { picked += it })
        }
        compose.onNodeWithText("operating rhythm").assertIsDisplayed()
        compose.onNodeWithText("quick").assertIsDisplayed()
        compose.onNodeWithText("Standard (default)").assertIsDisplayed()
        compose.onNodeWithText("like people").performClick()
        compose.onNodeWithText("quick").performClick()
        assertEquals(listOf(OperatingPace.HUMAN, OperatingPace.FAST), picked)
    }

    /**The current selection is written on the "Suspended Capsule" line on the home page. */
    @Test
    fun capsuleRowShowsCurrentChoice() {
        compose.setContent {
            SettingsContent(status = ready, androidRelease = "14", mainModel = "m", onBack = {}, onProblem = {}, onOpenModels = {}, capsuleMode = CapsuleMode.Always)
        }
        compose.awaitOne("always shown").assertIsDisplayed()
    }

    /**Suspended capsule sub-page choose one from three,Selected callback new value;The footer description uses accessibility services. */
    @Test
    fun capsulePageOffersThreeChoices() {
        var mode by mutableStateOf(CapsuleMode.DuringTasks)
        val picked = mutableListOf<CapsuleMode>()
        compose.setContent { CapsuleScreen(mode, onMode = { picked += it; mode = it }, onBack = {}) }
        compose.awaitOne("Displayed when there is a task").assertIsDisplayed()
        compose.onNodeWithText("closure").performScrollTo().assertIsDisplayed()
        //Footnotes below the three options,If the screen is short or the font size is large, you have to scroll to see it.
        compose.onNodeWithText("", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("always shown").performScrollTo().performClick()
        compose.onNodeWithText("closure").performScrollTo().performClick()
        assertEquals(listOf(CapsuleMode.Always, CapsuleMode.Off), picked)
    }

    /**"Clear all" first question;Clear after confirmation,The number of bars becomes 0 and the button disappears.;If cancelled, it will not move. */
    @Test
    fun clearRoutesAsksThenEmpties() {
        var routes by mutableStateOf(3)
        var cleared = 0
        compose.setContent { RoutesScreen(routes, onClear = { cleared++; routes = 0 }, onBack = {}) }
        compose.onNodeWithText("Memorized route").assertIsDisplayed()
        compose.onNodeWithText("3 items", substring = true).assertIsDisplayed()
        compose.onNodeWithText("clear all").performClick()
        compose.clickInDialog("Cancel")
        assertEquals(0, cleared)
        compose.onNodeWithText("clear all").performClick()
        compose.awaitOne("Clear all memorized operation routes?", inDialog = true).assertIsDisplayed()
        compose.clickInDialog("")
        assertEquals(1, cleared)
        compose.onNodeWithText("0 items", substring = true).assertIsDisplayed()
        compose.onNodeWithText("clear all").assertDoesNotExist()
    }

    /**Developer options:The eight switches are divided into two groups: speed and experiment.,Click to open the corresponding item. */
    @Test
    fun developerOptionsHasTwoGroupsAndToggles() {
        val changed = mutableListOf<SpeedFlags>()
        var flags by mutableStateOf(SpeedFlags.OFF)
        compose.setContent { DeveloperOptionsScreen(flags, onSpeed = { flags = it; changed += it }, onBack = {}) }
        compose.onNodeWithText("speed").assertIsDisplayed()
        for (t in listOf("Read the elements first, then look at the image if necessary", "Bring back screen changes after operation", "When you are confident, do a few steps in a row", "Directly open the page you want to go to", "Simple steps to lightweight models")) {
            scrollTo(t)
            compose.onNodeWithText(t).assertIsDisplayed()
        }
        scrollTo("experiment")
        compose.onNodeWithText("experiment").assertIsDisplayed()
        scrollTo("Do as you did last time")
        compose.onNodeWithText("Do as you did last time").performClick()
        assertEquals(listOf(SpeedFlags(replay = true)), changed)
        scrollTo("Different conversations taking place simultaneously")
        scrollTo("Write summaries of long tasks in separate sections")
        compose.onNodeWithText("Write summaries of long tasks in separate sections").assertIsDisplayed()
    }

    /**"Autonomous Mode" is at the top of the developer options,Default off;Click the switch or toggle the entire row. */
    @Test
    fun autonomousSwitchComesFirstAndToggles() {
        val toggled = mutableListOf<Boolean>()
        var on by mutableStateOf(false)
        compose.setContent { DeveloperOptionsScreen(SpeedFlags.OFF, onSpeed = {}, onBack = {}, autonomous = on, onAutonomous = { toggled += it; on = it }) }
        compose.onNodeWithText(AutonomyCopy.GROUP).assertIsDisplayed()
        compose.onNodeWithText(AutonomyCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithTag("dev-autonomous").assertIsOff().performClick()
        compose.onNodeWithTag("dev-autonomous").assertIsOn()
        compose.onNodeWithText(AutonomyCopy.TITLE).performClick()
        compose.onNodeWithTag("dev-autonomous").assertIsOff()
        assertEquals(listOf(true, false), toggled)
    }

    /**"View image positioning has been verified" moved to developer options:Appears only after looking at the picture model,Clicked switch. */
    @Test
    fun groundingSwitchShowsOnlyWithVisionModel() {
        val toggled = mutableListOf<Boolean>()
        var grounding by mutableStateOf<GroundingSetting?>(null)
        compose.setContent {
            DeveloperOptionsScreen(SpeedFlags.OFF, onSpeed = {}, onBack = {}, grounding = grounding, onGrounding = { toggled += it })
        }
        compose.onNodeWithText(GroundingCopy.TITLE).assertDoesNotExist()
        grounding = GroundingSetting("qwen-vl-max", verified = false)
        scrollTo(GroundingCopy.TITLE)
        compose.onNodeWithText(GroundingCopy.TITLE).performClick()
        assertEquals(listOf(true), toggled)
    }

    private class Calls { var reset = 0; val network = mutableListOf<Boolean>() }

    private fun advanced(status: SandboxStatus, network: Boolean = false, isolationIssue: Boolean = false): Calls {
        val c = Calls()
        compose.setContent {
            Column {
                AdvancedSection(
                    status = status, networkAllowed = network, isolationIssue = isolationIssue,
                    onNetworkChange = { c.network += it }, onReset = { c.reset++ },
                )
            }
        }
        return c
    }

    @Test
    fun unsupportedHidesAdvanced() {
        advanced(SandboxStatus.Unsupported("This device does not support(need arm64)"))
        compose.onAllNodesWithText(SandboxCopy.NETWORK_TITLE).assertCountEquals(0)
        compose.onAllNodesWithText("Reset the operating environment").assertCountEquals(0)
    }

    @Test
    fun advancedHasNoInstallOrSandboxWording() {
        advanced(SandboxStatus.NotInstalled)
        compose.onNodeWithText(SandboxCopy.NETWORK_TITLE).assertIsDisplayed()
        compose.onNodeWithText("Reset the operating environment").assertIsDisplayed()
        for (t in listOf("sandbox", "Install sandbox", "Software source", "Clear workspace")) compose.onAllNodesWithText(t, substring = true).assertCountEquals(0)
    }

    @Test
    fun resetAsksFirst() {
        val c = advanced(SandboxStatus.Ready(network = false))
        compose.onNodeWithText("Reset the operating environment").performClick()
        assertEquals(0, c.reset)
        compose.onNodeWithText(SandboxCopy.RESET_BODY).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, c.reset)
        compose.onNodeWithText("Reset the operating environment").performClick()
        compose.onNodeWithText("reset").performClick()
        assertEquals(1, c.reset)
    }

    @Test
    fun isolationIssueShown() {
        advanced(SandboxStatus.Ready(network = true), network = true, isolationIssue = true)
        compose.onNodeWithText(SandboxCopy.NETWORK_EXPLAIN).assertIsDisplayed()
        compose.onNodeWithText(SandboxCopy.ISOLATION_UNAVAILABLE).assertIsDisplayed()
    }

    @Test
    fun networkToggleReportsChange() {
        val c = advanced(SandboxStatus.Ready(network = false))
        compose.onNodeWithTag("sandbox-network").performClick()
        assertTrue(c.network == listOf(true))
    }

    /**Used space:One line per conversation in the workspace;If there are tasks running, please indicate that they cannot be cleared or clicked.;Confirm before cleaning,Call back after confirmation. */
    @Test
    fun usageClearsAWorkspaceOnlyAfterConfirming() {
        val usage = com.meenai.mina.cap.sandbox.SandboxUsage(
            runtime = 300L shl 20, skills = 2L shl 20,
            workspaces = listOf(
                com.meenai.mina.cap.sandbox.WorkspaceUsage("c-a", "a", "Check express delivery", 5L shl 20, busy = true),
                com.meenai.mina.cap.sandbox.WorkspaceUsage("c-b", "b", "Organize tables", 1L shl 20, busy = false),
                com.meenai.mina.cap.sandbox.WorkspaceUsage("c-gone", null, null, 1024, busy = false),
            ),
        )
        val cleared = mutableListOf<String>()
        var all = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AdvancedSection(
                    status = SandboxStatus.Ready(network = false), networkAllowed = false, isolationIssue = false,
                    onNetworkChange = {}, onReset = {}, usage = usage,
                    onClearWorkspace = { cleared += it.dirName }, onClearAll = { all++ },
                )
            }
        }
        compose.onNodeWithText(SandboxCopy.USAGE_TITLE).assertExists()
        compose.onNodeWithText("Deleted conversation").assertExists()
        compose.onNodeWithText("There is a task running in this conversation and it cannot be cleared at the moment.").assertExists()
        compose.onNodeWithText("Check express delivery").performScrollTo().performClick()
        compose.onAllNodesWithText(SandboxCopy.CLEAR_TITLE).assertCountEquals(0)
        compose.onNodeWithText("Organize tables").performScrollTo().performClick()
        compose.onNodeWithText(SandboxCopy.CLEAR_TITLE).assertIsDisplayed()
        assertEquals(emptyList<String>(), cleared)
        compose.onNodeWithText(SandboxCopy.CLEAR).performClick()
        assertEquals(listOf("c-b"), cleared)
        compose.onNodeWithText(SandboxCopy.CLEAR_ALL).performScrollTo().performClick()
        compose.onNodeWithText(SandboxCopy.CLEAR_ALL_BODY).assertIsDisplayed()
        compose.onNodeWithText(SandboxCopy.CLEAR).performClick()
        assertEquals(1, all)
    }
}

