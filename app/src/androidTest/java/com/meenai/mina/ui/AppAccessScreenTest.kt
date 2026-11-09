package com.meenai.mina.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppAccessScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun appAccessLoadsWithoutModelAndRecommendedCanBeCancelled() {
        compose.setContent { com.meenai.mina.ui.theme.MinaTheme { AppAccessScreen(onBack = {}, classifyApps = false) } }
        compose.onNodeWithText("Application access").assertIsDisplayed()
        compose.onNodeWithText("Recommended to disable", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("It is recommended to disable financial trading applications").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("All allowed").performScrollTo().assertIsDisplayed()
    }
}
