package com.meenai.mina.ui

import com.meenai.mina.core.shell.HelperRestart
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.core.shell.BuiltinShellFailure
import com.meenai.mina.core.shell.BuiltinShellFix
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.core.shell.PairingChannelHealth
import com.meenai.mina.core.shell.PairingDiscovery
import com.meenai.mina.core.shell.PhoneSetupFlow
import com.meenai.mina.core.shell.SetupInputs
import com.meenai.mina.core.shell.SetupStep
import com.meenai.mina.core.shell.SetupSuspect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**A few lines of mobile operations on the guide page and "Advanced"(Just use fake data,Do not touch system settings). */
@RunWith(AndroidJUnit4::class)
class PhoneSetupScreenTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    private fun show(inputs: SetupInputs, headsUpLowered: Boolean = false, diagnostics: Boolean = false, scanning: Boolean = false) = compose.setContent {
        PhoneSetupContent(
            view = PhoneSetupFlow.view(inputs),
            onStepAction = { calls += "step $it" },
            onFailureFix = { calls += "fix $it" },
            onAllowNotifications = { calls += "notify" },
            onConfirmStep = { calls += "confirm $it" },
            onSubmit = { c, p -> calls += "submit $c $p" },
            onClose = { calls += "close" },
            headsUpLowered = headsUpLowered,
            onOpenHeadsUp = { calls += "headsUp" },
            onAppInfo = { calls += "appInfo" },
            onSkipAccessibility = { calls += "skipA11y" },
            onRevisit = { calls += "revisit $it" },
            onOpenPairingChannel = { calls += "pairingChannel" },
            onDiagnostics = if (diagnostics) ({ calls += "diagnostics" }) else null,
            scanning = scanning,
            onConnectPort = { calls += "port $it" },
        )
    }

    /**Can't connect:"Talk to you later" is still there,;,Not even. */
    @Test
    fun failedConnectOffersLaterAndManualPort() {
        show(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)))
        compose.onNodeWithTag("setup-later").assertIsDisplayed()
        compose.onNodeWithTag("setup-connect-port").performScrollTo().performTextInput("70000")
        compose.onNodeWithTag("setup-connect-port-submit").performScrollTo().performClick()
        compose.onNodeWithText(PhoneSetupCopy.PORT_INVALID).assertExists()
        compose.onNodeWithTag("setup-connect-port").performTextClearance()
        compose.onNodeWithTag("setup-connect-port").performTextInput("41235")
        compose.onNodeWithTag("setup-connect-port-submit").performScrollTo().performClick()
        compose.onNodeWithTag("setup-later").performClick()
        assertEquals(listOf("port 41235", "close"), calls)
    }

    /**This environment does not have developer options and wireless debugging:Explain it from the beginning,No steps listed,"Got it" and leave. */
    @Test
    fun unavailableEnvironmentExplainsUpFrontWithoutSteps() {
        show(SetupInputs(false, false, BuiltinShellStatus.NotPaired, wirelessMissing = true, developerSettingsMissing = true))
        compose.onNodeWithTag("setup-unavailable").assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.UNAVAILABLE).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.DeveloperOptions).title).assertDoesNotExist()
        compose.onNodeWithTag("setup-later").assertDoesNotExist()
        compose.onNodeWithTag("setup-got-it").performClick()
        assertEquals(listOf("close"), calls)
    }

    /**Connecting:You can also "talk to me later";Tell the truth when looking for a port on this machine. */
    @Test
    fun workingOffersLaterAndShowsPortSearch() {
        show(SetupInputs(true, true, BuiltinShellStatus.Connecting), scanning = true)
        compose.onNodeWithText(PhoneSetupCopy.SCANNING_PORTS).assertIsDisplayed()
        compose.onNodeWithTag("setup-connect-port").assertDoesNotExist()
        compose.onNodeWithTag("setup-later").assertIsDisplayed().performClick()
        assertEquals(listOf("close"), calls)
    }

    /**Failed at pairing step(Wrong code)Do not fill in the connection port when,"I'll talk about it later" is still there. */
    @Test
    fun pairingFailureHasLaterButNoConnectPort() {
        show(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode)))
        compose.onNodeWithTag("setup-later").assertIsDisplayed()
        compose.onNodeWithTag("setup-connect-port").assertDoesNotExist()
    }

    /**One step to match, no problem skipped:The pairing code can only be filled in the notification. */
    private val pairingWithoutA11y = SetupInputs(true, true, BuiltinShellStatus.NotPaired, accessibility = false, accessibilitySkipped = true)

    /**The bottom of "Copy Diagnostic Information" is always;There is also one in the step that went wrong when it failed. */
    @Test
    fun diagnosticsButtonAtBottomAndInFailedStep() {
        show(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)), diagnostics = true)
        compose.onNodeWithTag("setup-diagnostics-failed").performScrollTo().performClick()
        compose.onNodeWithTag("setup-diagnostics").assertIsDisplayed().performClick()
        assertEquals(listOf("diagnostics", "diagnostics"), calls)
    }

    @Test
    fun diagnosticsButtonInGuidePhase() {
        show(SetupInputs(false, false, BuiltinShellStatus.NotPaired), diagnostics = true)
        compose.onNodeWithTag("setup-diagnostics").assertIsDisplayed()
        compose.onNodeWithTag("setup-diagnostics-failed").assertDoesNotExist()
    }

    @Test
    fun firstStepOpensAboutPhone() {
        show(SetupInputs(false, false, BuiltinShellStatus.NotPaired))
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.DeveloperOptions).title).assertIsDisplayed()
        compose.onNodeWithTag("setup-action").performClick()
        compose.onNodeWithTag("setup-later").performClick()
        assertEquals(listOf("step DeveloperOptions", "close"), calls)
    }

    @Test
    fun accessibilityStepOpensSettingsOffersAppInfoAndCanBeSkipped() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired, accessibility = false, restrictedSettings = true))
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.Accessibility).title).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.ACCESSIBILITY_SKIP).assertIsDisplayed()
        compose.onNodeWithTag("setup-action").performClick()
        compose.onNodeWithTag("setup-app-info").performClick()
        compose.onNodeWithTag("setup-skip-a11y").performClick()
        assertEquals(listOf("step Accessibility", "appInfo", "skipA11y"), calls)
        //No obstacles, just jump over and go down,Do not give another "opened"
        compose.onNodeWithTag("setup-confirm").assertDoesNotExist()
    }

    @Test
    fun everySwitchStepHasAManualNext() {
        //Everything reads closed:The first step is to click "I have opened it",Next step"
        show(SetupInputs(false, false, BuiltinShellStatus.NotPaired, usbDebugging = false, accessibility = false))
        compose.onNodeWithText(PhoneSetupCopy.TURNED_ON_NEXT).assertIsDisplayed()
        compose.onNodeWithTag("setup-confirm").performClick()
        assertEquals(listOf("confirm DeveloperOptions"), calls)
    }

    @Test
    fun wirelessStepHasAManualNext() {
        show(SetupInputs(true, false, BuiltinShellStatus.NotPaired))
        compose.onNodeWithTag("setup-confirm").performScrollTo().performClick()
        assertEquals(listOf("confirm WirelessDebugging"), calls)
    }

    @Test
    fun doneStepsCanBeTappedToRedo() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired))
        compose.onNodeWithTag("setup-step-UsbDebugging").performClick()
        //You can’t order anything you haven’t done later.
        compose.onNodeWithTag("setup-step-Connect").performClick()
        compose.onNodeWithText(PhoneSetupCopy.REVISIT_NOTE).performScrollTo().assertIsDisplayed()
        assertEquals(listOf("revisit UsbDebugging"), calls)
    }

    @Test
    fun revisitedStepOffersNextAndBack() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired, revisit = SetupStep.UsbDebugging))
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.UsbDebugging).body).assertIsDisplayed()
        compose.onNodeWithTag("setup-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("setup-revisit-back").performScrollTo().performClick()
        assertEquals(listOf("confirm UsbDebugging", "revisit null"), calls)
    }

    @Test
    fun connectionFailureSaysWhichStepToCheckAndGoesBack() {
        show(SetupInputs(true, null, BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)))
        compose.onNodeWithText(PhoneSetupCopy.suspect(SetupSuspect(SetupStep.WirelessDebugging, unreadable = true))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup-suspect-back").performScrollTo().performClick()
        assertEquals(listOf("revisit WirelessDebugging"), calls)
    }

    @Test
    fun pairingWithoutAccessibilityShowsTheThreeStepGuide() {
        show(pairingWithoutA11y)
        compose.onNodeWithTag("setup-pairing-guide").performScrollTo().assertIsDisplayed()
        PhoneSetupCopy.NOTIFY_GUIDE.forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithTag("setup-pairing-hint").assertDoesNotExist()
        //Good notification:The main button is still to go to wireless debugging,There is no button to edit the notification.
        compose.onNodeWithTag("setup-notify-fix").assertDoesNotExist()
        compose.onNodeWithTag("setup-action").performScrollTo().performClick()
        assertEquals(listOf("step PairingCode"), calls)
    }

    @Test
    fun pairingWithAccessibilityShowsTheAutoReadHint() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired))
        compose.onNodeWithText(PhoneSetupCopy.AUTO_READ_HINT).assertIsDisplayed()
        compose.onNodeWithTag("setup-pairing-guide").assertDoesNotExist()
    }

    @Test
    fun pairingAsksToAllowNotificationsFirstWhenTheyAreOff() {
        show(pairingWithoutA11y.copy(notificationsAllowed = false))
        compose.onNodeWithText(PhoneSetupCopy.NEED_NOTIFICATIONS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup-notify-fix").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("notify"), calls)
    }

    @Test
    fun pairingOpensTheChannelSettingsWhenPairingNotificationsAreOff() {
        show(pairingWithoutA11y.copy(pairingChannel = PairingChannelHealth.Off))
        compose.onNodeWithText(PhoneSetupCopy.PAIRING_CHANNEL_OFF).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.OPEN_PAIRING_CHANNEL).performScrollTo().performClick()
        assertEquals(listOf("pairingChannel"), calls)
    }

    @Test
    fun pairingWarnsWhenPairingNotificationsDoNotPopUp() {
        show(pairingWithoutA11y.copy(pairingChannel = PairingChannelHealth.Lowered))
        compose.onNodeWithTag("setup-pairing-lowered").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup-notify-fix").assertDoesNotExist()
        compose.onNodeWithTag("setup-pairing-lowered-open").performScrollTo().performClick()
        assertEquals(listOf("pairingChannel"), calls)
    }

    @Test
    fun pairingStepWarnsWhenHeadsUpIsLowered() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired, PairingDiscovery.Searching, notificationsAllowed = true), headsUpLowered = true)
        compose.onNodeWithTag("setup-heads-up").assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.HEADS_UP_LOWERED).assertIsDisplayed()
        compose.onNodeWithTag("setup-heads-up-open").performClick()
        assertEquals(listOf("headsUp"), calls)
    }

    @Test
    fun pairingStepTakesAManualCode() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired, PairingDiscovery.Searching))
        compose.onNodeWithTag("setup-manual").performClick()
        compose.onNodeWithTag("setup-code").performTextInput("123 456")
        compose.onNodeWithTag("setup-port").performTextInput("37001")
        //When the keyboard pops up on a real phone, the buttons will be pushed below the visible area.,
        compose.onNodeWithTag("setup-submit").performScrollTo().performClick()
        assertEquals(listOf("submit 123456 37001"), calls)
    }

    @Test
    fun failureShowsReasonAndOneTapFix() {
        show(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed)))
        compose.onNodeWithText(PhoneSetupCopy.failure(BuiltinShellFailure.HelperStartFailed).title).assertIsDisplayed()
        compose.onNodeWithTag("setup-fix").performClick()
        assertEquals(listOf("fix ${BuiltinShellFix.Retry}"), calls)
    }

    @Test
    fun successKeepsChecklistAndWaitsForTheDoneButton() {
        show(SetupInputs(true, true, BuiltinShellStatus.Running))
        compose.onNodeWithText(PhoneSetupCopy.SUCCESS_LINE).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.DeveloperOptions).title).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.successNote(HelperRestart.None)).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.SUCCESS_DONE).assertIsDisplayed()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("setup-done").performClick()
        assertEquals(listOf("close"), calls)
    }

    /**"Start using computer instead" usually does not appear:The wireless debugging step only has one line "Cannot find wireless debugging.?",Only after clicking did the command and copy appear in this step. */
    @Test
    fun usbCardAppearsOnlyWhenAskedOnTheWirelessStep() {
        val wirelessStep = SetupInputs(true, false, BuiltinShellStatus.NotPaired)
        compose.setContent {
            var asked by remember { mutableStateOf(false) }
            PhoneSetupContent(
                view = PhoneSetupFlow.view(wirelessStep.copy(usbAsked = asked)),
                onStepAction = {}, onFailureFix = {}, onAllowNotifications = {}, onConfirmStep = {}, onSubmit = { _, _ -> }, onClose = {},
                usbCommand = "adb shell sh /sdcard/usb-start.sh",
                onAskUsb = { asked = true },
                onCopyUsbCommand = { calls += "copy" },
            )
        }
        compose.onNodeWithTag("setup-usb").assertDoesNotExist()
        compose.onNodeWithTag("setup-usb-ask").performScrollTo().performClick()
        compose.onNodeWithTag("setup-usb-ask").assertDoesNotExist()
        compose.onNodeWithText(PhoneSetupCopy.USB_TITLE).assertExists()
        compose.onNodeWithTag("setup-usb-copy").performScrollTo().performClick()
        assertEquals(listOf("copy"), calls)
    }

    /**Go to the pairing step normally:There is no "Wireless debugging not found"?",There are no cards either. */
    @Test
    fun normalPairingStepHasNoUsbOption() {
        compose.setContent {
            PhoneSetupContent(
                view = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.NotPaired)),
                onStepAction = {}, onFailureFix = {}, onAllowNotifications = {}, onConfirmStep = {}, onSubmit = { _, _ -> }, onClose = {},
                usbCommand = "adb shell sh /sdcard/usb-start.sh",
            )
        }
        compose.onNodeWithTag("setup-usb").assertDoesNotExist()
        compose.onNodeWithTag("setup-usb-ask").assertDoesNotExist()
    }
}
