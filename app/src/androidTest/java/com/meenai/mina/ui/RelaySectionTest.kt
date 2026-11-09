package com.meenai.mina.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.meenai.mina.core.remote.RelayState
import com.meenai.mina.settings.RelayConfig
import com.meenai.mina.ui.components.PageScaffold
import com.meenai.mina.ui.theme.MinaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RelaySectionTest {
    @get:Rule val compose = createComposeRule()
    private val config = mutableStateOf(RelayConfig())
    private val state = mutableStateOf(RelayState.Off)
    private val changes = mutableListOf<Boolean>()
    private val deviceChanges = mutableListOf<Boolean>()
    private var paired: Pair<String, String>? = null
    private var unpaired = 0
    private var copied = 0

    private fun page() {
        compose.setContent { MinaTheme {
            PageScaffold("remote", {}) {
                RelayControls(config.value, state.value, false, false,
                    { url, code -> paired = url to code }, changes::add, { unpaired++ }, { copied++ }, { copied++ },
                    onDeviceAccess = deviceChanges::add)
            }
        } }
    }

    @Test fun pairingRequiresHttpsAndClearsCodeWithoutEnablingAccess() {
        page()
        compose.onNodeWithTag("relay-pair").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("relay-code").performTextInput("example-pairing-code")
        compose.onNodeWithTag("relay-pair").assertIsNotEnabled()
        compose.onNodeWithTag("relay-gateway").performTextInput("https://relay.example")
        compose.onNodeWithTag("relay-pair").performScrollTo().performClick()
        assertEquals("https://relay.example" to "example-pairing-code", paired)
        assertTrue(changes.isEmpty())
        compose.onNodeWithTag("relay-code").assertTextEquals("")
    }

    @Test fun pairedSettingsKeepTokensHiddenAndRequireExplicitEnableAndUnpair() {
        config.value = RelayConfig(gateway = "https://relay.example/")
        page()
        compose.onNodeWithText("Paired, remote access is not enabled").assertIsDisplayed()
        compose.onNodeWithTag("relay-code").assertDoesNotExist()
        compose.onNodeWithTag("relay-enabled").performClick()
        assertEquals(listOf(true), changes)
        compose.onNodeWithTag("relay-device-access").performScrollTo().assertIsOff().performClick()
        assertEquals(listOf(true), deviceChanges)
        compose.onNodeWithText("Copy MCP address").performScrollTo().performClick()
        assertEquals(1, copied)
        compose.onNodeWithText("Unpair gateway").performScrollTo().performClick()
        assertEquals(0, unpaired)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, unpaired)
    }

    @Test fun onlineAndRevokedStateAreShownAsObserved() {
        config.value = RelayConfig(true, "https://relay.example/")
        state.value = RelayState.Online
        page()
        compose.onNodeWithText("Gateway is connected, waiting CLI /MCP request").assertIsDisplayed()
        compose.runOnIdle { state.value = RelayState.Unauthorized }
        compose.onNodeWithText("The pairing has expired or been revoked. Please pair the gateway again.").assertIsDisplayed()
        compose.runOnIdle { state.value = RelayState.Incompatible }
        compose.onNodeWithText("Please update Mina and gateway to matching versions").assertIsDisplayed()
    }
}
