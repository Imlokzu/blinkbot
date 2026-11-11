package me.waveio.blinkchat

import org.junit.Assert.*
import org.junit.Test

/**
 * The notes sandbox is the only filesystem the model can reach, so its path
 * policy is proved here without an Android runtime. Every refusal must carry a
 * localization key, never the attempted path.
 */
class WorkspacePathsTest {
    @Test fun keepsOrdinaryRelativeNotePaths() {
        assertEquals("today.md", workspaceRelativePath("today.md"))
        assertEquals("notes/today.md", workspaceRelativePath("notes/today.md"))
        assertEquals("today.md", workspaceRelativePath("  today.md  "))
    }

    @Test fun normalizesRedundantSeparatorsWithoutChangingTheTarget() {
        assertEquals("notes/today.md", workspaceRelativePath("notes//today.md"))
        assertEquals("today.md", workspaceRelativePath("./today.md"))
        assertEquals("notes/today.md", workspaceRelativePath("./notes/./today.md"))
    }

    @Test fun refusesAbsoluteAndDriveQualifiedPaths() {
        for (path in listOf("/etc/passwd", "/data/data/me.waveio.blinkchat/files/chats.json", "C:/tmp/today.md", "c:\\tmp\\today.md")) {
            assertPathRefused(path)
        }
    }

    @Test fun refusesParentTraversalInEverySegment() {
        for (path in listOf("../secrets.md", "..\\secrets.md", "notes/../../secrets.md", "notes/..", "a/b/../../../c.txt")) {
            assertPathRefused(path)
        }
    }

    @Test fun refusesBackslashSeparatorsEvenWithoutTraversal() {
        // A backslash is a legal filename character on Android but hides Windows traversal.
        assertPathRefused("notes\\today.md")
    }

    @Test fun refusesEmptyAndDirectoryOnlyPaths() {
        for (path in listOf("", "   ", "/", "//", ".", "./", "..")) {
            assertPathRefused(path)
        }
    }

    @Test fun allowsOnlyTheTextExtensionsTheEditorCanRepresent() {
        for (extension in listOf("md", "txt", "json", "csv", "html", "js", "css", "kt", "py")) {
            assertEquals("note.$extension", workspaceRelativePath("note.$extension"))
        }
        assertEquals("note.MD", workspaceRelativePath("note.MD"))
        for (path in listOf("note", "note.exe", "note.png", "note.sh", "note.md.exe", "notes.d/readme")) {
            assertPathRefused(path)
        }
    }

    @Test fun refusesControlCharactersThatCouldForgeLogOrTerminalOutput() {
        assertPathRefused("note\u0000.md")
        assertPathRefused("note\n.md")
        assertPathRefused("note\u001b[31m.md")
    }

    @Test fun boundsDepthAndNameLengthSoAToolCannotBuildAnUnboundedTree() {
        val deep = (1 until ChatLimits.WORKSPACE_DEPTH).joinToString("/") { "dir$it" } + "/note.md"
        assertEquals(deep, workspaceRelativePath(deep))
        assertPathRefused("dir/$deep")
        assertEquals("a".repeat(250) + ".md", workspaceRelativePath("a".repeat(250) + ".md"))
        assertPathRefused("a".repeat(ChatLimits.SEGMENT_LENGTH + 1) + ".md")
        assertPathRefused("a".repeat(ChatLimits.PATH_LENGTH + 1) + ".md")
    }

    @Test fun refusalsCarryOnlyTheLocalizationKey() {
        val failure = assertThrows(ProviderFailure::class.java) { workspaceRelativePath("../../etc/passwd") }
        assertEquals("standalone.error.path", failure.key)
        assertEquals("standalone.error.path", failure.message)
    }

    private fun assertPathRefused(path: String) {
        val failure = assertThrows(ProviderFailure::class.java) { workspaceRelativePath(path) }
        assertEquals("standalone.error.path", failure.key)
    }
}
