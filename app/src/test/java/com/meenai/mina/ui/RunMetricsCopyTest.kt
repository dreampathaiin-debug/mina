package com.meenai.mina.ui

import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.core.record.RunMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunMetricsCopyTest {
    @get:Rule val texts = EnglishTexts()

    @Test fun durations() {
        assertEquals("0 s", RunMetricsCopy.duration(0))
        assertEquals("3.2 s", RunMetricsCopy.duration(3_240))
        assertEquals("45 s", RunMetricsCopy.duration(45_000))
        assertEquals("1 min 0 s", RunMetricsCopy.duration(59_960))
        assertEquals("2 min 13 s", RunMetricsCopy.duration(133_000))
        assertEquals("1 h 5 min", RunMetricsCopy.duration(3_900_000))
    }

    @Test fun breakdownShowsSharesTokensAndSteps() {
        val m = RunMetrics(totalMs = 10_000, modelMs = 5_000, observeMs = 2_000, actMs = 1_000, retryWaitMs = 500,
            inputTokens = 12_345, outputTokens = 100, cachedTokens = 10_000, steps = 4, observeCalls = 3, actCalls = 2, allCalls = 6)
        val rows = RunMetricsCopy.breakdown(m).toMap()
        assertEquals("5 s · 50%", rows[RunMetricsCopy.MODEL])
        assertEquals("2 s · 20%", rows[RunMetricsCopy.OBSERVE])
        assertEquals("1 s · 10%", rows[RunMetricsCopy.ACT])
        assertEquals("2 s · 20%", rows[RunMetricsCopy.OTHER])
        assertEquals("0.5 s", rows["Of which retry waits"])
        assertEquals("12.3K", rows["Input tokens"])
        assertEquals("10K", rows["Cache hits"])
        assertEquals("4 steps", rows["Steps"])
        assertEquals("3 / 2", rows["Reads / actions"])
        assertTrue(rows.getValue("All model requests").startsWith("6"))
    }
}
