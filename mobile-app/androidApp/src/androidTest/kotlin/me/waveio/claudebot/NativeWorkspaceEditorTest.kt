package me.waveio.claudebot

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import me.waveio.claudebot.data.WebPreviewResource
import me.waveio.claudebot.data.WorkspaceEditorDocument
import me.waveio.claudebot.data.WorkspaceEditorSession
import me.waveio.claudebot.ui.NativeWorkspaceEditor
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real bundled editor and native message port; synthetic resources, no host or account. */
class NativeWorkspaceEditorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val shown = mutableStateOf(true)
    private val document = mutableStateOf(WorkspaceEditorDocument("editor-test", "session/notes.md", "markdown", "# Native editor fixture"))
    private val session = WorkspaceEditorSession()
    private val changes = CopyOnWriteArrayList<String>()
    private val paths = CopyOnWriteArrayList<String>()
    private val errors = CopyOnWriteArrayList<String>()

    @After fun cleanup() { compose.runOnIdle { shown.value = false } }

    private fun mount(readOnly: Boolean = false, drawing: Boolean = false) {
        if (drawing) document.value = document.value.copy(path = "session/scene.excalidraw", kind = "drawing",
            content = """{"type":"excalidraw","version":2,"source":"blink","elements":[],"appState":{},"files":{}}""")
        document.value = document.value.copy(readOnly = readOnly)
        compose.setContent {
            if (shown.value) NativeWorkspaceEditor(document.value, session,
                onChange = { content -> changes += content; document.value = document.value.copy(content = content) },
                onAction = {}, onError = { errors += it },
                loadResource = { path ->
                    paths += path
                    when (path) {
                        "session/shape.svg" -> WebPreviewResource("<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'/>".toByteArray(), "image/svg+xml")
                        else -> null
                    }
                }, modifier = Modifier.fillMaxSize())
        }
        val ready = if (drawing) "document.querySelector('[data-testid=toolbar-rectangle]')"
            else "document.body.textContent.includes('Native editor fixture')"
        compose.waitUntil(20_000) { javascript("Boolean(window.BlinkWorkspace && $ready)") == "true" }
        assertTrue("Opening a document must not autosave a normalized replacement", changes.isEmpty())
        assertTrue("Bundled editor must initialize without native errors: $errors", errors.isEmpty())
        assertVisibleHitTarget(if (drawing) "[data-testid=toolbar-rectangle]" else ".view-tabs button")
    }

    private fun assertVisibleHitTarget(selector: String) {
        val encoded = javascript("""JSON.stringify((() => {
            const element = document.querySelector(${JSONObject.quote(selector)});
            if (!element) return {found:false};
            const target = element.closest('label') || element;
            const rect = target.getBoundingClientRect();
            const hit = document.elementFromPoint(rect.x + rect.width/2, rect.y + rect.height/2);
            const ancestors = [];
            for (let node = target; node; node = node.parentElement) {
                const bounds = node.getBoundingClientRect(), style = getComputedStyle(node);
                ancestors.push({tag:node.tagName,id:node.id,height:bounds.height,width:bounds.width,
                    cssHeight:style.height,overflow:style.overflow,visibility:style.visibility});
            }
            const roots = [document.documentElement,document.body,document.getElementById('root')];
            return {found:true, rootsSized:roots.every(node => node && node.getBoundingClientRect().height > 100 && node.getBoundingClientRect().width > 100),
                hitTarget:target === hit || target.contains(hit), hitTag:hit?.tagName,
                viewport:{width:innerWidth,height:innerHeight}, ancestors};
        })())""".trimIndent())
        val diagnostics = JSONObject(JSONTokener(encoded).nextValue() as String)
        assertTrue("Editor DOM roots must occupy the native viewport: $diagnostics", diagnostics.optBoolean("rootsSized"))
        assertTrue("Visible editor controls must receive real DOM hit tests: $diagnostics", diagnostics.optBoolean("hitTarget"))
    }

    private fun findWebView(node: View): WebView? {
        if (node is WebView) return node
        if (node is ViewGroup) for (index in 0 until node.childCount) findWebView(node.getChildAt(index))?.let { return it }
        return null
    }

    private fun javascript(source: String): String {
        val result = AtomicReference<String>()
        val complete = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val web = requireNotNull(findWebView(compose.activity.window.decorView))
            web.evaluateJavascript(source) { result.set(it); complete.countDown() }
        }
        assertTrue("Native editor JavaScript must respond", complete.await(3, TimeUnit.SECONDS))
        return result.get()
    }

    private fun send(type: String, id: String = "editor-test", sequence: Long = 1, content: String = "changed") {
        val message = JSONObject().put("type", type).put("id", id).put("sequence", sequence).put("content", content)
        javascript("window.BlinkNative.postMessage(${JSONObject.quote(message.toString())})")
    }

    @Test fun richTextChangesAndImmediateCloseFlushReachNativeWithoutReopeningContent() {
        mount()
        javascript("""(() => {
            const editable = document.querySelector('[contenteditable="true"]');
            editable.focus();
            const range = document.createRange(); range.selectNodeContents(editable); range.collapse(false);
            const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);
            document.execCommand('insertText', false, ' typed on the phone');
        })()""".trimIndent())
        val flushed = AtomicReference<Boolean?>()
        compose.runOnIdle { session.flush { flushed.set(it) } }
        compose.waitUntil(5_000) { flushed.get() != null }
        assertEquals(true, flushed.get())
        assertTrue("Last keystrokes must precede flush acknowledgement", changes.lastOrNull()?.contains("typed on the phone") == true)
        val selectionBefore = javascript("getSelection().anchorOffset")
        compose.runOnIdle { document.value = document.value.copy(saveState = "saved", theme = "dark") }
        compose.waitForIdle()
        assertEquals(selectionBefore, javascript("getSelection().anchorOffset"))
        assertTrue(javascript("document.body.textContent.includes('typed on the phone')") == "true")
    }

    @Test fun drawingToolbarHasVisibleAncestorsAndReceivesHitTests() {
        mount(drawing = true)
        assertEquals("true", javascript("Boolean(document.querySelector('canvas')?.getBoundingClientRect().height > 100)"))
    }

    @Test fun wrongDocumentReadonlyAndChildFrameMessagesCannotSave() {
        mount(readOnly = true)
        send("change", content = "forbidden read-only edit")
        send("change", id = "other-document", sequence = 2)
        javascript("""(() => {
            const frame = document.createElement('iframe'); document.body.append(frame);
            try { frame.contentWindow.BlinkNative.postMessage(JSON.stringify({type:'change',id:'editor-test',sequence:3,content:'child frame'})); } catch (_) {}
        })()""".trimIndent())
        compose.waitForIdle()
        assertTrue(changes.isEmpty())
        compose.runOnIdle { document.value = document.value.copy(readOnly = false) }
        compose.waitForIdle()
        send("change", sequence = 4, content = "allowed main frame")
        compose.waitUntil(3_000) { changes.isNotEmpty() }
        assertEquals(listOf("allowed main frame"), changes.toList())
        send("change", sequence = 4, content = "duplicate")
        compose.waitForIdle()
        assertEquals(1, changes.size)
    }

    @Test fun resourcesStayManifestOrImageBoundAndForeignNavigationIsBlocked() {
        mount()
        javascript("""(async () => {
            window.fixtureStatuses = [];
            for (const path of ['/workspace/session/shape.svg','/workspace/session/site.html','/workspace/session/script.js','/missing.js','/favicon.ico']) {
                try { window.fixtureStatuses.push((await fetch(path)).status); } catch (_) { window.fixtureStatuses.push(0); }
            }
            window.fixtureRequestsDone = true;
        })()""".trimIndent())
        compose.waitUntil(10_000) { javascript("Boolean(window.fixtureRequestsDone)") == "true" }
        assertEquals("[200,403,403,404,404]", javascript("JSON.stringify(window.fixtureStatuses)").removeSurrounding("\""))
        assertEquals(listOf("session/shape.svg"), paths.toList())
        assertTrue("Denied unknown assets must not fail an initialized editor: $errors", errors.isEmpty())
        val origin = javascript("location.origin")
        javascript("location.href='https://outside.invalid/credential-check'")
        compose.waitForIdle()
        assertEquals(origin, javascript("location.origin"))
        assertEquals("null", javascript("window.open('https://outside.invalid/')"))
    }
}
