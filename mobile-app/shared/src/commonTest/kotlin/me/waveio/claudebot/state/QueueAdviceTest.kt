package me.waveio.claudebot.state

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The Queue screen must explain an item from its own state, never from a guess. */
class QueueAdviceTest {
    @Test fun hostFailuresNameTheirRealCauseAndLeadWithTheStepThatFixesIt() {
        val failed = queueAdvice(PendingRow("job_1", "hi", "failed", error = "turn_failed"))
        assertEquals("queue.reason.failed", failed.reason)
        assertEquals(QueueStep.Retry, failed.recommended)
        val cut = queueAdvice(PendingRow("job_2", "hi", "failed", error = "incomplete_stream"))
        assertEquals("queue.reason.incomplete", cut.reason)
        // Retrying with the same model would fail the same way; the chat is where the model changes.
        val image = queueAdvice(PendingRow("job_3", "hi", "failed", error = "mobile_image_model_unavailable"))
        assertEquals("queue.reason.imageModel", image.reason)
        assertEquals(QueueStep.Open, image.recommended)
        assertEquals("queue.reason.interrupted", queueAdvice(PendingRow("job_4", "hi", "interrupted")).reason)
    }

    @Test fun cancelSaysWhatItReallyDoesForEachKind() {
        assertEquals("queue.cancelHint.local", queueAdvice(PendingRow("local:a", "hi", "pending")).cancelHint)
        // The host pauses the whole chat queue when a waiting job is stopped.
        assertEquals("queue.cancelHint.waiting", queueAdvice(PendingRow("job", "hi", "queued")).cancelHint)
        val ended = queueAdvice(PendingRow("job", "hi", "failed", error = "turn_failed"))
        assertEquals("queue.clear", ended.cancelLabel)
        assertEquals("queue.cancelHint.ended", ended.cancelHint)
    }

    @Test fun waitingItemsOfferRetryOnlyWhenSomethingIsActuallyStuck() {
        val waiting = queueAdvice(PendingRow("job", "hi", "queued"))
        assertFalse(waiting.canRetry)
        assertEquals(QueueStep.None, waiting.recommended)
        assertEquals(QueueStep.Continue, queueAdvice(PendingRow("job", "hi", "queued", paused = true)).recommended)
        assertEquals("queue.reason.offline", queueAdvice(PendingRow("local:a", "hi", "pending")).reason)
        assertEquals(QueueStep.SendNow, queueAdvice(PendingRow("local:a", "hi", "pending", declined = true)).recommended)
        assertEquals("queue.reason.refused", queueAdvice(PendingRow("local:a", "hi", "failed", error = "invalid_attachment")).reason)
    }

    private val fixtures = mutableListOf<ControllerTestFixture>()
    private fun controllerTest(block: suspend TestScope.() -> Unit): TestResult = runTest(timeout = 10.seconds) {
        try { block() } finally { fixtures.forEach { it.close() }; fixtures.clear(); runCurrent() }
    }

    @Test fun clearingAnEndedHostJobRemovesItForGoodWithoutAStopRequest() = controllerTest {
        val fixture = ControllerTestFixture(StandardTestDispatcher(testScheduler)).also { fixtures += it }
        fixture.handler = { request ->
            if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Get)
                jsonResponse("""{"messages":[{"id":"job_dead","session_id":"chat_1","state":"failed","message":"Lost reply","error":"incomplete_stream"}]}""")
            else null
        }
        runCurrent()
        fixture.controller.navigate(Screen.Queue)
        runCurrent()
        val row = fixture.controller.state.value.allPending.single()
        assertEquals("incomplete_stream", row.error)
        fixture.controller.cancelPending("job_dead")
        runCurrent()
        // The host ignores stop() for ended jobs; asking it would leave the row listed forever.
        assertTrue(fixture.requests.none { it.path.contains("job_dead") })
        assertTrue(fixture.controller.state.value.allPending.isEmpty())
        val saved = Json { ignoreUnknownKeys = true }.decodeFromString<Preferences>(fixture.bridge.preferences.getValue("preferences.v1"))
        assertEquals(listOf("job_dead"), saved.dismissedJobs)
        fixture.controller.navigate(Screen.Chat); runCurrent()
        fixture.controller.navigate(Screen.Queue); runCurrent()
        assertTrue(fixture.controller.state.value.allPending.isEmpty(), "A refreshed list must keep a cleared job hidden")
    }
}
