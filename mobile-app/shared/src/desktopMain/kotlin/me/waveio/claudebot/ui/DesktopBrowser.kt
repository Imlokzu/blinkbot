package me.waveio.claudebot.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.json.*
import me.waveio.claudebot.data.*
import org.cef.CefApp
import org.cef.CefClient
import org.cef.CefSettings
import com.jetbrains.cef.JCefAppConfig
import org.cef.browser.*
import org.cef.callback.*
import org.cef.handler.*
import org.cef.misc.*
import org.cef.network.*
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import java.nio.file.Path

/** JCEF ships inside the pinned runtime; startup never downloads executable code. */
object DesktopBrowserRuntime {
    private var app: CefApp? = null
    private var cacheDirectory: Path? = null
    internal val browserHandles = mutableListOf<CefBrowser>()
    internal val disconnectionHandlers = mutableListOf<() -> Unit>()
    fun initialize() {
        // The bundled server owns Chromium's AppKit loop independently of Compose.
        System.setProperty("CEF_SERVER_USE_PIPE", "true")
        CefApp.setIsRemoteEnabled(true)
        val config = JCefAppConfig.getInstance()
        val cache = Files.createTempDirectory("blink-browser-").also { cacheDirectory = it }
        val args = config.appArgs + arrayOf("--disable-background-networking", "--disable-component-update", "--disable-sync", "--enable-features=ConnectionAllowlists,OverrideConnectionAllowlistOriginTrial", "--user-data-dir=$cache")
        check(CefApp.startup(args))
        val settings = config.cefSettings.apply {
            windowless_rendering_enabled = true
            background_color = this.ColorType(255, 255, 255, 255)
            persist_session_cookies = false
            cache_path = cache.toString()
            no_sandbox = false
            log_file = System.getProperty("blink.browserLog")
            log_severity = if (log_file.isNullOrBlank()) CefSettings.LogSeverity.LOGSEVERITY_DISABLE else CefSettings.LogSeverity.LOGSEVERITY_INFO
            remote_debugging_port = System.getProperty("blink.browserDebugPort")?.toIntOrNull() ?: 0
        }
        app = CefApp.getInstance(args, settings, null)
        app?.setDisconnectionCallback { javax.swing.SwingUtilities.invokeLater { disconnectionHandlers.toList().forEach { it() } } }
    }
    internal fun client(): CefClient = requireNotNull(app).createClient()
    fun close() {
        browserHandles.toList().forEach { it.close(true) }
        browserHandles.clear(); disconnectionHandlers.clear(); app?.dispose(); app = null
        cacheDirectory?.let { directory -> runCatching { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } } }
        cacheDirectory = null
    }
}

/** Window close waits for the same browser flush that native in-app exits use. */
object DesktopEditorExit {
    private var session: WorkspaceEditorSession? = null
    private var owner: Any? = null
    internal fun attach(owner: Any, session: WorkspaceEditorSession) { this.owner = owner; this.session = session }
    internal fun detach(owner: Any) { if (this.owner === owner) { this.owner = null; session = null } }
    fun flush(complete: (Boolean) -> Unit) { session?.flush(complete) ?: complete(true) }
}

/** Ephemeral browser origins allow only the native-owned callback resources. */
internal fun desktopBrowserPath(url: String, origin: String, trusted: Boolean): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val expected = runCatching { URI(origin) }.getOrNull() ?: return null
    if (uri.scheme != expected.scheme || uri.host != expected.host || uri.port != expected.port || uri.rawUserInfo != null) return null
    return if (trusted) workspaceEditorResourcePath(uri.rawPath.orEmpty()) else previewResourcePath(uri.rawPath.orEmpty())
}

/** Response-header CSP creates the opaque origin; guards parse before user markup. */
internal fun desktopPreviewDocument(html: String, origin: String): ByteArray {
    require(Regex("http://[a-z0-9-]+\\.localhost:[0-9]+").matches(origin))
    val links = """document.addEventListener('click', event => {
        const link=event.target.closest && event.target.closest('a');
        if (link && (link.hasAttribute('download') || new URL(link.href,document.URL).origin !== new URL(document.URL).origin)) event.preventDefault();
    },true);"""
    return ("<!doctype html><script>$PreviewBootstrap$links</script>$html").toByteArray()
}

