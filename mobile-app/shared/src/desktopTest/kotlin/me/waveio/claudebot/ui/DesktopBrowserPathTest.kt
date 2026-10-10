package me.waveio.claudebot.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopBrowserPathTest {
    private val previewOrigin = "http://preview-fixture.localhost:43111"
    private val editorOrigin = "http://editor-fixture.localhost:43112"

    @Test
    fun previewAcceptsOnlyItsExactLoopbackOriginAndSafeResourcePaths() {
        assertEquals("/index.html", desktopBrowserPath("$previewOrigin/index.html", previewOrigin, false))
        assertEquals("/assets/main.js", desktopBrowserPath("$previewOrigin/assets/main.js", previewOrigin, false))

        listOf(
            "https://preview-fixture.localhost:43111/index.html",
            "http://attacker.localhost:43111/index.html",
            "http://user@preview-fixture.localhost:43111/index.html",
            "http://preview-fixture.localhost:43112/index.html",
            "http://preview-fixture.localhost/index.html",
            "$previewOrigin/%2e%2e/private.js",
            "$previewOrigin/assets%2fsecret.js",
            "$previewOrigin/%zz",
        ).forEach { url -> assertNull(desktopBrowserPath(url, previewOrigin, false), url) }
    }

    @Test
    fun trustedEditorAllowsOnlyTheDocumentAndSafeWorkspaceResources() {
        assertEquals("/index.html", desktopBrowserPath("$editorOrigin/index.html", editorOrigin, true))
        assertEquals("/workspace/session/note.md", desktopBrowserPath("$editorOrigin/workspace/session/note.md", editorOrigin, true))
        assertEquals("/assets/editor.js", desktopBrowserPath("$editorOrigin/assets/editor.js", editorOrigin, true))

        listOf(
            "$editorOrigin/",
            "$editorOrigin/workspace/../../outside.txt",
            "$editorOrigin/workspace%2fsecret.txt",
            "https://editor-fixture.localhost:43112/index.html",
            "http://editor-fixture.localhost:43111/index.html",
        ).forEach { url -> assertNull(desktopBrowserPath(url, editorOrigin, true), url) }
    }
}
