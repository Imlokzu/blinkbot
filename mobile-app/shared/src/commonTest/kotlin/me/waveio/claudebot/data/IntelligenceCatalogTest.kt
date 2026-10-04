package me.waveio.claudebot.data

import kotlinx.serialization.json.Json
import kotlin.test.*

class IntelligenceCatalogTest {
    @Test fun intelligenceCatalogPreservesScoresAndIgnoresNewServerFields() {
        val catalog = Json { ignoreUnknownKeys = true }.decodeFromString<IntelligenceCatalog>("""
            {"available":true,"updated":12,"source":{"name":"Epoch","url":"https://example.test","license":"CC"},
             "benchmarks":[{"key":"gpqa","name":"GPQA Diamond","top":0.91}],
             "models":{"provider/model":{"index":72.4,"scores":{"gpqa":0.81}}},"future_field":true}
        """.trimIndent())
        assertTrue(catalog.available)
        assertEquals(72.4, catalog.models.getValue("provider/model").index)
        assertEquals(0.81, catalog.models.getValue("provider/model").scores["gpqa"])
        assertEquals("Epoch", catalog.source.name)
    }
}
