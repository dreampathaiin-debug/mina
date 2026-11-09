package com.meenai.mina.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.settings.AesGcmKeyCipher
import com.meenai.mina.settings.DeviceAccessSettings
import com.meenai.mina.ui.components.PageScaffold
import com.meenai.mina.ui.theme.MinaTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.crypto.KeyGenerator

class DeviceAccessSectionTest {
    @get:Rule val compose = createComposeRule()
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "device-access-ui-test")
    @After fun cleanup() { dir.deleteRecursively() }

    @Test fun credentialEditorRequiresConfirmationAndNeverShowsSavedValue() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val settings = DeviceAccessSettings(dir, AesGcmKeyCipher { key })
        compose.setContent { MinaTheme(dark = false, reduceMotion = true) {
            PageScaffold("Computer connection", {}) { DeviceAccessSection(settings) }
        } }
        compose.onNodeWithText("Lock screen credentials").performClick()
        compose.onNodeWithText("Save credentials").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("device-access-credential").performScrollTo().performTextInput("123456")
        compose.onNodeWithText("Save credentials").assertIsNotEnabled()
        compose.onNodeWithTag("device-access-repeat").performScrollTo().performTextInput("123456")
        compose.onNodeWithText("Save credentials").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { settings.credentialConfigured }
        compose.onNodeWithText("PIN saved · Click here to change").performScrollTo().assertIsDisplayed()
        settings.credential()!!.use { assertEquals("123456", String(it.value)) }
        compose.onNodeWithText("123456").assertDoesNotExist()
    }
}
