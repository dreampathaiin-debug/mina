package com.meenai.mina.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.core.memory.MemoryItem
import com.meenai.mina.core.memory.MemoryKind
import com.meenai.mina.core.memory.MemoryPersistence
import com.meenai.mina.core.memory.MemorySource
import com.meenai.mina.core.memory.MemoryStatus
import com.meenai.mina.core.memory.MemoryStore
import com.meenai.mina.settings.MemorySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MemoryScreenTest {

    @get:Rule val compose = createComposeRule()

    private val store = MemoryStore(object : MemoryPersistence {
        override fun load() = emptyList<MemoryItem>()
        override fun save(items: List<MemoryItem>) {}
    }, System::currentTimeMillis, { java.util.UUID.randomUUID().toString() })

    private fun show() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val vm = MemoryViewModel(store, MemorySettings(File(dir, "memory-screen-test.json").apply { delete() }), null,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        compose.setContent { MemoryScreen(vm) {} }
    }

    @Test fun addEditDeleteRoundTrip() {
        show()
        compose.onNodeWithText("").performClick()
        compose.onNodeWithTag("memory-content").performTextInput("The user lives in Zhangjiang")
        compose.onNodeWithTag("memory-save").performClick()
        //Click a line to raise the operation panel,Select "Change"
        compose.onNodeWithText("The user lives in Zhangjiang").performClick()
        compose.onNodeWithText("Change it").performClick()
        compose.onNodeWithTag("memory-content").performTextClearance()
        compose.onNodeWithTag("memory-content").performTextInput("The user lives in Zhangjiang Hi-Tech")
        compose.onNodeWithTag("memory-save").performClick()
        compose.waitForIdle()
        assertEquals("The user lives in Zhangjiang Hi-Tech", store.items.value.single().content)
        compose.onNodeWithText("The user lives in Zhangjiang Hi-Tech").performClick()
        compose.onNodeWithText("delete").performClick()
        compose.waitForIdle()
        assertTrue(store.items.value.isEmpty())
    }

    @Test fun approveProcedure() {
        store.add(MemoryKind.procedure, "Order takeout and compare prices first", emptyList(), MemorySource("user_said", "Compare prices first when ordering takeout next time", atMs = 1)).getOrThrow()
        show()
        compose.onNodeWithTag("segment-Pending").performClick()
        compose.onNodeWithText("good").performClick()
        compose.waitForIdle()
        assertEquals(MemoryStatus.active, store.items.value.single().status)
        //After approval, take it from "Pending Confirmation",appear in memory list
        compose.onNodeWithText("Nothing waiting for your confirmation").assertExists()
        compose.onNodeWithTag("segment-Memories").performClick()
        assertEquals(1, compose.onAllNodesWithTag("memory-row").fetchSemanticsNodes().size)
    }
}
