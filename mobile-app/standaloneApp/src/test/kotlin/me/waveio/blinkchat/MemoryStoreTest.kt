package me.waveio.blinkchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory is the feature users trust with personal facts, so the store must prove it
 * never duplicates silently, never drops the store on bad reviewer output, and never
 * injects beyond its cap.
 */
class MemoryStoreTest {
    private fun memory(id: String, content: String, muted: Boolean = false) =
        StoredMemory(id, content, createdAt = 1, updatedAt = 1, muted = muted)

    @Test
    fun `remember saves a clean fact and dedups case-insensitively`() {
        val (afterFirst, saved) = MemoryStore.remember(emptyList(), "My cat is called Mura.", nowSeconds = 10)
        assertTrue(saved)
        assertEquals(listOf("My cat is called Mura."), afterFirst.map { it.content })
        val (afterSecond, savedAgain) = MemoryStore.remember(afterFirst, "my cat is called mura", nowSeconds = 20)
        assertTrue(!savedAgain)
        // Trailing punctuation alone must not create a second fact.
        val (afterPunct, savedPunct) = MemoryStore.remember(afterFirst, "my cat is called Mura", nowSeconds = 30)
        assertTrue(!savedPunct)
        assertEquals(afterFirst, afterPunct)
        assertEquals(afterFirst, afterSecond)
    }

    @Test
    fun `remember collapses whitespace and rejects junk`() {
        val (withFact, saved) = MemoryStore.remember(emptyList(), "  I live\n\tin  Lviv  ", nowSeconds = 1)
        assertTrue(saved)
        assertEquals("I live in Lviv", withFact.single().content)
        assertTrue(!MemoryStore.remember(withFact, "   ", nowSeconds = 2).second)
        assertTrue(!MemoryStore.remember(withFact, "x".repeat(MemoryLimits.FACT_CHARS + 1), nowSeconds = 2).second)
    }

    @Test
    fun `forget removes by substring only when present`() {
        val existing = listOf(memory("1", "My cat is called Mura"), memory("2", "I live in Lviv"))
        assertEquals(listOf("I live in Lviv"), MemoryStore.forget(existing, "cat").map { it.content })
        assertEquals(existing, MemoryStore.forget(existing, "dog"))
        assertEquals(existing, MemoryStore.forget(existing, "   "))
    }

    @Test
    fun `applies add replace and remove operations`() {
        val existing = listOf(memory("1", "I like tea"))
        val ops = parseMemoryOperations(
            """{"operations":[
                {"action":"add","content":"My cat is called Mura"},
                {"action":"replace","id":"1","content":"I only drink coffee now"},
                {"action":"remove","id":"does-not-exist"}]}""")
        val updated = applyMemoryOperations(existing, ops, nowSeconds = 99)
        assertEquals(listOf("I only drink coffee now", "My cat is called Mura"), updated.map { it.content })
        assertEquals(99, updated[0].updatedAt)
        assertEquals(99, updated[1].updatedAt)
    }

    @Test
    fun `garbage reviewer output yields no operations`() {
        for (garbage in listOf("", "no json here", "{\"operations\": \"not a list\"}", "[1,2,3]", "{\"operations\":[{\"action\":\"nuke\"}]}")) {
            val ops = parseMemoryOperations(garbage)
            assertTrue("garbage must parse to nothing: $garbage", ops.isEmpty() || ops.all { it.action in setOf("add", "replace", "remove") })
        }
        // The nuke case must be filtered, not passed through.
        assertEquals(emptyList<MemoryOperation>(), parseMemoryOperations("{\"operations\":[{\"action\":\"nuke\"}]}"))
    }

    @Test
    fun `reviewer json wrapped in prose still parses`() {
        val prose = "Sure! Here is the result:\n```json\n{\"operations\":[{\"action\":\"add\",\"content\":\"Drives a Skoda\"}]}\n```"
        assertEquals(listOf("add"), parseMemoryOperations(prose).map { it.action })
        assertEquals("Drives a Skoda", parseMemoryOperations(prose).single().content)
    }

    @Test
    fun `add respects dedup and the store cap`() {
        var store = listOf(memory("1", "I like tea"))
        val ops = listOf(MemoryOperation("add", null, "i LIKE tea"), MemoryOperation("add", null, "brand new fact"))
        store = applyMemoryOperations(store, ops, nowSeconds = 5)
        assertEquals(listOf("I like tea", "brand new fact"), store.map { it.content })
        var full = (1..MemoryLimits.MAX_FACTS).map { memory("f$it", "fact $it") }
        full = applyMemoryOperations(full, listOf(MemoryOperation("add", null, "one more")), nowSeconds = 5)
        assertEquals(MemoryLimits.MAX_FACTS, full.size)
    }

    @Test
    fun `context block lists facts and stops at the char cap`() {
        val existing = listOf(memory("1", "My cat is called Mura"), memory("2", "I live in Lviv", muted = true))
        val block = MemoryStore.contextBlock(existing)
        assertTrue(block!!.contains("- My cat is called Mura"))
        assertTrue(!block.contains("Lviv"))
        assertNull(MemoryStore.contextBlock(emptyList()))
        val tiny = MemoryStore.contextBlock(existing, charLimit = 30)
        assertNull(tiny)
    }

    @Test
    fun `review transcript is bounded and labeled`() {
        val messages = (1..30).map { StoredMessage(it.toString(), if (it % 2 == 0) "user" else "assistant", "turn $it") }
        val transcript = reviewTranscript(messages)
        assertTrue(transcript.contains("User: turn 30"))
        assertTrue(!transcript.contains("turn 5"))
        assertEquals(MemoryLimits.REVIEW_TRANSCRIPT_MESSAGES, transcript.lines().size)
    }

    @Test
    fun `review task embeds existing ids so replace and remove can target them`() {
        val task = memoryReviewTask(listOf(memory("abc", "I like tea")), "User: hi")
        assertTrue(task.contains("id=abc I like tea"))
        assertTrue(task.contains("Conversation transcript:"))
    }
}
