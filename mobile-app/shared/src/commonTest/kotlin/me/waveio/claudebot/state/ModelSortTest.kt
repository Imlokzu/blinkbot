package me.waveio.claudebot.state

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelSortTest {
    private fun row(id: String, provider: String, available: Boolean = true) =
        ModelRow(id, id.substringAfterLast('/'), provider, provider, available)

    @Test
    fun chatgptLeadsAndVersionsDescend() {
        val models = listOf(
            row("regolo/luna", "regolo"),
            row("openai/gpt-6-sol", "chatgpt"),
            row("openai/gpt-6.1-sol", "chatgpt"),
            row("openai/astra", "chatgpt"),
            row("openai/gpt-6-mini-sol", "chatgpt"),
            row("anthropic/claude-opus-4", "anthropic"),
        )
        val order = models.sortedWith(modelComparator).map { it.id }
        assertEquals(
            listOf(
                "openai/astra",
                "openai/gpt-6.1-sol",
                "openai/gpt-6-sol",
                "openai/gpt-6-mini-sol",
                "regolo/luna",
                "anthropic/claude-opus-4",
            ),
            order,
        )
    }

    @Test
    fun unavailableModelsSinkToTheEnd() {
        val models = listOf(
            row("openai/gpt-6.1-sol", "chatgpt", available = false),
            row("regolo/sol", "regolo"),
        )
        assertEquals("regolo/sol", models.sortedWith(modelComparator).first().id)
    }
}
