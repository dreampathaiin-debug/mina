package com.meenai.mina.cap.phone.shell

import com.meenai.mina.core.uistate.SystemAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemIntentsTest {
    @Test fun alarmSkipsTheClockScreenAndCarriesEveryField() {
        val s = SystemIntents.spec(SystemAction.SetAlarm(9, 40, "meeting", listOf(2, 6), vibrate = false))
        assertEquals("android.intent.action.SET_ALARM", s.action)
        assertNull(s.data)
        assertEquals(
            mapOf(
                "android.intent.extra.alarm.HOUR" to 9,
                "android.intent.extra.alarm.MINUTES" to 40,
                "android.intent.extra.alarm.SKIP_UI" to true,
                "android.intent.extra.alarm.MESSAGE" to "meeting",
                "android.intent.extra.alarm.DAYS" to arrayListOf(2, 6),
                "android.intent.extra.alarm.VIBRATE" to false,
            ),
            s.extras,
        )
    }

    @Test fun oneTimeAlarmHasNoDaysOrOptionalFields() {
        val s = SystemIntents.spec(SystemAction.SetAlarm(7, 0))
        assertEquals(setOf("android.intent.extra.alarm.HOUR", "android.intent.extra.alarm.MINUTES", "android.intent.extra.alarm.SKIP_UI"), s.extras.keys)
    }

    @Test fun timerSkipsTheClockScreen() {
        val s = SystemIntents.spec(SystemAction.SetTimer(600, "instant noodles"))
        assertEquals("android.intent.action.SET_TIMER", s.action)
        assertEquals(600, s.extras["android.intent.extra.alarm.LENGTH"])
        assertEquals(true, s.extras["android.intent.extra.alarm.SKIP_UI"])
        assertEquals("instant noodles", s.extras["android.intent.extra.alarm.MESSAGE"])
    }

    @Test fun showAlarmsIsJustTheAction() {
        assertEquals(SystemIntents.Spec("android.intent.action.SHOW_ALARMS"), SystemIntents.spec(SystemAction.ShowAlarms))
    }

    @Test fun calendarEventOpensTheInsertScreenWithAnExplicitType() {
        val s = SystemIntents.spec(SystemAction.AddCalendarEvent("Physical examination", 1_000L, 2_000L, allDay = false, location = "Hospital", description = null))
        assertEquals("android.intent.action.INSERT", s.action)
        assertEquals("content://com.android.calendar/events", s.data)
        assertEquals("vnd.android.cursor.dir/event", s.type)
        assertEquals(mapOf("title" to "Physical examination", "beginTime" to 1_000L, "endTime" to 2_000L, "eventLocation" to "Hospital"), s.extras)
        val allDay = SystemIntents.spec(SystemAction.AddCalendarEvent("holiday", 1_000L, allDay = true))
        assertEquals(true, allDay.extras["allDay"])
    }

    @Test fun helperOnlyStartsTheseActions() {
        for (a in listOf(SystemAction.SetAlarm(1, 2), SystemAction.SetTimer(3), SystemAction.ShowAlarms, SystemAction.AddCalendarEvent("t", 0))) {
            val s = SystemIntents.spec(a)
            assertTrue(s.action, SystemIntents.allowed(s.action, s.data))
        }
        assertFalse(SystemIntents.allowed("android.intent.action.VIEW", "https://example.com"))
        assertFalse(SystemIntents.allowed("android.intent.action.INSERT", "content://contacts/people"))
        assertFalse(SystemIntents.allowed("android.intent.action.SET_ALARM", "content://x"))
        assertFalse(SystemIntents.allowed(null, null))
    }

    @Test fun choosingTheHandler() {
        val clock = SystemIntents.Handler("com.example.clock", "Set", system = true)
        val other = SystemIntents.Handler("com.example.other", "Set", system = false)
        assertNull(SystemIntents.choose(emptyList(), null))
        assertEquals(other, SystemIntents.choose(listOf(other), null))
        //If the user sets the default, use the default;Not set(What the system parses is the selector)First choose the one that comes with the system
        assertEquals(other, SystemIntents.choose(listOf(clock, other), "com.example.other"))
        assertEquals(clock, SystemIntents.choose(listOf(other, clock), "android"))
        assertEquals(other, SystemIntents.choose(listOf(other, other.copy(className = "B")), null))
    }
}
