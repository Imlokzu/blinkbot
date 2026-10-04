package me.waveio.claudebot.data

import kotlin.test.*

class ReplyImagesTest {
    private val origin = "https://bot.example"
    @Test fun markdownImageKeepsCaptionAndBalancedUrlAtItsSourceRange() {
        val text = "Before\n\n![Portrait](https://images.example/photo_(2).jpg?width=1200&key=a)\n\nAfter"
        val image = replyImages(text, origin).single()
        assertEquals("Portrait", image.item.name)
        assertEquals("https://images.example/photo_(2).jpg?width=1200&key=a", image.item.path)
        assertEquals("remote", image.item.source)
        assertTrue(text.substring(image.start, image.end).startsWith("![Portrait]"))
        assertEquals("Before\n\n", text.substring(0, image.start))
        assertEquals("\n\nAfter", text.substring(image.end))
    }
    @Test fun imageReferenceAndExtensionlessImagesAreRecognized() {
        val text = "![Portrait][picture]\n\n[picture]: https://images.example/render?id=one"
        assertEquals("https://images.example/render?id=one", replyImages(text, origin).single().item.path)
        assertEquals("image/*", replyImages("![Art](https://images.example/render?id=2)", origin).single().item.mimeType)
    }
    @Test fun rawImageAndImageLinkRenderButPagesDoNot() {
        val text = "https://images.example/a.jpg\n\n[Second photo](https://images.example/b.png)\n\n[Article](https://news.example/article)"
        assertEquals(listOf("https://images.example/a.jpg", "https://images.example/b.png"), replyImages(text, origin).map { it.item.path })
        assertEquals("https://images.example/a.jpg", replyImages("Here: https://images.example/a.jpg and a caption.", origin).single().item.path)
    }
    @Test fun codeAndIncompleteStreamingSyntaxNeverDownload() {
        val text = "```md\n![Example](https://images.example/a.jpg)\n```\n`![inline](https://images.example/b.png)`"
        assertTrue(replyImages(text, origin).isEmpty())
        assertTrue(replyImages("![Portrait](https://images.example/a.jpg", origin, live = true).isEmpty())
        assertTrue(replyImages("https://images.example/a.jpg", origin, live = true).isEmpty())
        assertEquals(1, replyImages("https://images.example/a.jpg\n", origin, live = true).size)
        assertEquals(1, replyImages("![Portrait](https://images.example/a.jpg)", origin, live = true).size)
    }
    @Test fun ownedUploadsAndWorkspaceLinksHaveDistinctAuthenticatedPaths() {
        assertEquals("upload", replyImageItem("/uploads/picture.jpg", "", origin)?.source)
        assertEquals("/uploads/picture.jpg", replyImageItem("$origin/uploads/picture.jpg", "", origin)?.path)
        assertEquals("workspace", replyImageItem("/preview/session/drawing.png", "", origin)?.source)
        assertEquals("session/drawing.png", replyImageItem("/preview/session/drawing.png", "", origin)?.path)
        assertEquals("remote", replyImageItem("https://elsewhere.example/uploads/picture.jpg", "", origin)?.source)
        assertNull(replyImageItem("$origin/api/setup", "", origin))
        assertNull(replyImageItem("/preview/../secrets.png", "", origin))
        assertNull(replyImageItem("/uploads/%2e%2e%2fsecret.png", "", origin))
    }
    @Test fun unsupportedSchemesCredentialsAndProtocolRelativeLinksNeverLoad() {
        for (url in listOf("file:///etc/a.png", "data:image/png;base64,x", "javascript:alert(1)", "https://" + "me:password" + "@images.example/a.png", "//images.example/a.png")) {
            assertNull(replyImageItem(url, "", origin), url)
        }
    }
    @Test fun clickableImageRendersTheNestedPictureInsteadOfTheLandingPage() {
        val text = "[![Portrait](https://images.example/a.png)](https://news.example/article)"
        val image = replyImages(text, origin).single()
        assertEquals("https://images.example/a.png", image.item.path)
        assertEquals("Portrait", image.item.name)
        assertEquals(text, text.substring(image.start, image.end))
    }
    @Test fun imageTitleAndEscapesDoNotBecomePartOfTheDestination() {
        val image = replyImages("![A](<https://images.example/a.jpg> \"Title\")", origin).single()
        assertEquals("https://images.example/a.jpg", image.item.path)
    }
}