private class DesktopBrowserHost(
    private val trusted: Boolean,
    private val entry: String,
    private val resources: suspend (String, Boolean, String) -> WebPreviewResource?,
    private val message: ((String) -> Unit)?,
    private val error: () -> Unit,
) {
    private val alive = AtomicBoolean(true)
    private val failureReported = AtomicBoolean(false)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Swing)
    private val resourceServer = DesktopBrowserResourceServer(trusted, resources, ::fail, entry)
    val origin = resourceServer.origin
    private val client = DesktopBrowserRuntime.client()
    private val router = if (trusted) CefMessageRouter.create() else null
    private val evaluations = mutableMapOf<String, Pair<(Boolean) -> Unit, Job>>()
    private lateinit var browser: CefBrowser
    private val surface = DesktopBrowserSurface()
    private val disconnected: () -> Unit = { fail() }
    private fun localPath(url: String): String? {
        return desktopBrowserPath(url, origin, trusted)
    }
    init {
        router?.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(browser: CefBrowser, frame: CefFrame, id: Long, request: String,
                                 persistent: Boolean, callback: CefQueryCallback): Boolean {
                if (!trusted || !alive.get() || !frame.isMain || localPath(frame.url) != "/index.html" ||
                    request.length > WorkspaceEditorMaxMessageBytes) { callback.failure(403, ""); return true }
                scope.launch {
                    if (!alive.get()) return@launch
                    val objectValue = runCatching { Json.parseToJsonElement(request).jsonObject }.getOrNull()
                    val token = (objectValue?.get("evaluation") as? JsonPrimitive)?.contentOrNull
                    if (token != null) evaluations.remove(token)?.let { (complete, timeout) ->
                        timeout.cancel(); complete((objectValue["success"] as? JsonPrimitive)?.booleanOrNull == true)
                    } else message?.invoke(request)
                }
                callback.success("")
                return true
            }
        }, true)
        router?.let(client::addMessageRouter)
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(browser: CefBrowser, frame: CefFrame, url: String, name: String) = true
        })
        client.addPermissionHandler { _, _, _, _, callback -> callback.Cancel(); true }
        client.addDownloadHandler(object : CefDownloadHandlerAdapter() {
            override fun onBeforeDownload(browser: CefBrowser, item: CefDownloadItem, name: String, callback: CefBeforeDownloadCallback) = false
        })
        client.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest, gesture: Boolean, redirect: Boolean): Boolean {
                val path = localPath(request.url)
                return !alive.get() || !frame.isMain || request.method != "GET" || path == null || trusted && path != "/index.html"
            }
            override fun onOpenURLFromTab(browser: CefBrowser, frame: CefFrame, url: String, gesture: Boolean) = true
            override fun getResourceRequestHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest,
                navigation: Boolean, download: Boolean, initiator: String?, disable: BoolRef): CefResourceRequestHandler? {
                disable.set(true)
                val path = localPath(request.url)
                if (alive.get() && request.method == "GET" && path != null) { disable.set(false); return null }
                // No handler plus disabled default loading cancels the request in CEF.
                return null
            }
            override fun onRenderProcessTerminated(browser: CefBrowser, status: CefRequestHandler.TerminationStatus, code: Int, text: String) = fail()
        })
        // The HTTP boundary reports main/critical resource errors. The pinned remote
        // CEF load-handler callback stalls its channel after a blocked child navigation.
        browser = client.createBrowser("$origin$entry", CefRendering.CefRenderingWithHandler(surface.renderer, surface),
            false, CefRequestContext.createContext(null))
        surface.browser = browser
        DesktopBrowserRuntime.browserHandles += browser
        DesktopBrowserRuntime.disconnectionHandlers += disconnected
        scope.launch {
            delay(PreviewResourceTimeoutMillis)
            if (alive.get() && (browser.getIdentifier() <= 0 || surface.paintedFrame == null)) fail()
        }
    }
    val component get() = surface
    private fun fail() { scope.launch { if (alive.get() && failureReported.compareAndSet(false, true)) error() } }
    fun evaluate(script: String, complete: (Boolean) -> Unit) {
        if (!alive.get()) { complete(false); return }
        val token = UUID.randomUUID().toString()
        val timeout = scope.launch { delay(3000); evaluations.remove(token)?.first?.invoke(false) }
        evaluations[token] = complete to timeout
        val encoded = JsonPrimitive(token).toString()
        browser.executeJavaScript("""(() => { let success=false; try { success=($script) === true; } catch (_) {} window.cefQuery({request:JSON.stringify({evaluation:$encoded,success}),onSuccess:()=>{},onFailure:()=>{}}); })();""", "$origin/index.html", 0)
    }
    fun close() {
        if (!alive.compareAndSet(true, false)) return
        evaluations.values.toList().forEach { (complete, timeout) -> timeout.cancel(); complete(false) }
        evaluations.clear(); job.cancel()
        resourceServer.close()
        DesktopBrowserRuntime.browserHandles.remove(browser)
        DesktopBrowserRuntime.disconnectionHandlers.remove(disconnected)
        browser.close(true); router?.dispose(); client.dispose()
    }
}

