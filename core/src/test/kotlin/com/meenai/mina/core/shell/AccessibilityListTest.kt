package com.meenai.mina.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityListTest {
    private val mina = PermissionCommands.accessibilityService("com.meenai.mina")
    private val minaLong = "com.meenai.mina/com.meenai.mina.a11y.MinaAccessibilityService"
    private val other = "com.example.reader/.ReaderService"
    private val other2 = "org.sample.keys/org.sample.keys.KeyService"

    @Test
    fun emptyOrUnsetValues() {
        listOf(null, "", "   ", "null", "null\n", ":", " : ").forEach { raw ->
            assertEquals("raw=$raw", emptyList<String>(), AccessibilityList.entries(raw))
            assertFalse(AccessibilityList.contains(raw, mina))
            assertEquals(mina, AccessibilityList.with(raw, mina))
            assertEquals("", AccessibilityList.without(raw, mina))
        }
    }

    @Test
    fun appendKeepsOtherServicesInOrder() {
        assertEquals("$other:$mina", AccessibilityList.with(other, mina))
        assertEquals("$other:$other2:$mina", AccessibilityList.with("$other:$other2\n", mina))
    }

    @Test
    fun alreadyPresentIsNotDuplicated() {
        assertEquals("$other:$mina", AccessibilityList.with("$other:$mina", mina))
        // 系统里存的是完整类名写法,也算已经有了
        assertEquals("$minaLong:$other", AccessibilityList.with("$minaLong:$other", mina))
        assertTrue(AccessibilityList.contains(minaLong, mina))
    }

    @Test
    fun removeKeepsOthersAndHandlesBothSpellings() {
        assertEquals("$other:$other2", AccessibilityList.without("$other:$mina:$other2", mina))
        assertEquals(other, AccessibilityList.without("$minaLong:$other", mina))
        assertEquals(other, AccessibilityList.without(other, mina))
        assertEquals("", AccessibilityList.without(mina, mina))
    }

    @Test
    fun strayColonsAndSpacesAreCleanedUp() {
        assertEquals("$other:$other2:$mina", AccessibilityList.with(":$other:: $other2 :", mina))
        assertEquals("$other:$other2", AccessibilityList.without("$other::$mina:$other2:", mina))
    }

    @Test
    fun differentServiceOfSamePackageIsNotConfused() {
        val sibling = "com.meenai.mina/.a11y.OtherService"
        assertFalse(AccessibilityList.contains(sibling, mina))
        assertEquals(sibling, AccessibilityList.without("$sibling:$mina", mina))
    }
}
