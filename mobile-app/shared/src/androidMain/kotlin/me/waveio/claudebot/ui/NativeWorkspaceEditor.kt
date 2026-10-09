@file:JvmName("NativeWorkspaceEditorAndroidKt")

package me.waveio.claudebot.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import me.waveio.claudebot.data.*

@Composable
actual fun NativeWorkspaceEditor(
    document: WorkspaceEditorDocument,
    session: WorkspaceEditorSession,
    onChange: (String) -> Unit,
    onAction: (WorkspaceEditorAction) -> Unit,
    onError: (String) -> Unit,
    loadResource: suspend (String) -> WebPreviewResource?,
    modifier: Modifier,
) {
    key(document.id, session) {
        val host = remember { AndroidWorkspaceEditor(document, session, onChange, onAction, onError, loadResource) }
        SideEffect { host.update(document, onChange, onAction, onError, loadResource) }
        DisposableEffect(host) { onDispose { host.close() } }
        AndroidView(factory = host::createView, modifier = modifier.testTag("native-workspace-editor"), onRelease = { host.close() })
    }
}

/** Only shipped editor code receives this instance's origin-scoped message port. */
private class AndroidWorkspaceEditor(
    document: WorkspaceEditorDocument,
    private val session: WorkspaceEditorSession,
    onChange: (String) -> Unit,
    onAction: (WorkspaceEditorAction) -> Unit,
    private var onError: (String) -> Unit,
    @Volatile private var loader: suspend (String) -> WebPreviewResource?,
) {
    private val host = "workspace-${UUID.randomUUID()}.invalid"
    private val origin = "https://$host"
    private val alive = AtomicBoolean(true)
    private val requests = SupervisorJob()
    private val scope = CoroutineScope(requests + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    private var bootstrap: ScriptHandler? = null
    @Volatile private var manifest: WorkspaceEditorManifest? = null
    private var activity: Activity? = null
    private var lifecycle: Application.ActivityLifecycleCallbacks? = null
    private val bridge = WorkspaceEditorBridge(document, scope, { script, complete ->
        val web = view
        if (!alive.get() || web == null) complete(false)
        else web.evaluateJavascript(script) { result -> complete(alive.get() && result == "true") }
    }, onChange, onAction, onError)

    fun update(document: WorkspaceEditorDocument, change: (String) -> Unit, action: (WorkspaceEditorAction) -> Unit,
               error: (String) -> Unit, load: suspend (String) -> WebPreviewResource?) {
        loader = load; onError = error
        bridge.onChange = change; bridge.onAction = action; bridge.onError = error
        bridge.update(document)
    }

    private fun path(uri: Uri): String? {
        if (uri.scheme != "https" || uri.host != host || uri.port != -1 && uri.port != 443 || uri.userInfo != null) return null
        return workspaceEditorResourcePath(uri.encodedPath.orEmpty())
    }

    private fun sameOrigin(uri: Uri) = uri.scheme == "https" && uri.host == host &&
        (uri.port == -1 || uri.port == 443) && uri.userInfo == null

    private fun fail(code: String = "editor_failed") {
        main.post { if (alive.get()) onError(code) }
    }

    private fun response(resource: WebPreviewResource) = WebResourceResponse(resource.mimeType, "utf-8", resource.status,
        if (resource.status in 200..299) "OK" else "Unavailable", WorkspaceEditorResponseHeaders, ByteArrayInputStream(resource.bytes))

    private fun denied(status: Int = 403) = response(WebPreviewResource(ByteArray(0), "text/plain", status))

    private fun intercept(request: WebResourceRequest): WebResourceResponse {
        if (!alive.get() || request.method != "GET") return denied()
        val requestPath = path(request.url) ?: return denied()
        if (request.isForMainFrame && requestPath != "/index.html") return denied()
        if (!request.isForMainFrame && requestPath == "/index.html") return denied()
        val workspacePath = requestPath.takeIf { it.startsWith("/workspace/") }?.removePrefix("/workspace/")
        if (workspacePath != null && !validWorkspaceEditorResourcePath(workspacePath)) return denied()
        val bundle = manifest
        // WebView may probe for a favicon that the application never requested.
        // Unknown paths remain unavailable; only shipped asset failures break the editor.
        if (workspacePath == null && !request.isForMainFrame && bundle?.files?.containsKey(requestPath.removePrefix("/")) != true) return denied(404)
        if (Looper.myLooper() == Looper.getMainLooper()) { fail(); return denied(503) }
        return try {
            val resource = runBlocking(requests + Dispatchers.IO) {
                withTimeout(WorkspaceEditorResourceTimeoutMillis) {
                    if (workspacePath != null) loader(workspacePath)?.let { guardedWorkspaceEditorResource(workspacePath, it) }
                    else bundle?.let { loadWorkspaceEditorAsset(it, requestPath) }
                }
            }
            if (!alive.get()) denied(410)
            else if (resource == null) {
                fail(if (workspacePath != null) "resource_unavailable" else "editor_failed")
                denied(404)
            } else response(resource)
        } catch (_: CancellationException) { denied(410) }
        catch (_: Exception) { fail(if (workspacePath != null) "resource_unavailable" else "editor_failed"); denied(503) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun createView(context: Context): WebView = WebView(context).also { web ->
        view = web
        // Chromium changes percentage-height layout for WRAP_CONTENT even when
        // Compose measures the native view to a full-size rectangle.
        web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.setDownloadListener { _, _, _, _, _ -> Unit }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, false, false) }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest) = intercept(request)
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                !alive.get() || !request.isForMainFrame || request.method != "GET" || path(request.url) != "/index.html"

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url == null || path(Uri.parse(url)) != "/index.html") { view?.stopLoading(); fail() }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError?) {
                if (request.isForMainFrame || path(request.url)?.let { manifest?.files?.containsKey(it.removePrefix("/")) } == true) fail()
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, errorResponse: WebResourceResponse?) {
                if (request.isForMainFrame) fail()
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) { handler?.cancel() }
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) { handler?.cancel() }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val report = alive.get()
                close(rendererTerminated = true)
                if (report) onError("editor_failed")
                return true
            }
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            web.settings.javaScriptEnabled = false
            fail()
            return@also
        }
        WebViewCompat.addWebMessageListener(web, "BlinkNative", setOf(origin)) { sender, message, source, isMainFrame, _ ->
            if (alive.get() && sender === view && isMainFrame && sameOrigin(source) &&
                sender.url?.let { path(Uri.parse(it)) } == "/index.html" && message.type == WebMessageCompat.TYPE_STRING) {
                message.data?.let(bridge::receive)
            }
        }
        bootstrap = WebViewCompat.addDocumentStartJavaScript(web, PreviewBootstrap, setOf(origin))
        session.attach(this, bridge::flush, bridge::resolveAction)
        registerBackgroundFlush(context)
        scope.launch {
            try {
                val bundle = withContext(Dispatchers.IO) { withTimeout(WorkspaceEditorResourceTimeoutMillis) { loadWorkspaceEditorManifest() } }
                if (!alive.get()) return@launch
                manifest = bundle
                web.loadUrl("$origin/${bundle.entry}")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail() }
        }
    }

    private fun registerBackgroundFlush(context: Context) {
        var current = context
        while (current is ContextWrapper && current !is Activity) {
            val next = current.baseContext
            if (next === current) break
            current = next
        }
        val owner = current as? Activity ?: return
        activity = owner
        lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) { if (activity === owner && alive.get()) bridge.flush { } }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }.also(owner.application::registerActivityLifecycleCallbacks)
    }

    fun close(rendererTerminated: Boolean = false) {
        if (!alive.compareAndSet(true, false)) return
        session.detach(this)
        bridge.close()
        lifecycle?.let { activity?.application?.unregisterActivityLifecycleCallbacks(it) }
        lifecycle = null; activity = null
        scope.cancel()
        view?.let { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            if (!rendererTerminated) {
                web.stopLoading()
                bootstrap?.remove()
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, "BlinkNative")
                web.webChromeClient = null
                web.webViewClient = WebViewClient()
                web.removeAllViews()
            }
            web.destroy()
        }
        bootstrap = null; view = null; manifest = null
    }
}
