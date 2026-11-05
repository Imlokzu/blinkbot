package me.waveio.claudebot.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaveHistoryTest {
    @Test fun barsTraceSpeechOverTimeInsteadOfCopyingOneLevel() {
        val wave = WaveHistory(size = 4)
        listOf(.1f, .9f, .3f).forEach(wave::push)
        // Newest on the right, older samples moved left: the shape is the recent speech.
        assertEquals(listOf(0f, .1f, .9f, .3f), wave.samples.toList())
        wave.push(2f)
        assertEquals(1f, wave.samples.last(), "Levels stay within the bar height")
    }

    @Test fun stoppingLetsTheHistorySinkToTheBaseline() {
        val wave = WaveHistory(size = 3)
        wave.push(1f)
        var ticks = 0
        while (wave.decay()) ticks++
        assertTrue(ticks in 5..30)
        assertTrue(wave.samples.all { it == 0f })
        assertFalse(wave.decay())
    }
}
