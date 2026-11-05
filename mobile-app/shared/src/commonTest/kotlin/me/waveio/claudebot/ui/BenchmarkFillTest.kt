package me.waveio.claudebot.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BenchmarkFillTest {
    @Test fun theBarFillsTheSameShareAsThePrintedPercent() {
        // Owner: "it says 55% but looks like 70–80%". The bar used score ÷ best (0.55 / 0.76).
        assertEquals(.55f, benchmarkFill(.55), 1e-6f)
        assertEquals(0f, benchmarkFill(null))
        assertEquals(1f, benchmarkFill(1.4))
    }
}
