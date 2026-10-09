package me.waveio.claudebot

import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.waveio.claudebot.data.WebPreviewResource
import me.waveio.claudebot.ui.NativeWebAppPreview
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real native WebView, local callback fixtures only; no server, account or JS bridge. */
class WebAppPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val revision = mutableLongStateOf(1L)
    private val shown = mutableStateOf(true)
    private val paths = CopyOnWriteArrayList<String>()
    private val errors = AtomicInteger()
    private val cookieOrigin = "https://unrelated-${UUID.randomUUID()}.invalid"

    @After fun cleanup() {
        compose.runOnIdle { shown.value = false }
        // Expire only this fixture's cookie; never clear another WebView's store.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CookieManager.getInstance().setCookie(cookieOrigin, "fixture=; Max-Age=0; Path=/")
        }
    }

    private fun mount(entry: String = "index.html", load: suspend (String) -> WebPreviewResource?) {
        compose.setContent {
            if (shown.value) NativeWebAppPreview(entry, revision.longValue, { path ->
                assertNotEquals("Resource loading must not block the UI thread", Looper.getMainLooper(), Looper.myLooper())
                paths += path
                load(path)
            }, { errors.incrementAndGet() }, Modifier.fillMaxSize())
        }
    }

    private fun findWebView(node: View): WebView? {
        if (node is WebView) return node
        if (node is ViewGroup) for (index in 0 until node.childCount) {
            findWebView(node.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun webView(): WebView {
        var result: WebView? = null
        compose.runOnIdle { result = findWebView(compose.activity.window.decorView) }
        return requireNotNull(result)
    }

    private fun javascript(source: String): String {
        val result = AtomicReference<String>()
        val complete = CountDownLatch(1)
        val web = webView()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(source) { value -> result.set(value); complete.countDown() }
        }
        assertTrue("WebView must answer without blocking the UI", complete.await(3, TimeUnit.SECONDS))
        return result.get()
    }

    private fun waitForJavascript(predicate: String) {
        compose.waitUntil(10_000) { javascript("Boolean($predicate)") == "true" }
    }

    private fun text(value: String, mime: String) = WebPreviewResource(value.toByteArray(), mime)

    @Test fun percentageHeightRootsFillTheNativeViewportAndRemainHittableWithoutABridge() {
        mount { path -> if (path == "/index.html") text("""
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              html,body,#stage { margin:0; width:100%; height:100%; overflow:hidden; }
              #stage { position:relative; background:#e0eedf; }
              #target { position:absolute; left:20px; top:20px; width:160px; height:48px; }
            </style></head><body><main id="stage"><button id="target">Fixture action</button></main></body></html>
        """.trimIndent(), "text/html") else null }
        waitForJavascript("document.getElementById('target') !== null")
        val raw = javascript("""JSON.stringify((() => {
            const roots = [document.documentElement,document.body,document.getElementById('stage')];
            const bounds = roots.map(node => { const r=node.getBoundingClientRect(); return {tag:node.tagName,width:r.width,height:r.height}; });
            const target=document.getElementById('target'), rect=target.getBoundingClientRect();
            const hit=document.elementFromPoint(rect.x+rect.width/2,rect.y+rect.height/2);
            return {bounds,viewport:{width:innerWidth,height:innerHeight},
                fillsViewport:bounds.every(r => Math.abs(r.width-innerWidth)<2 && Math.abs(r.height-innerHeight)<2 && r.height>100),
                hitTarget:hit===target || target.contains(hit),hitTag:hit?.tagName};
        })())""".trimIndent())
        val diagnostics = JSONObject(JSONTokener(raw).nextValue() as String)
        assertTrue("Percentage roots must fill the measured native viewport: $diagnostics", diagnostics.getBoolean("fillsViewport"))
        assertTrue("Rendered controls must receive DOM hit tests: $diagnostics", diagnostics.getBoolean("hitTarget"))
        assertEquals("\"undefined\"", javascript("typeof window.BlinkNative"))
        assertEquals(0, errors.get())
    }

    private fun builtApp(path: String): WebPreviewResource? = when (path) {
        "/index.html" -> text("""
            <!doctype html><html><head><link rel="stylesheet" href="/assets/theme.css?cache=1"></head>
            <body><button id="counter">0</button><a id="route" href="/details?cache=2#section">Details</a>
            <p id="data"></p><script type="module" src="/assets/main.js?cache=3"></script></body></html>
        """.trimIndent(), "text/html")
        "/assets/theme.css" -> text("#counter { color: rgb(18, 52, 86); }", "text/css")
        "/assets/main.js" -> text("""
            import {mount} from './counter.js?cache=4';
            mount();
            fetch('/data.json?cache=5').then(r => r.json()).then(data => {
              document.getElementById('data').textContent = data.value;
              window.fixtureReady = true;
            });
        """.trimIndent(), "text/javascript")
        "/assets/counter.js" -> text("""
            export function mount() {
              const button = document.getElementById('counter');
              let count = 0; button.onclick = () => { button.textContent = String(++count); };
            }
        """.trimIndent(), "application/javascript")
        "/data.json" -> text("""{"value":"callback data"}""", "application/json")
        else -> null
    }

    @Test fun aProductionViteReactBundleRendersAndUpdatesInsideTheNativeView() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "react-preview")
        assertTrue("Build and push test-fixtures/react-preview before this suite", File(directory,"index.html").isFile)
        mount { path ->
            val file = File(directory,path.removePrefix("/"))
            if (!file.isFile) null else WebPreviewResource(file.readBytes(), when(file.extension) {
                "html" -> "text/html"
                "js" -> "text/javascript"
                "css" -> "text/css"
                else -> "application/octet-stream"
            })
        }
        waitForJavascript("document.getElementById('react-counter')")
        assertEquals("\"rgb(18, 52, 86)\"", javascript("getComputedStyle(document.getElementById('react-counter')).color"))
        javascript("document.getElementById('react-counter').click()")
        waitForJavascript("document.getElementById('react-counter').textContent === '1'")
        javascript("document.getElementById('react-route').click()")
        waitForJavascript("location.pathname === '/details' && document.getElementById('react-counter')")
        assertEquals(0,errors.get())
    }

    @Test fun modulesCssCounterAndSpaNavigationUseCallbackResources() {
        mount(load = ::builtApp)
        waitForJavascript("window.fixtureReady")
        assertEquals("\"rgb(18, 52, 86)\"", javascript("getComputedStyle(document.getElementById('counter')).color"))
        javascript("document.getElementById('counter').click()")
        assertEquals("\"1\"", javascript("document.getElementById('counter').textContent"))
        assertEquals("\"callback data\"", javascript("document.getElementById('data').textContent"))
        assertTrue(paths.containsAll(listOf("/index.html", "/assets/main.js", "/assets/counter.js", "/assets/theme.css", "/data.json")))
        assertTrue("Only normalized paths reach the credentialed host callback", paths.all { it.startsWith('/') && '?' !in it && '#' !in it && "://" !in it })

        javascript("document.getElementById('route').click()")
        waitForJavascript("location.pathname === '/details' && window.fixtureReady")
        assertTrue(paths.contains("/details"))
        assertTrue("Extensionless document navigation reloads the entry", paths.count { it == "/index.html" } >= 2)
        javascript("history.pushState({}, '', '/client-route')")
        assertEquals("\"/client-route\"", javascript("location.pathname"))
        assertEquals(0, errors.get())
    }

    @Test fun foreignRequestsNonGetWorkersPopupsAndFramesAreBlocked() {
        mount(load = ::builtApp)
        waitForJavascript("window.fixtureReady")
        val origin = javascript("location.origin")
        javascript("""
            window.blocked = {};
            for (const name of ['WebSocket', 'RTCPeerConnection', 'Worker', 'SharedWorker']) {
              try { new window[name]('/worker.js'); blocked[name] = false; } catch (_) { blocked[name] = true; }
            }
            try { navigator.serviceWorker.register('/worker.js'); blocked.serviceWorker = false; }
            catch (_) { blocked.serviceWorker = true; }
            blocked.popup = window.open('https://outside.invalid/') === null;
            document.cookie = 'forbidden=1'; blocked.cookie = document.cookie === '';
            const frame = document.createElement('iframe'); frame.src = '/frame.html'; document.body.append(frame);
            const form = document.createElement('form'); form.action = '/post'; form.method = 'POST';
            document.body.append(form); form.submit();
            Promise.allSettled([
              fetch('https://outside.invalid/resource'), fetch('file:///data/local/private'),
              fetch('content://fixture/private'), fetch('/post', {method:'POST', body:'fixture'})
            ]).then(results => {
              blocked.foreign = results.slice(0,3).every(r => r.status === 'rejected');
              const post = results[3]; blocked.post = post.status === 'rejected' || post.value.status === 403;
              blocked.finished = true;
            });
        """.trimIndent())
        waitForJavascript("window.blocked && blocked.finished")
        assertEquals("true", javascript("Object.values(blocked).every(Boolean)"))
        javascript("location.href = 'https://outside.invalid/navigation'")
        assertEquals(origin, javascript("location.origin"))
        assertFalse(paths.any { it in setOf("/post", "/worker.js", "/frame.html", "/resource", "/navigation") })

        // Exercise the native deny path independently of CSP. No socket is used.
        val web = webView()
        lateinit var client: WebViewClient
        compose.runOnIdle { client = web.webViewClient }
        val base = JSONObject("{\"origin\":$origin}").getString("origin")
        val before = paths.toList()
        runBlocking(Dispatchers.Default) {
            for ((url, method) in listOf(
                "https://outside.invalid/resource" to "GET", "file:///etc/passwd" to "GET",
                "content://fixture/private" to "GET", "$base/post" to "POST",
                "$base/%2e%2e/private" to "GET", "$base/assets%2Fprivate" to "GET",
            )) {
                val reply = client.shouldInterceptRequest(web, FixtureRequest(url, method))
                assertNotNull(reply)
                assertEquals(403, reply!!.statusCode)
            }
        }
        assertEquals(before, paths.toList())
        assertEquals(0, errors.get())
    }

    @Test fun revisionKeepsOpaqueStorageIsolationWithoutClearingOtherCookies() {
        mount(load = ::builtApp)
        waitForJavascript("window.fixtureReady")
        val original = webView()
        val origin = javascript("location.origin")
        val storageDenied = """
            (() => ['localStorage', 'sessionStorage'].every(name => {
              try { window[name].setItem('fixture', 'private'); return false; }
              catch (error) { return error.name === 'SecurityError'; }
            }))()
        """.trimIndent()
        assertEquals("\"null\"", javascript("self.origin"))
        assertEquals("true", javascript(storageDenied))
        val cookieWritten = CountDownLatch(1)
        compose.runOnIdle {
            CookieManager.getInstance().setCookie(cookieOrigin, "fixture=retained; Path=/") { cookieWritten.countDown() }
        }
        assertTrue(cookieWritten.await(3, TimeUnit.SECONDS))
        compose.runOnIdle { revision.longValue++ }
        waitForJavascript("window.fixtureReady")
        assertNotSame(original, webView())
        assertNotEquals(origin, javascript("location.origin"))
        assertEquals("\"null\"", javascript("self.origin"))
        assertEquals("true", javascript(storageDenied))
        compose.runOnIdle { shown.value = false }
        compose.runOnIdle {
            assertNull(findWebView(compose.activity.window.decorView))
            assertTrue(CookieManager.getInstance().getCookie(cookieOrigin).orEmpty().contains("fixture=retained"))
        }
        assertEquals(0, errors.get())
    }

    @Test fun freshBlankFramesCannotExposeNativeNetworkConstructors() {
        mount { path ->
            if (path != "/index.html") null else text("""
                <!doctype html><html><head><link rel="icon" href="data:,"></head><body><script>
                (() => {
                  const names = ['RTCPeerConnection', 'webkitRTCPeerConnection', 'WebSocket',
                                 'WebTransport', 'Worker', 'SharedWorker', 'EventSource'];
                  const guarded = realm => {
                    try { return names.every(name => {
                      const descriptor = Object.getOwnPropertyDescriptor(realm, name);
                      return descriptor && descriptor.writable === false && descriptor.configurable === false &&
                        typeof descriptor.value === 'function' &&
                        !Function.prototype.toString.call(descriptor.value).includes('[native code]');
                    }); } catch (error) { return error.name === 'SecurityError'; }
                  };
                  const checks = [guarded(window)];
                  for (const url of [null, 'about:blank']) {
                    const frame = document.createElement('iframe');
                    if (url) frame.src = url;
                    document.body.append(frame);
                    // Inspect immediately: a load-event patch is already too late.
                    checks.push(guarded(frame.contentWindow));
                    try {
                      const childDocument = frame.contentWindow.document;
                      const nested = childDocument.createElement('iframe');
                      childDocument.body.append(nested);
                      checks.push(guarded(nested.contentWindow));
                    } catch (error) { checks.push(error.name === 'SecurityError'); }
                    try {
                      const cookie = Object.getOwnPropertyDescriptor(frame.contentWindow.Document.prototype, 'cookie');
                      checks.push(cookie && cookie.configurable === false &&
                        !Function.prototype.toString.call(cookie.get).includes('[native code]'));
                    } catch (error) { checks.push(error.name === 'SecurityError'); }
                    frame.remove();
                  }
                  window.blankFrameAudit = checks;
                })();
                </script></body></html>
            """.trimIndent(), "text/html")
        }
        // No constructors are called, no ICE configuration is created, and no
        // offer, candidate, fetch or other network operation is attempted.
        waitForJavascript("Array.isArray(window.blankFrameAudit)")
        assertEquals("7", javascript("blankFrameAudit.length"))
        assertEquals("Fresh frame guard results: " + javascript("JSON.stringify(blankFrameAudit)"),
            "true", javascript("blankFrameAudit.every(Boolean)"))
        assertEquals(listOf("/index.html"), paths.toList())
        assertEquals(0, errors.get())
    }

    @Test fun rendererExitIsHandledWithoutCrashingAndRequiresExplicitReload() {
        mount(load = ::builtApp)
        waitForJavascript("window.fixtureReady")
        val original = webView()
        lateinit var client: WebViewClient
        val detail = object : RenderProcessGoneDetail() {
            override fun didCrash() = true
            override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_BOUND
        }
        compose.runOnIdle {
            client = original.webViewClient
            // Invoke the callback contract; never kill the real shared provider.
            assertTrue(client.onRenderProcessGone(original, detail))
            assertNull(findWebView(compose.activity.window.decorView))
            assertEquals(1, errors.get())
        }
        compose.runOnIdle { revision.longValue++ }
        waitForJavascript("window.fixtureReady")
        assertNotSame(original, webView())
        compose.runOnIdle { assertTrue(client.onRenderProcessGone(original, detail)) }
        assertEquals("A stale renderer callback cannot fail the replacement", 1, errors.get())
    }

    @Test fun missingEntryModuleReportsFailureInsteadOfLeavingABlankApp() {
        mount { path -> if (path == "/assets/main.js") null else builtApp(path) }
        compose.waitUntil(5_000) { errors.get() == 1 }
        assertTrue(paths.contains("/assets/main.js"))
        assertEquals(1, errors.get())
    }

    @Test fun unavailableStyleReportsFailureInsteadOfLeavingABrokenApp() {
        mount { path ->
            if (path == "/assets/theme.css") WebPreviewResource(ByteArray(0), "text/css", 503)
            else builtApp(path)
        }
        compose.waitUntil(5_000) { errors.get() == 1 }
        assertTrue(paths.contains("/assets/theme.css"))
        assertEquals(1, errors.get())
    }

    @Test fun extensionlessModuleReturningSpaHtmlReportsFailure() {
        mount { path ->
            when (path) {
                "/index.html" -> text("<!doctype html><script type=\"module\" src=\"/bootstrap\"></script>", "text/html")
                // Match the backend's extensionless SPA fallback, including 200.
                "/bootstrap" -> text("<!doctype html><p>SPA fallback</p>", "text/html")
                else -> null
            }
        }
        compose.waitUntil(5_000) { errors.get() == 1 }
        assertTrue(paths.contains("/bootstrap"))
        assertEquals(1, errors.get())
    }

    @Test fun disposalCancelsLoadsAndSuppressesLateFailure() {
        val started = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val delivered = AtomicBoolean()
        val release = CompletableDeferred<Unit>()
        mount {
            started.set(true)
            try { CompletableDeferred<Unit>().await(); null }
            finally {
                cancelled.set(true)
                // Model a transport which delivers a late completion after cancel.
                withContext(NonCancellable) { withTimeout(5_000) { release.await() } }
                delivered.set(true)
            }
        }
        compose.waitUntil(5_000) { started.get() }
        compose.runOnIdle { shown.value = false }
        compose.waitUntil(5_000) { cancelled.get() }
        release.complete(Unit)
        compose.waitUntil(5_000) { delivered.get() }
        compose.runOnIdle { assertNull(findWebView(compose.activity.window.decorView)) }
        assertEquals("Late completion must not call into the disposed screen", 0, errors.get())
    }

    @Test fun missingMainDocumentReportsErrorAndDoesNotFallBackToNetwork() {
        mount { null }
        compose.waitUntil(5_000) { errors.get() == 1 }
        assertEquals(listOf("/index.html"), paths.toList())
        compose.runOnIdle { shown.value = false }
        assertEquals(1, errors.get())
    }
}

private class FixtureRequest(url: String, private val requestMethod: String) : WebResourceRequest {
    private val uri = Uri.parse(url)
    override fun getUrl(): Uri = uri
    override fun isForMainFrame() = false
    override fun isRedirect() = false
    override fun hasGesture() = false
    override fun getMethod() = requestMethod
    override fun getRequestHeaders(): Map<String, String> = emptyMap()
}
