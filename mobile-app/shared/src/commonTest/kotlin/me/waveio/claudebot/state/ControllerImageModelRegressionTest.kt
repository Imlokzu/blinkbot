@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.waveio.claudebot.platform.PickedFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** An automatic image route and an explicit user selection are different intents. */
class ControllerImageModelRegressionTest {
    private val fixtures = mutableListOf<ControllerTestFixture>()

    private fun TestScope.fixture(bridge: FakePlatformBridge = FakePlatformBridge()) =
        ControllerTestFixture(StandardTestDispatcher(testScheduler), bridge).also { fixtures += it }

    private fun controllerTest(block: suspend TestScope.() -> Unit): TestResult = runTest(timeout = 10.seconds) {
        try { block() } finally { fixtures.forEach { it.close() }; fixtures.clear(); runCurrent() }
    }

    private fun configureSend(fixture: ControllerTestFixture) {
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/brain/models" -> jsonResponse(catalog)
            request.url.encodedPath == "/api/chat/upload" -> jsonResponse("""{"url":"/uploads/image.png","name":"image.png","type":"image/png","size":3}""")
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post ->
                jsonResponse("""{"id":"submitted_job","session_id":"chat_1","state":"queued"}""")
            else -> null
        } }
    }

    private fun TestScope.open(fixture: ControllerTestFixture) {
        runCurrent(); fixture.controller.openChat("chat_1"); runCurrent()
    }

    private fun TestScope.pickImage(fixture: ControllerTestFixture) {
        fixture.controller.pickFile("photo")
        fixture.bridge.pickedResult?.invoke(PickedFile("image.png", "image/png", byteArrayOf(1, 2, 3)))
        runCurrent()
    }

    private fun submitted(fixture: ControllerTestFixture) = fixture.requests.last { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post }

    @Test fun inheritedImageSendLetsTheHostChooseItsConfiguredImageModel() = controllerTest {
        val fixture = fixture()
        configureSend(fixture); open(fixture); pickImage(fixture)
        assertEquals("regolo/text", fixture.controller.state.value.selectedModel)
        fixture.controller.send(); runCurrent()
        assertEquals("", submitted(fixture).body?.get("model")?.jsonPrimitive?.content)
        assertEquals("", fixture.controller.state.value.preferences.chatModels["chat_1"])
        assertEquals("regolo/text", fixture.controller.state.value.selectedModel, "Sending must not silently rewrite the user's visible selection")
    }

    @Test fun anExplicitImageSelectionIsSentUnchangedEvenWhenItIsNotVisionCapable() = controllerTest {
        val fixture = fixture()
        configureSend(fixture); open(fixture); pickImage(fixture)
        fixture.controller.selectModel("regolo/text")
        fixture.controller.send(); runCurrent()
        assertEquals("regolo/text", submitted(fixture).body?.get("model")?.jsonPrimitive?.content)
        assertEquals("regolo/text", fixture.controller.state.value.preferences.chatModels["chat_1"])
    }

    @Test fun fixedAndLastModelPreferencesCountAsExplicitImageChoices() = controllerTest {
        for (preferences in listOf(
            Preferences(defaultModelMode = "fixed", defaultModel = "regolo/text"),
            Preferences(defaultModelMode = "fixed", defaultModel = "regolo/text", chatModels = mapOf("chat_1" to "")),
            Preferences(defaultModelMode = "last", lastModel = "regolo/text"),
        )) {
            val bridge = FakePlatformBridge()
            bridge.preferences["preferences.v1"] = Json.encodeToString(preferences)
            val fixture = fixture(bridge)
            configureSend(fixture); open(fixture); pickImage(fixture)
            fixture.controller.send(); runCurrent()
            assertEquals("regolo/text", submitted(fixture).body?.get("model")?.jsonPrimitive?.content)
        }
    }

    @Test fun ordinaryTextKeepsTheResolvedModelWithoutMakingTheChatExplicit() = controllerTest {
        val fixture = fixture()
        configureSend(fixture); open(fixture)
        fixture.controller.draft("Text only")
        fixture.controller.send(); runCurrent()
        assertEquals("regolo/text", submitted(fixture).body?.get("model")?.jsonPrimitive?.content)
        assertEquals("", fixture.controller.state.value.preferences.chatModels["chat_1"])
        pickImage(fixture)
        fixture.controller.send(); runCurrent()
        assertEquals("", submitted(fixture).body?.get("model")?.jsonPrimitive?.content, "A previous text send must not turn an inherited default into an explicit image choice")
    }

    @Test fun inheritedImageIntentSurvivesControllerRestartAndReopeningTheChat() = controllerTest {
        val bridge = FakePlatformBridge()
        val first = fixture(bridge)
        configureSend(first); open(first); pickImage(first)
        first.controller.send(); runCurrent()
        first.close(); runCurrent()
        val second = fixture(bridge)
        configureSend(second); open(second); pickImage(second)
        second.controller.send(); runCurrent()
        assertEquals("regolo/text", second.controller.state.value.selectedModel)
        assertEquals("", submitted(second).body?.get("model")?.jsonPrimitive?.content)
    }

    @Test fun modelCapabilityMetadataPreservesTrueFalseAndUnknown() = controllerTest {
        val fixture = fixture()
        configureSend(fixture); runCurrent()
        val models = fixture.controller.state.value.models.associateBy { it.id }
        assertEquals(true, models.getValue("regolo/vision").vision)
        assertEquals(false, models.getValue("regolo/text").vision)
        assertNull(models.getValue("regolo/unknown").vision, "Missing metadata is not proof that images are unsupported")
    }

    private fun configureImageFork(fixture: ControllerTestFixture) {
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/brain/models" -> jsonResponse(catalog)
            "/api/sessions/chat_1" -> jsonResponse(imageForkHistory)
            "/api/mobile/sessions/chat_1/fork" -> jsonResponse("""{"id":"fork_job","session_id":"fork_chat","state":"queued"}""")
            else -> null
        } }
    }

    @Test fun editingAnImageMessageUsesItsOriginalManifestForInheritedModelIntent() = controllerTest {
        for (selection in listOf(null, "regolo/text", "regolo/vision")) {
            val fixture = fixture()
            configureImageFork(fixture); open(fixture)
            selection?.let { fixture.controller.selectModel(it) }
            fixture.controller.draft("Original unsent draft")
            fixture.controller.editMessage("image_user")
            assertTrue(fixture.controller.state.value.attachments.isEmpty(), "Inherited files must not duplicate the history attachment in the composer")
            assertEquals("/uploads/image.png", fixture.controller.state.value.messages.first().attachments.single().path)
            fixture.controller.cancelEdit()
            assertEquals("Original unsent draft", fixture.controller.state.value.draft)
            fixture.controller.editMessage("image_user")
            fixture.controller.draft("")
            fixture.controller.send(); runCurrent()
            val request = fixture.requests.single { it.path.endsWith("/fork") }
            assertEquals(selection.orEmpty(), request.body?.get("model")?.jsonPrimitive?.content)
            assertEquals("edit", request.body?.get("action")?.jsonPrimitive?.content)
            assertEquals("image_user", request.body?.get("message_id")?.jsonPrimitive?.content)
            assertEquals("", request.body?.get("message")?.jsonPrimitive?.content, "An inherited image permits an empty edited caption")
            assertNull(request.body?.get("attachments"), "The host retains the source manifest; do not submit duplicate attachments")
            assertEquals("Bearer old-device-token", request.bearer)
            assertNull(fixture.controller.state.value.error)
        }
    }

    @Test fun regeneratingAnImageAnswerUsesItsOwnPrecedingHumanMessageForModelIntent() = controllerTest {
        for (selection in listOf(null, "regolo/text", "regolo/vision")) {
            val fixture = fixture()
            configureImageFork(fixture); open(fixture)
            selection?.let { fixture.controller.selectModel(it) }
            fixture.controller.draft("Unsent draft stays")
            fixture.controller.regenerate("image_answer"); runCurrent()
            val request = fixture.requests.single { it.path.endsWith("/fork") }
            assertEquals(selection.orEmpty(), request.body?.get("model")?.jsonPrimitive?.content)
            assertEquals("regenerate", request.body?.get("action")?.jsonPrimitive?.content)
            assertEquals("image_answer", request.body?.get("message_id")?.jsonPrimitive?.content)
            assertNull(request.body?.get("message"))
            assertNull(request.body?.get("attachments"))
            assertEquals("Unsent draft stays", fixture.controller.state.value.draft)
            assertEquals("Bearer old-device-token", request.bearer)
            assertNull(fixture.controller.state.value.error)
        }
    }

    @Test fun selectingAVisionModelThenManuallyRetryingTheFailedImagePreservesItsManifest() = controllerTest {
        retryWithNewSelection("mobile_image_model_unavailable", "regolo/vision")
    }

    @Test fun changingModelsDoesNotRewriteAnUnrelatedFailedRequestsModel() = controllerTest {
        retryWithNewSelection("mobile_turn_failed", "regolo/text")
    }

    private fun configureFailedJob(fixture: ControllerTestFixture, code: String) {
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/brain/models" -> jsonResponse(catalog)
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Get -> jsonResponse(failedJob(code))
            request.url.encodedPath == "/api/mobile/sessions/chat_1/resume" -> jsonResponse("""{"ok":true}""")
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post ->
                jsonResponse("""{"id":"retried_job","session_id":"chat_1","state":"queued"}""")
            else -> null
        } }
    }

    private fun TestScope.retryWithNewSelection(code: String, expectedModel: String) {
        val fixture = fixture()
        configureFailedJob(fixture, code); open(fixture)
        fixture.controller.navigate(Screen.Queue); runCurrent()
        fixture.controller.selectModel("regolo/vision")
        assertFalse(fixture.requests.any { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post }, "Selecting a model must never resend automatically")
        fixture.controller.retryPending("failed_image"); runCurrent()
        val request = submitted(fixture)
        assertEquals(expectedModel, request.body?.get("model")?.jsonPrimitive?.content)
        assertEquals("chat_1", request.body?.get("session_id")?.jsonPrimitive?.content)
        assertEquals("Describe this image", request.body?.get("message")?.jsonPrimitive?.content)
        val attachment = request.body?.get("attachments")?.jsonArray?.single()?.jsonObject
        assertEquals("/uploads/image.png", attachment?.get("url")?.jsonPrimitive?.content)
        assertEquals("image.png", attachment?.get("name")?.jsonPrimitive?.content)
        assertEquals("image/png", attachment?.get("type")?.jsonPrimitive?.content)
        assertEquals("3", attachment?.get("size")?.jsonPrimitive?.content)
        assertEquals("old.example", request.host)
        assertEquals("Bearer old-device-token", request.bearer)
        assertFalse(fixture.requests.any { it.path == "/api/chat/upload" }, "Retry reuses the uploaded manifest")
    }

    @Test fun delayedFailedImageLookupCannotResubmitUnderANewPairedOwner() = controllerTest {
        val fixture = fixture()
        configureFailedJob(fixture, "mobile_image_model_unavailable"); open(fixture)
        fixture.controller.navigate(Screen.Queue); runCurrent()
        fixture.controller.selectModel("regolo/vision")
        val lookup = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.url.host == "old.example") {
            lookup.await(); jsonResponse(failedJob("mobile_image_model_unavailable"))
        } else null }
        fixture.controller.retryPending("failed_image"); runCurrent()
        fixture.controller.disconnect(); fixture.pairNewOwner(); runCurrent()
        lookup.complete(Unit); runCurrent()
        assertFalse(fixture.requests.any { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post })
        assertFalse(fixture.requests.any { it.path.endsWith("/resume") })
        assertNull(fixture.controller.state.value.error)
    }

    @Test fun imageFailureFromSubmitRestoresItsManifestAndOpensTheModelPicker() = controllerTest {
        val fixture = fixture()
        configureSend(fixture); open(fixture); pickImage(fixture)
        fixture.controller.draft("Describe this image")
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post)
            jsonResponse("""{"detail":{"code":"mobile_image_model_unavailable"}}""", HttpStatusCode.BadRequest) else null }
        fixture.controller.send(); runCurrent()
        val state = fixture.controller.state.value
        assertEquals("error.imageModel", state.error)
        assertTrue(state.modelPickerOpen)
        assertEquals("Describe this image", state.draft)
        assertEquals("/uploads/image.png", state.attachments.single().path)
    }

    private fun failedJob(code: String) = """{"messages":[{"id":"failed_image","session_id":"chat_1","client_id":"previous","state":"failed","error":"$code","message":"Describe this image","model":"regolo/text","reasoning_effort":"none","conversation_paused":true,"attachments":[{"url":"/uploads/image.png","name":"image.png","type":"image/png","size":3}]}]}"""

    private companion object {
        const val imageForkHistory = """{"messages":[{"id":"image_user","role":"user","content":"Original image caption","attachments":[{"url":"/uploads/image.png","name":"image.png","type":"image/png","size":3}]},{"id":"image_answer","role":"assistant","content":"Image answer"},{"id":"text_user","role":"user","content":"Later text request"},{"id":"text_answer","role":"assistant","content":"Later text answer"}]}"""
        const val catalog = """{"models":[{"id":"regolo/text","label":"Text","provider":"regolo","vision":false},{"id":"regolo/vision","label":"Vision","provider":"regolo","vision":true},{"id":"regolo/unknown","label":"Unknown","provider":"regolo"}],"selected":"regolo/text","image_model":"regolo/vision"}"""
    }
}