@Composable
actual fun NativeWorkspaceEditor(document: WorkspaceEditorDocument, session: WorkspaceEditorSession,
    onChange: (String) -> Unit, onAction: (WorkspaceEditorAction) -> Unit, onError: (String) -> Unit,
    loadResource: suspend (String) -> WebPreviewResource?, modifier: Modifier) {
    key(document.id, session) {
        val scope = rememberCoroutineScope()
        val loader by rememberUpdatedState(loadResource)
        val failure by rememberUpdatedState(onError)
        val manifest by produceState<WorkspaceEditorManifest?>(null) {
            value = runCatching { withContext(Dispatchers.IO) { loadWorkspaceEditorManifest() } }.getOrNull()
            if (value == null) failure("editor_failed")
        }
        val pack = manifest
        if (pack != null) {
            lateinit var host: DesktopBrowserHost
            val bridge = remember { WorkspaceEditorBridge(document, scope, { script, done -> host.evaluate(script, done) }, onChange, onAction, onError) }
            host = remember {
                DesktopBrowserHost(true, "/index.html", { path, main, _ ->
                    when {
                        path == "/blink-bridge.js" -> WebPreviewResource("window.BlinkNative=Object.freeze({postMessage:raw=>window.cefQuery({request:raw,onSuccess:()=>{},onFailure:()=>{}})});".toByteArray(), "text/javascript")
                        path.startsWith("/workspace/") -> path.removePrefix("/workspace/").let { relative ->
                            loader(relative)?.let { guardedWorkspaceEditorResource(relative, it) }
                        }
                        path == "/index.html" && !main -> null
                        else -> loadWorkspaceEditorAsset(pack, path)?.let { resource ->
                            if (path == "/index.html") resource.copy(bytes = resource.bytes.decodeToString()
                                .replace("<head>", "<head><script src=\"/blink-bridge.js\"></script>").toByteArray()) else resource
                        }
                    }
                }, bridge::receive, { failure("editor_failed") })
            }
            SideEffect { bridge.onChange = onChange; bridge.onAction = onAction; bridge.onError = onError; bridge.update(document) }
            DisposableEffect(host) {
                session.attach(host, bridge::flush, bridge::resolveAction)
                if (!document.readOnly) DesktopEditorExit.attach(host, session)
                onDispose { DesktopEditorExit.detach(host); session.detach(host); bridge.close(); host.close() }
            }
            SwingPanel(factory = { host.component }, modifier = modifier.testTag("native-workspace-editor"))
        }
    }
}

@Composable
actual fun NativeWebAppPreview(entry: String, revision: Long, loadResource: suspend (String) -> WebPreviewResource?,
    onError: () -> Unit, modifier: Modifier) {
    val entryPath = previewEntryPath(entry)
    if (entryPath == null) { LaunchedEffect(entry) { onError() }; return }
    key(entry, revision) {
        val loader by rememberUpdatedState(loadResource)
        val failure by rememberUpdatedState(onError)
        val host = remember {
            DesktopBrowserHost(false, entryPath, { path, main, origin ->
                var resource = loader(path)?.let(::guardedPreviewResource)
                if (main && (resource == null || resource.status == 404) && previewSpaRoute(path)) resource = loader(entryPath)?.let(::guardedPreviewResource)
                resource?.takeIf { if (main) it.status in 200..299 && it.mimeType == "text/html" else it.mimeType != "text/html" && previewAssetMimeMatches(path, it.mimeType) }
                    ?.let { response ->
                        if (main) response.copy(bytes = desktopPreviewDocument(response.bytes.decodeToString(), origin)) else response
                    }
            }, null, { failure() })
        }
        DisposableEffect(host) { onDispose { host.close() } }
        SwingPanel(factory = { host.component }, modifier = modifier.testTag("native-web-app-preview"))
    }
}
