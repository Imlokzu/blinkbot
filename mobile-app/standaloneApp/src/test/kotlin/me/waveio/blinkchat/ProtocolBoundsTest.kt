package me.waveio.blinkchat

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProtocolBoundsTest {
    @Test fun sseAcceptsEmptyKeepalivesAndBareCarriageReturns() = runTest {
        val provider=ProviderClient(HttpClient(MockEngine {
            respond("data:\r\r: keepalive\r\rdata: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}\r\rdata: [DONE]\r\r",headers=headersOf(HttpHeaders.ContentType,"text/event-stream"))
        }))
        try { assertEquals("Hello",provider.chat(ProviderSettings(),"test-key",listOf(ApiMessage("user","Hello"))).text) }
        finally { provider.close() }
    }

    @Test fun longSseLinesAreRefusedBeforeAccumulatingTheWholeBody() = runTest {
        val provider=ProviderClient(HttpClient(MockEngine { respond("data: "+"x".repeat(1024*1024+1),headers=headersOf(HttpHeaders.ContentType,"text/event-stream")) }))
        try {
            try { provider.chat(ProviderSettings(),"test-key",listOf(ApiMessage("user","Hello"))); fail("Oversized line accepted") }
            catch (error:ProviderFailure) { assertEquals("standalone.error.response",error.key) }
        } finally { provider.close() }
    }

    @Test fun deeplyNestedJsonIsRejectedButBracketsInsideStringsAreHarmless() {
        validateJsonNesting("{\"text\":\""+"[".repeat(1000)+"\"}")
        try { validateJsonNesting("[".repeat(65)+"]".repeat(65)); fail("Deep nesting accepted") }
        catch (error:ProviderFailure) { assertEquals("standalone.error.response",error.key) }
    }
}
