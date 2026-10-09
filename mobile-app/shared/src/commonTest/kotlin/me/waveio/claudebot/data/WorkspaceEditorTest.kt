package me.waveio.claudebot.data

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkspaceEditorTest {
    private val document = WorkspaceEditorDocument("editor-7", "session/notes.md", "markdown", "# Original")

    private fun event(type: String, id: String = document.id, sequence: Long = 1, vararg fields: Pair<String, String>): String =
        buildJsonObject {
            put("type", type); put("id", id); put("sequence", sequence)
            fields.forEach { (name, value) -> put(name, value) }
        }.toString()

    @Test fun decodedResourcePathsRejectTraversalURLsAndEncodedSeparators() {
        assertEquals("/workspace/session/my drawing.excalidraw", workspaceEditorResourcePath("/workspace/session/my%20drawing.excalidraw"))
        for (path in listOf("/workspace/../secret", "/workspace/%2e%2e/secret", "/workspace/session%2ffile", "/workspace/%252e%252e/file",
            "/workspace/a%5cb", "https://host.invalid/file", "//host.invalid/file", "/workspace/a?b", "/workspace/a%00b")) {
            assertNull(workspaceEditorResourcePath(path), path)
        }
    }

    @Test fun workspaceResponsesCannotSupplyTrustedScriptsStylesOrHTML() {
        for (mime in listOf("text/html", "text/javascript", "application/javascript", "text/css", "application/pdf")) {
            assertNull(guardedWorkspaceEditorResource("scene.excalidraw", WebPreviewResource(byteArrayOf(1), mime)))
        }
        assertNotNull(guardedWorkspaceEditorResource("scene.excalidraw.json", WebPreviewResource("{}".encodeToByteArray(), "application/json")))
        assertNotNull(guardedWorkspaceEditorResource("image.svg", WebPreviewResource("<svg/>".encodeToByteArray(), "image/svg+xml; charset=utf-8")))
        assertNull(guardedWorkspaceEditorResource("config.json", WebPreviewResource("{}".encodeToByteArray(), "application/json")))
        assertNull(guardedWorkspaceEditorResource("photo.png", WebPreviewResource(byteArrayOf(1), "image/png", 302)))
    }

    @Test fun changesRequireCurrentDocumentStrictSequenceAndUTF8Bounds() {
        val accepted = parseWorkspaceEditorEvent(event("change", fields = arrayOf("content" to "new")), document, 0)
        assertEquals(WorkspaceEditorEvent.Change(1, "new"), accepted)
        assertNull(parseWorkspaceEditorEvent(event("change", id = "other", fields = arrayOf("content" to "new")), document, 0))
        assertNull(parseWorkspaceEditorEvent(event("change", fields = arrayOf("content" to "new")), document, 1))
        assertNull(parseWorkspaceEditorEvent(event("change", sequence = 9_007_199_254_740_992, fields = arrayOf("content" to "new")), document, 0))
        assertNull(parseWorkspaceEditorEvent(event("change", fields = arrayOf("content" to "я".repeat(1_000_001))), document, 0))
        assertNull(parseWorkspaceEditorEvent("""{"type":"change","id":"editor-7","sequence":"2","content":"no"}""", document, 0))
        assertNull(parseWorkspaceEditorEvent("[]", document, 0))
    }

    @Test fun readonlyNeverEmitsEditsButCanOpenFilesAndConvertASiblingDiagram() {
        val readOnly = document.copy(readOnly = true)
        assertNull(parseWorkspaceEditorEvent(event("change", fields = arrayOf("content" to "new")), readOnly, 0))
        assertNull(parseWorkspaceEditorEvent(event("action", fields = arrayOf("action" to "createDrawing")), readOnly, 0))
        assertNotNull(parseWorkspaceEditorEvent(event("action", fields = arrayOf("action" to "openWorkspace", "path" to "session/scene.excalidraw")), readOnly, 0))
        assertNotNull(parseWorkspaceEditorEvent(event("action", fields = arrayOf("action" to "convertMermaid", "content" to "{}")), readOnly.copy(kind = "mermaid"), 0))
    }

    @Test fun actionsCannotChooseArbitraryWriteDestinationsOrUnsafeExternalURLs() {
        for (path in listOf("../outside", "/absolute", "https://foreign.invalid/secret", "session/%2e%2e/secret")) {
            assertNull(parseWorkspaceEditorEvent(event("action", fields = arrayOf("action" to "openWorkspace", "path" to path)), document, 0))
        }
        for (url in listOf("javascript:alert(1)", "file:///private", "http://example.com", "https://user:password@example.com/")) {
            assertNull(parseWorkspaceEditorEvent(event("action", fields = arrayOf("action" to "openExternal", "path" to url)), document, 0))
        }
        assertNotNull(parseWorkspaceEditorEvent(event("action", fields = arrayOf("action" to "openExternal", "path" to "https://example.com/page")), document, 0))
        assertNull(parseWorkspaceEditorEvent(event("action", fields = arrayOf("action" to "createDrawing", "path" to "replace.md")), document, 0))
        assertNull(parseWorkspaceEditorEvent(event("action", fields = arrayOf("action" to "writeFile", "content" to "bad")), document, 0))
    }

    @Test fun drawingExportOnlyCarriesBoundedPNGDataFromDrawingOrMermaid() {
        val png = "data:image/png;base64,iVBORw0KGgo="
        val export = event("action", fields = arrayOf("action" to "exportDrawing", "content" to png))
        for (kind in listOf("drawing", "mermaid")) {
            for (readOnly in listOf(false, true)) {
                assertNotNull(parseWorkspaceEditorEvent(export, document.copy(kind = kind, readOnly = readOnly), 0))
            }
        }
        assertNull(parseWorkspaceEditorEvent(export, document, 0))
        val drawing = document.copy(kind = "drawing", readOnly = true)
        assertNull(parseWorkspaceEditorEvent(event("change", fields = arrayOf("content" to "changed source")), drawing, 0))
        for (content in listOf("data:image/jpeg;base64,iVBORw0KGgo=", "data:image/png;base64,", "data:image/png;base64,not!base64",
            "https://example.com/drawing.png", "data:image/png;base64," + "A".repeat(WorkspaceEditorMaxContentBytes))) {
            assertNull(parseWorkspaceEditorEvent(event("action", fields = arrayOf("action" to "exportDrawing", "content" to content)), drawing, 0))
        }
        assertNull(parseWorkspaceEditorEvent(event("action", fields = arrayOf("action" to "exportDrawing", "content" to png, "path" to "overwrite.png")), drawing, 0))
    }

    @Test fun readyAndHostUpdatesNeverEchoContentBackIntoACaretReset() = runTest {
        val scripts = mutableListOf<String>()
        val changes = mutableListOf<String>()
        val bridge = WorkspaceEditorBridge(document, backgroundScope, { script, done -> scripts += script; done(true) }, changes::add, {}, {})
        bridge.receive("""{"type":"ready"}""")
        bridge.receive("""{"type":"ready"}""")
        assertEquals(1, scripts.size)
        assertContains(scripts.single(), "openDocument")
        assertContains(scripts.single(), "\"readOnly\":false")
        assertContains(scripts.single(), "\"theme\":\"light\"")
        assertContains(scripts.single(), "\"language\":\"en\"")
        assertContains(scripts.single(), "\"saveState\":\"saved\"")
        bridge.receive(event("change", fields = arrayOf("content" to "changed")))
        bridge.update(document.copy(content = "changed"))
        assertEquals(1, scripts.size)
        bridge.update(document.copy(content = "changed", saveState = "saving", theme = "dark"))
        assertEquals(2, scripts.size)
        assertContains(scripts.last(), "updateHost")
        assertFalse(scripts.last().contains("\"content\""))
        bridge.update(document.copy(content = "changed"))
        assertContains(scripts.last(), "\"theme\":\"light\"")
        assertContains(scripts.last(), "\"saveState\":\"saved\"")
        assertEquals(listOf("changed"), changes)
        bridge.close()
    }

    @Test fun flushRequiresMatchingAckAfterTheLastChangeAndFailsOnTimeout() = runTest {
        val changes = mutableListOf<String>()
        val results = mutableListOf<Boolean>()
        val bridge = WorkspaceEditorBridge(document, backgroundScope, { _, done -> done(true) }, changes::add, {}, {})
        bridge.flush(results::add)
        assertEquals(listOf(false), results)
        bridge.receive("""{"type":"ready"}""")
        bridge.flush { results += it; assertEquals(listOf("last keystroke"), changes) }
        bridge.receive(event("flushed", id = "stale", fields = arrayOf("token" to "flush-1")))
        assertEquals(1, results.size)
        bridge.receive(event("change", fields = arrayOf("content" to "last keystroke")))
        bridge.receive(event("flushed", fields = arrayOf("token" to "flush-1")))
        assertEquals(listOf(false, true), results)
        bridge.flush(results::add)
        runCurrent(); advanceTimeBy(WorkspaceEditorFlushTimeoutMillis + 1); runCurrent()
        assertEquals(listOf(false, true, false), results)
        bridge.close()
    }

    @Test fun disposedEditorRejectsLateEventsAndCompletesWaitersExactlyOnce() = runTest {
        val results = mutableListOf<Boolean>()
        val changes = mutableListOf<String>()
        val bridge = WorkspaceEditorBridge(document, backgroundScope, { _, done -> done(true) }, changes::add, {}, {})
        bridge.receive("""{"type":"ready"}""")
        bridge.flush(results::add)
        bridge.close(); bridge.close()
        bridge.receive(event("change", fields = arrayOf("content" to "late")))
        bridge.receive(event("flushed", fields = arrayOf("token" to "flush-1")))
        assertEquals(listOf(false), results)
        assertTrue(changes.isEmpty())
    }

    @Test fun createDrawingResolutionIsBoundToTheOutstandingDocumentAction() = runTest {
        val scripts = mutableListOf<String>()
        val bridge = WorkspaceEditorBridge(document, backgroundScope, { script, done -> scripts += script; done(true) }, {}, {}, {})
        bridge.receive("""{"type":"ready"}""")
        bridge.resolveAction(1, "session/not-created.excalidraw", null)
        assertEquals(1, scripts.size)
        bridge.receive(event("action", fields = arrayOf("action" to "createDrawing")))
        bridge.resolveAction(1, "session/created.excalidraw", null)
        assertContains(scripts.last(), "resolveAction")
        assertContains(scripts.last(), "session/created.excalidraw")
        bridge.resolveAction(1, "session/duplicate.excalidraw", null)
        assertEquals(2, scripts.size)
        bridge.close()
    }

    @Test fun oldViewDisposalCannotDetachItsReplacementSession() {
        val session = WorkspaceEditorSession()
        val old = Any(); val current = Any()
        session.attach(old, { it(false) }, { _, _, _ -> })
        session.attach(current, { it(true) }, { _, _, _ -> })
        session.detach(old)
        var result: Boolean? = null
        session.flush { result = it }
        assertEquals(true, result)
        session.detach(current)
        session.flush { result = it }
        assertEquals(false, result)
    }
}
