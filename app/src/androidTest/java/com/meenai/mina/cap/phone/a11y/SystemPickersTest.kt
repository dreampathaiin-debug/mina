package com.meenai.mina.cap.phone.a11y

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**Real machine:The package names of the system selector, file selector, and permission box can be parsed according to the standard Intent.,And ordinary apps will not be counted. */
@RunWith(AndroidJUnit4::class)
class SystemPickersTest {
    @Test
    fun resolvesThePickersOfThisDevice() {
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val pickers = SystemPickers.resolve(pm)
        //At least parse out the permissions box and file picker(AOSP has it)
        assertTrue(pickers.toString(), pickers.size >= 3)
        val documents = pm.resolveActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 0)
            ?.activityInfo?.packageName
        assertTrue("$documents not in $pickers", documents == null || documents in pickers)
        assertTrue(pickers.none { it == "com.android.settings" || it.startsWith("com.wangzan101") })
        assertTrue(SystemPickers.settingsPackage(pm) == DeviceSupport.SETTINGS)
    }
}
