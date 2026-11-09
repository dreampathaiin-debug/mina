package com.meenai.mina.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.core.remote.RemoteActivity
import com.meenai.mina.core.remote.RelayState
import com.meenai.mina.mcp.McpServerState
import com.meenai.mina.ui.theme.MinaTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComputerAccessScreenTest {
    @get:Rule val compose = createComposeRule()

    private val example = ComputerAccessUi(
        McpServerState.Listening(8765), networkEnabled = true, usbEnabled = false, port = 8765,
        RemoteActivity(), listOf("192.168.1.23", "100.64.0.2"), "example-ui-test-value", "adb shell sh /example/connect-computer.sh",
    )
    private val state = mutableStateOf(example)
    private val networkChanges = mutableListOf<Boolean>()
    private val usbChanges = mutableListOf<Boolean>()
    private val copied = mutableListOf<String>()

    private fun page(initial: ComputerAccessUi = example) {
        state.value = initial
        compose.setContent {
            MinaTheme {
                ComputerAccessPage(state.value, {}, usbChanges::add, networkChanges::add, copied::add, {},
                    deviceSettings = { Text("Device settings slot") }, relayGuide = { mcp -> Text(if (mcp) "Remote MCP guide" else "Remote CLI guide") })
            }
        }
    }

    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    private fun networkGuide() {
        compose.onNodeWithTag("computer-mcp").performScrollTo().performClick()
        compose.onNodeWithTag("computer-network").performScrollTo().performClick()
    }

    @Test fun remoteGuideSelectionDoesNotEnableOtherTransports() {
        page(example.copy(networkEnabled = false, server = McpServerState.Off))
        compose.onNodeWithTag("computer-mcp").performScrollTo().performClick()
        compose.onNodeWithTag("computer-remote").performScrollTo().performClick()
        compose.onNodeWithText("Remote MCP guide").assertIsDisplayed()
        assertTrue(networkChanges.isEmpty()); assertTrue(usbChanges.isEmpty())
        compose.onNodeWithTag("computer-cli").performScrollTo().performClick()
        compose.onNodeWithText("Remote CLI guide").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("computer-remote").assertIsDisplayed()
    }

    @Test fun remoteOnlyStartupFailureIsShownUntilTheRelayStarts() {
        page(example.copy(networkEnabled = false, usbEnabled = false,
            server = McpServerState.Failed("test-start-denied"), relayEnabled = true, relayState = RelayState.Off))
        compose.onNodeWithText("Failed to open:test-start-denied").assertIsDisplayed()
        compose.onNodeWithText("Connecting to remote gateway...").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(relayState = RelayState.Online) }
        compose.onNodeWithText("Wait for computer to connect").assertIsDisplayed()
        compose.onNodeWithText("Failed to open:test-start-denied").assertDoesNotExist()
    }

    @Test fun remoteConnectionWaitsForActualRequestAndShowsRevocation() {
        page(example.copy(networkEnabled = false, server = McpServerState.Off, relayEnabled = true, relayState = RelayState.Online))
        compose.onNodeWithText("Wait for computer to connect").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(activity = RemoteActivity(1, 1_700_000_000_000)) }
        compose.onNodeWithText("Computer request received").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(relayState = RelayState.Unauthorized) }
        compose.onNodeWithText("The pairing has expired or been revoked. Please pair the gateway again.").assertIsDisplayed()
    }

    @Test fun cliIsDefaultAndSelectionDoesNotChangeAccessPermissions() {
        page(example.copy(networkEnabled = false, server = McpServerState.Off))
        compose.onNodeWithText("1. Prepare computer tools").assertIsDisplayed()
        compose.onNodeWithTag("mcp-enabled").assertDoesNotExist()
        compose.onNodeWithText("Device settings slot").assertDoesNotExist()
        tap("Copy installation instructions")
        compose.onAllNodesWithText("copy command")[0].performScrollTo().performClick()
        compose.onNodeWithText("mina doctor").assertDoesNotExist()
        assertTrue(copied[0].contains("docs/cli.md"))
        assertEquals(listOf("mina connect"), copied.drop(1))
        networkGuide()
        assertTrue(networkChanges.isEmpty())
        assertTrue(usbChanges.isEmpty())
        tap("Enable network access")
        assertEquals(listOf(true), networkChanges)
    }

    @Test fun cliSharesNetworkSelectionAndCopiesOriginWithTokenFile() {
        page()
        compose.onNodeWithTag("computer-network").performClick()
        tap("http://100.64.0.2:8765")
        compose.onNodeWithTag("computer-copy-cli-network").performScrollTo().performClick()
        assertEquals("mina connect --url http://100.64.0.2:8765 --token-file /path/to/token", copied.single())
        assertFalse(copied.single().contains(example.token!!))
        assertFalse(copied.single().contains("/mcp"))
        compose.onNodeWithText("mina doctor").assertDoesNotExist()
        compose.onNodeWithTag("computer-mcp").performScrollTo().performClick()
        compose.onNodeWithText("http://100.64.0.2:8765/mcp").assertExists()
        compose.onNodeWithTag("computer-cli").performScrollTo().performClick()
        compose.onNodeWithText("http://100.64.0.2:8765").assertExists()
        assertTrue(networkChanges.isEmpty())
        assertTrue(usbChanges.isEmpty())
        compose.runOnIdle { state.value = example.copy(token = null) }
        compose.onNodeWithTag("computer-copy-cli-network").assertIsNotEnabled()
    }

    @Test fun advancedSettingsAreHiddenUntilOpenedAndKeepExplicitControls() {
        page()
        tap("Advanced settings")
        compose.onNodeWithText("Device settings slot").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mcp-usb").performScrollTo().performClick()
        compose.onNodeWithTag("mcp-enabled").performScrollTo().performClick()
        assertEquals(listOf(true), usbChanges)
        assertEquals(listOf(false), networkChanges)
        tap("Collapse advanced settings")
        compose.onNodeWithTag("mcp-enabled").assertDoesNotExist()
        compose.onNodeWithText("Device settings slot").assertDoesNotExist()
    }

    @Test fun listeningWaitsForARequestAndOffOrFailureOverridesOldActivity() {
        page()
        compose.onNodeWithText("Wait for computer to connect").assertIsDisplayed()
        compose.onNodeWithText("Computer request received").assertDoesNotExist()
        compose.runOnIdle { state.value = example.copy(activity = RemoteActivity(1, 1_700_000_000_000)) }
        compose.onNodeWithText("Computer request received").assertIsDisplayed()
        compose.onNodeWithText("last request", substring = true).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(networkEnabled = false) }
        compose.onNodeWithText("Computer access is not enabled").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(networkEnabled = true, server = McpServerState.Failed("port busy")) }
        compose.onNodeWithText("port busy", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Computer request received").assertDoesNotExist()
    }

    @Test fun networkConfigMasksPreviewButCopiesTokenAndSelectedAddress() {
        page()
        networkGuide()
        compose.onNodeWithTag("computer-config-preview").assertDoesNotExist()
        tap("http://100.64.0.2:8765/mcp")
        tap("View configuration")
        compose.onNodeWithText(example.token!!, substring = true).assertDoesNotExist()
        compose.onNodeWithText("<token>", substring = true).assertExists()
        compose.onNodeWithTag("computer-copy-config").performScrollTo().performClick()
        val config = JSONObject(copied.single()).getJSONObject("mcpServers").getJSONObject("mina")
        assertEquals("http://100.64.0.2:8765/mcp", config.getString("url"))
        assertEquals("Bearer ${example.token}", config.getJSONObject("headers").getString("Authorization"))
        assertTrue(networkChanges.isEmpty())
        assertTrue(usbChanges.isEmpty())
        for (invalid in listOf(example.copy(addresses = emptyList()), example.copy(token = null),
            example.copy(server = McpServerState.Failed("port busy")))) {
            compose.runOnIdle { state.value = invalid }
            compose.onNodeWithTag("computer-copy-config").assertIsNotEnabled()
        }
    }

    @Test fun usbGuideCopiesBootstrapAndForwardWithoutNetworkCredentials() {
        page()
        compose.onNodeWithTag("computer-mcp").performClick()
        compose.onAllNodesWithText("copy command")[0].performScrollTo().performClick()
        compose.onAllNodesWithText("copy command")[1].performScrollTo().performClick()
        tap("Copy setup instructions")
        assertEquals(example.usbCommand, copied[0])
        assertEquals("adb forward tcp:8765 tcp:8765", copied[1])
        assertTrue(copied[2].contains("http://127.0.0.1:8765/mcp"))
        assertFalse(copied.any { it.contains(example.token!!) })
        assertTrue(networkChanges.isEmpty())
        assertTrue(usbChanges.isEmpty())
    }
}
