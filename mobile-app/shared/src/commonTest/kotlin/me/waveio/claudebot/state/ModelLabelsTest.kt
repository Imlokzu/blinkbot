package me.waveio.claudebot.state

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelLabelsTest {
    @Test
    fun rawGptIdsReadLikeTheirNamedSiblings() {
        // The owner saw "gpt-5.5-pro" in a list of "GPT-5.5", "GPT-6 Sol"…
        assertEquals("GPT-5.5 Pro", displayModelLabel("openai/gpt-5.5-pro", null))
        assertEquals("GPT-5.5 Pro", displayModelLabel("openai/gpt-5.5-pro", "gpt-5.5-pro"))
        assertEquals("GPT-OSS 120B", displayModelLabel("regolo/gpt-oss-120b", "gpt-oss-120b"))
        assertEquals("GPT-5.4 Mini", displayModelLabel("openai/gpt-5.4-mini", ""))
        assertEquals("GPT-4o Mini", displayModelLabel("openai/gpt-4o-mini", null))
    }

    @Test
    fun realNamesAndOtherFamiliesStayExactlyAsTheHostSentThem() {
        assertEquals("GPT-6 Sol", displayModelLabel("openai/gpt-6-sol", "GPT-6 Sol"))
        assertEquals("gpt-5 custom Name", displayModelLabel("x/gpt-5", "gpt-5 custom Name"))
        assertEquals("o3-mini", displayModelLabel("openai/o3-mini", null))
        assertEquals("MiniMax M3", displayModelLabel("omni/opencode-go/minimax-m3", "MiniMax M3"))
    }
}
