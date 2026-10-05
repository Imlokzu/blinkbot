package me.waveio.claudebot.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FileLinksTest {
    private val origin = "https://bot.example"

    private fun invalid(value: String, defaultSession: String = "", workspaceBase: String? = null) {
        val failure = assertFailsWith<ApiFailure>(value) {
            fileLinkTarget(value, origin, defaultSession, workspaceBase)
        }
        assertEquals(0, failure.status)
        assertEquals("invalid_workspace_path", failure.code)
    }

    @Test fun ownedFileAndPreviewRoutesUseTheWorkspaceApi() {
        for (prefix in listOf("/file/", "/preview/", "$origin/file/", "$origin/preview/")) {
            assertEquals(FileLinkTarget("project/index.html", "workspace", "chat_1"),
                fileLinkTarget(prefix + "project/index.html", origin, "chat_1"))
        }
        assertEquals(FileLinkTarget("project", "workspace", ""), fileLinkTarget("/preview/project/", origin))
        assertEquals(FileLinkTarget("project/dist", "workspace", ""), fileLinkTarget("/preview/project/dist/#top", origin))
    }

    @Test fun originComparisonIncludesProtocolHostAndEffectivePort() {
        assertEquals(FileLinkTarget("a.txt", "workspace", ""), fileLinkTarget("HTTPS://BOT.EXAMPLE:443/file/a.txt", origin))
        assertEquals(FileLinkTarget("a.txt", "workspace", ""), fileLinkTarget("https://bot.example:8443/file/a.txt", "https://bot.example:8443"))
        assertEquals(FileLinkTarget("a.txt", "workspace", ""), fileLinkTarget("https://[::1]:443/file/a.txt", "https://[::1]"))
        assertNull(fileLinkTarget("https://[::1]:8443/file/a.txt", "https://[::1]"))
        for (url in listOf("http://bot.example/file/a.txt", "https://bot.example:8443/file/a.txt",
            "https://other.example/file/a.txt", "https://localhost/file/a.txt",
            "https://bot.example.evil/file/a.txt", "https://bot.example./file/a.txt")) {
            assertNull(fileLinkTarget(url, origin), url)
        }
    }

    @Test fun validForeignLinksAreNotDecodedOrInterpretedAsWorkspacePaths() {
        assertNull(fileLinkTarget("https://other.example/file/%252e%252e/private?token=keep#fragment", origin))
        assertNull(fileLinkTarget("https://other.example/preview/a?session_id=bad/value", origin))
        assertNull(fileLinkTarget("https://other.example/uploads/a%2fb", origin))
        assertNull(fileLinkTarget("$origin/api/status", origin))
        assertNull(fileLinkTarget("$origin/", origin))
        assertNull(fileLinkTarget("/other/path", origin))
    }

    @Test fun workspaceSegmentsDecodeOnceWithoutFormUrlPlusConversion() {
        assertEquals(FileLinkTarget("notes/my file+1.md", "workspace", ""), fileLinkTarget("/file/notes/my%20file+1.md", origin))
        assertEquals(FileLinkTarget("звіт/дані.txt", "workspace", ""),
            fileLinkTarget("/preview/%D0%B7%D0%B2%D1%96%D1%82/%D0%B4%D0%B0%D0%BD%D1%96.txt", origin))
        assertEquals(FileLinkTarget("notes/😀.txt", "workspace", ""), fileLinkTarget("/file/notes/%F0%9F%98%80.txt", origin))
        assertEquals(FileLinkTarget("notes/звіт.txt", "workspace", ""), fileLinkTarget("/file/notes/звіт.txt", origin))
    }

    @Test fun workspaceQueriesCarryOneValidatedSessionAndOptionalCachebuster() {
        assertEquals(FileLinkTarget("a.md", "workspace", "other_2-A"),
            fileLinkTarget("/file/a.md?session_id=other_2-A&r=17", origin, "default"))
        assertEquals(FileLinkTarget("a.md", "workspace", "chat_1"),
            fileLinkTarget("/file/a.md?r=17", origin, "chat_1"))
        assertEquals(FileLinkTarget("a.md", "workspace", "chat_1"),
            fileLinkTarget("/file/a.md?session_id=chat%5F1", origin))
        assertEquals(FileLinkTarget("a.md", "workspace", ""),
            fileLinkTarget("/file/a.md?session_id=", origin, "default"))
        assertEquals(FileLinkTarget("a.md", "workspace", "a".repeat(64)),
            fileLinkTarget("/file/a.md", origin, "a".repeat(64)))
    }

    @Test fun duplicateUnexpectedAndMalformedQueriesCannotEscapeIntoBrowserFallback() {
        for (query in listOf("session_id=a&session_id=b", "session_id=a&session%5Fid=b",
            "r=1&r=2", "other=1", "session_id=a/b", "session_id=a%2Fb", "session_id=a+b",
            "session_id=a%252Fb", "session_id=" + "a".repeat(65), "session_id=%00",
            "session_id=%FF", "r=%ZZ", "r=%00", "r=1&", "=x")) invalid("/file/a.md?$query")
        invalid("/file/a.md", defaultSession = "not/a/session")
        invalid("/file/a.md", defaultSession = "a".repeat(65))
    }

    @Test fun uploadUrlsKeepTheirOwnerEncodingAndNeverAcquireASession() {
        for (prefix in listOf("", origin)) {
            assertEquals(FileLinkTarget("/uploads/my%20file.pdf", "upload"),
                fileLinkTarget("$prefix/uploads/my%20file.pdf#page=2", origin, "chat_1"))
            assertEquals(FileLinkTarget("/uploads/%D0%B7%D0%B2%D1%96%D1%82.pdf", "upload"),
                fileLinkTarget("$prefix/uploads/%D0%B7%D0%B2%D1%96%D1%82.pdf", origin))
        }
        for (suffix in listOf("", ".", "..", "a/b", "%2e%2e", "a%2Fb", "a%5Cb",
            "a%3Fb", "a%23b", "%252e%252e", "%FF", "%00")) invalid("/uploads/$suffix")
        for (query in listOf("?", "?r=1", "?session_id=chat_1")) invalid("/uploads/a.pdf$query")
    }

    @Test fun canonicalInternalPathsRejectTraversalAndEncodedSeparators() {
        val paths = listOf("../secret", "a/../secret", "a/./b", "a//b", "/etc/passwd",
            "%2e%2e/secret", "a/%2E%2E/secret", "%252e%252e/secret", "a%2Fb", "a%2fb",
            "a%5Cb", "a\\b", "a%3Fb", "a%23b", "a%00b", "a%7Fb", "a%3Ab",
            "a%", "a%2", "a%ZZ", "%FF", "%C0%AF", "%ED%A0%80", "%E2%82", "a\nfile")
        for (route in listOf("file", "preview")) for (path in paths) {
            invalid("/$route/$path")
            invalid("$origin/$route/$path")
        }
        for (url in listOf("/file", "/file/", "/preview", "/preview/", "/preview/project//",
            "/file/a/", "/%66ile/a.txt", "/pre%76iew/a.txt", "/file%2Fproject/a.txt",
            "/preview%2fproject/a.txt", "/%2ffile/a.txt", "/file%5Cproject/a.txt")) invalid(url)
    }

    @Test fun fragmentsAreIgnoredAndDoNotPolluteQueriesOrPaths() {
        assertEquals(FileLinkTarget("a.md", "workspace", "chat_1"),
            fileLinkTarget("/file/a.md?session_id=chat_1#part?session_id=bad/value", origin))
        assertEquals(FileLinkTarget("a.md", "workspace", ""), fileLinkTarget("/file/a.md#%ZZ", origin))
        assertNull(fileLinkTarget("#section", origin, workspaceBase = "notes/readme.md"))
        assertNull(fileLinkTarget("", origin))
    }

    @Test fun relativeMarkdownLinksNeedAnExplicitCanonicalBaseFile() {
        assertNull(fileLinkTarget("next.md", origin))
        assertNull(fileLinkTarget("../next.md", origin))
        assertEquals(FileLinkTarget("notes/next file.md", "workspace", "chat_1"),
            fileLinkTarget("next%20file.md", origin, "chat_1", "notes/readme.md"))
        assertEquals(FileLinkTarget("project/src/index.tsx", "workspace", ""),
            fileLinkTarget("./src/../src/index.tsx", origin, workspaceBase = "project/README.md"))
        assertEquals(FileLinkTarget("sibling/file.md", "workspace", ""),
            fileLinkTarget("../sibling/file.md", origin, workspaceBase = "notes/readme.md"))
        assertEquals(FileLinkTarget("next.md", "workspace", "override"),
            fileLinkTarget("next.md?session_id=override", origin, "chat_1", "README.md"))
        assertEquals(FileLinkTarget("project", "workspace", ""),
            fileLinkTarget("../project/", origin, workspaceBase = "notes/readme.md"))
    }

    @Test fun relativeDotResolutionNeverEscapesWorkspaceOrHidesEncoding() {
        for (link in listOf("../../secret", "a/../../../secret", "%2e%2e/secret", "%252e%252e/secret",
            "./a%2Fb", "a//b", "a%00b", "../", ".//a", "a\\b", "?session_id=x")) {
            invalid(link, workspaceBase = "notes/readme.md")
        }
        invalid("../secret", workspaceBase = "README.md")
        invalid("next.md", workspaceBase = "../readme.md")
        invalid("next.md", workspaceBase = "/readme.md")
        invalid("next.md", workspaceBase = "notes/%2e%2e/readme.md")
        invalid("next.md", workspaceBase = "")
    }

    @Test fun unsafeSchemesCredentialsAndMalformedAuthoritiesAreErrors() {
        val syntheticUserInfo = listOf("fixture-user", "fixture-password").joinToString(":")
        for (url in listOf("javascript:alert(1)", "data:text/html,hello", "file:///file/a.md",
            "ftp://bot.example/file/a.md", "https:/bot.example/file/a.md", "https:///file/a.md",
            "//bot.example/file/a.md", "https://user@bot.example/file/a.md",
            "https://$syntheticUserInfo@foreign.example/file/a.md", "https://bot.example%2Fevil/file/a.md",
            "https://bot.example:0/file/a.md", "https://bot.example:/file/a.md",
            "https://bot.example:+443/file/a.md", "https://bot.example:65536/file/a.md",
            "https://bot.example:443:443/file/a.md", "https://:443/file/a.md",
            "https://bot.example\\evil/file/a.md", " https://bot.example/file/a.md")) invalid(url)
        val failure = assertFailsWith<ApiFailure> { fileLinkTarget("/file/a.md", "http://bot.example") }
        assertEquals("invalid_workspace_path", failure.code)
    }
}
