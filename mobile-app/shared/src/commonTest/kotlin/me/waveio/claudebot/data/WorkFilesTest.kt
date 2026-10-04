package me.waveio.claudebot.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** Generated-file cards require tool evidence, not optimistic assistant claims. */
class WorkFilesTest {
    private fun value(source: String): JsonElement = Json.parseToJsonElement(source)
    private fun step(label: String = "workspace_write", status: String = "done", input: String = """{"path":"session/asked.md"}""", result: String = "{}", id: String = "call") =
        ToolStep(id, label, status = status, input = value(input), result = value(result))

    @Test fun actualMcpResultInAnyTextBlockOverridesRequestedPath() {
        val result = """{"content":[{"type":"image","data":"ignored"},{"type":"text","text":"{\"ok\":true,\"path\":\"sessions/chat_1/delivered.png\"}"}]}"""
        val file = collectWorkFiles(listOf(step(label = "workspace__workspace_write", result = result))).single()
        assertEquals("sessions/chat_1/delivered.png", file.path)
        assertEquals("delivered.png", file.name)
        assertEquals("image/png", file.mimeType)
        assertEquals("image", file.kind)
        assertEquals(1, file.revision)
        assertFalse(file.active)
    }

    @Test fun structuredAndJsonStringResultsAndShownFilesUseActualPath() {
        val cases = listOf(
            """{"structuredContent":{"path":"session/actual.pdf"}}""",
            """{"result":{"shown":"session/actual.pdf"}}""",
            JsonPrimitive("""{"shown":"session/actual.pdf"}""").toString(),
        )
        for (result in cases) {
            assertEquals("session/actual.pdf", collectWorkFiles(listOf(step(label = "workspace_show", result = result))).single().path)
        }
    }

    @Test fun failedInterruptedAndMissingCompletionNeverDeliverFiles() {
        val failed = listOf(
            step(status = "failed"), step(status = "interrupted"), step(status = "error"),
            step(result = """{"ok":false,"path":"session/no.png"}"""),
            step(result = """{"content":[{"type":"text","text":"{\"error\":\"disk full\",\"path\":\"session/no.png\"}"}]}"""),
            step(result = """{"structuredContent":{"success":false}}"""),
            ToolStep("unknown", "workspace_write", input = value("""{"path":"session/unknown.txt"}""")),
        )
        assertTrue(collectWorkFiles(failed).isEmpty())
        assertTrue(collectWorkFiles(listOf(step(label = "workspace_read"))).isEmpty())
    }

    @Test fun activeWritesHaveNoCompletedRevisionAndFailedRewritesPreserveOldFile() {
        val done = step()
        val active = step(status = "active", id = "next")
        val file = collectWorkFiles(listOf(done, active)).single()
        assertTrue(file.active)
        assertEquals(1, file.revision)
        val failed = collectWorkFiles(listOf(done, step(status = "failed", id = "next"))).single()
        assertFalse(failed.active)
        assertEquals(1, failed.revision)
    }

    @Test fun completedDeletesRemoveCardsButActiveAndFailedDeletesDoNot() {
        for (status in listOf("active", "failed")) {
            assertEquals(1, collectWorkFiles(listOf(step(), step(label = "workspace_delete", status = status))).size)
        }
        assertTrue(collectWorkFiles(listOf(step(), step(label = "workspace_delete"))).isEmpty())
    }

    @Test fun nativeAbsoluteWritesRequireTheDisclosedWorkspaceRoot() {
        val write = step(label = "write", input = """{"file_path":"/srv/private-owner/workspace/session/picture.png"}""",
            result = JsonPrimitive("Successfully wrote 18 bytes to /srv/private-owner/workspace/session/picture.png").toString())
        assertTrue(collectWorkFiles(listOf(write)).isEmpty())
        val info = step(label = "workspace_info", result = """{"root":"/srv/private-owner/workspace"}""")
        assertEquals("session/picture.png", collectWorkFiles(listOf(info, write)).single().path)
        assertEquals("/srv/private-owner/workspace", workspaceRootFromSteps(listOf(info)))
        assertTrue(collectWorkFiles(listOf(write), "/different/workspace").isEmpty())
    }

    @Test fun nativeRelativeFilePathAndStructuredOutputsAreSupportedWithoutInventedResults() {
        val write = step(label = "write", input = """{"filePath":"session/picture.png"}""",
            result = """{"path":"session/delivered.png","size":42}""")
        assertEquals("session/delivered.png", collectWorkFiles(listOf(write)).single().path)
        assertTrue(collectWorkFiles(listOf(step(label = "write"))).isEmpty())
        assertTrue(collectWorkFiles(listOf(step(label = "write", result = JsonPrimitive("Error: could not write").toString()))).isEmpty())
    }

    @Test fun arbitraryLinksAndTraversalCannotBecomeWorkspaceCards() {
        for (path in listOf("https://outside.example/a.png", "/etc/passwd", "../a.png", "session/../a.png", "session/%2e%2e/a.png", "session/a.png?token=x", "session\\a.png")) {
            assertFailsWith<ApiFailure> { checkedWorkspacePath(path) }
            assertTrue(collectWorkFiles(listOf(step(result = "{\"path\":${JsonPrimitive(path)}}"))).isEmpty(), path)
        }
    }

    @Test fun oldJsonStringInputsAndSafeDetailFallbackRemainReadable() {
        val input = JsonPrimitive("""{"path":"session/old.md"}""").toString()
        assertEquals("session/old.md", collectWorkFiles(listOf(step(input = input))).single().path)
        assertEquals("notes/old.txt", collectWorkFiles(listOf(ToolStep("old", "workspace_show", detail = "notes/old.txt", status = "done"))).single().path)
    }

    @Test fun editsDeduplicateAndKindsMatchTheWebWorkbench() {
        assertEquals(2, collectWorkFiles(listOf(step(), step(id = "second"))).single().revision)
        assertEquals("drawing", workspaceFileKind("session/diagram.excalidraw.json"))
        assertEquals("mermaid", workspaceFileKind("session/graph.mmd"))
        assertEquals("image", workspaceFileKind("session/PHOTO.AVIF"))
        assertEquals("markdown", workspaceFileKind("session/readme.md"))
    }

    @Test fun binaryExportTypesDoNotPretendToBePlainText() {
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", workspaceMimeType("session/report.docx"))
        assertEquals("application/zip", workspaceMimeType("session/archive.zip"))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workspaceMimeType("session/results.xlsx"))
        assertEquals("application/octet-stream", workspaceMimeType("session/unknown.custom"))
        assertEquals("text/plain", workspaceMimeType("session/source.py"))
    }
}
