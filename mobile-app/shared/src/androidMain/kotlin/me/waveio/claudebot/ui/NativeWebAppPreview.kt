@file:JvmName("NativeWebAppPreviewAndroidKt")

package me.waveio.claudebot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.waveio.claudebot.data.WebPreviewResource

@Composable
actual fun NativeWebAppPreview(
    entry: String,
    revision: Long,
    loadResource: suspend (String) -> WebPreviewResource?,
    onError: () -> Unit,
    modifier: Modifier,
) {
    key(entry, revision) {
        val session = remember { AndroidPreviewSession(entry, loadResource, onError) }
        SideEffect { session.loader = loadResource; session.onError = onError }
        DisposableEffect(session) { onDispose { session.close() } }
        AndroidView(
            factory = { session.createView(it) },
            modifier = modifier.testTag("native-web-app-preview"),
            onRelease = { session.close() },
        )
    }
}

/** No request, header or credential from the host transport reaches this WebView. */
private class AndroidPreviewSession(
    entry: String,
    @Volatile var loader: suspend (String) -> WebPreviewResource?,
    @Volatile var onError: () -> Unit,
) {
    private val entryPath = previewEntryPath(entry)
    private val host = "preview-${UUID.randomUUID()}.invalid"
    private val origin = "https://$host"
    private val alive = AtomicBoolean(true)
    private val errorReported = AtomicBoolean(false)
    private val requests = SupervisorJob()
    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    private var bootstrap: ScriptHandler? = null

    private fun path(uri: Uri): String? {
        if (uri.scheme != "https" || uri.host != host || uri.port != -1 && uri.port != 443 || uri.userInfo != null) return null
        return previewResourcePath(uri.encodedPath.orEmpty())
    }

    private fun fail() {
        main.post { if (alive.get() && errorReported.compareAndSet(false, true)) onError() }
    }

    private fun response(resource: WebPreviewResource): WebResourceResponse = WebResourceResponse(
        resource.mimeType,
        "utf-8",
        resource.status,
        if (resource.status in 200..299) "OK" else "Unavailable",
        PreviewResponseHeaders,
        ByteArrayInputStream(resource.bytes),
    )

    private fun denied(status: Int = 403) = response(WebPreviewResource(ByteArray(0), "text/plain", status))

    private fun destination(request: WebResourceRequest): String? = request.requestHeaders.entries
        .firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value?.trim()?.lowercase()

    private fun critical(request: WebResourceRequest, mime: String? = null): Boolean {
        val resourcePath = path(request.url) ?: return false
        return request.isForMainFrame || previewCriticalAsset(resourcePath, mime, destination(request))
    }

    private fun intercept(request: WebResourceRequest): WebResourceResponse {
        if (!alive.get() || request.method != "GET") return denied()
        val resourcePath = path(request.url) ?: return denied()
        // WebView calls interception on its own worker thread. Never block the
        // main thread; cancellation of this parent job aborts every pending load.
        if (Looper.myLooper() == Looper.getMainLooper()) { fail(); return denied(503) }
        return try {
            val resource = runBlocking(requests + Dispatchers.IO) {
                withTimeout(PreviewResourceTimeoutMillis) {
                    var loaded = loader(resourcePath)
                    if ((loaded == null || loaded.status == 404) && request.isForMainFrame &&
                        resourcePath != entryPath && previewSpaRoute(resourcePath) && entryPath != null) {
                        loaded = loader(entryPath)
                    }
                    loaded?.let(::guardedPreviewResource)
                }
            }
            if (!alive.get()) denied(410)
            else if (resource == null) {
                if (critical(request)) fail()
                denied(404)
            } else if (request.isForMainFrame && (resource.status !in 200..299 || resource.mimeType != "text/html")) {
                fail(); denied(resource.status.takeIf { it in 400..599 } ?: 415)
            } else if (!request.isForMainFrame && resource.mimeType == "text/html") {
                // A SPA fallback must never masquerade as an extensionless
                // module, even when WebView omits Fetch Metadata headers.
                fail(); denied(415)
            } else if (!previewAssetMimeMatches(resourcePath, resource.mimeType, destination(request))) {
                if (critical(request)) fail()
                denied(415)
            } else {
                if (resource.status !in 200..299 && critical(request, resource.mimeType)) fail()
                response(resource)
            }
        } catch (_: CancellationException) {
            if (alive.get() && critical(request)) fail()
            denied(410)
        } catch (_: Exception) {
            if (critical(request)) fail()
            denied(503)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun createView(context: Context): WebView = WebView(context).also { web ->
        view = web
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            WebView.setWebContentsDebuggingEnabled(false)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
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
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse = intercept(request)
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                !alive.get() || !request.isForMainFrame || request.method != "GET" || path(request.url) == null

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url == null || path(Uri.parse(url)) == null) { view?.stopLoading(); fail() }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError?) {
                if (critical(request)) fail()
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, errorResponse: WebResourceResponse?) {
                if (critical(request, errorResponse?.mimeType)) fail()
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val report = alive.get() && errorReported.compareAndSet(false, true)
                close(rendererTerminated = true)
                if (report) onError()
                return true
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) { handler?.cancel() }
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) { handler?.cancel() }
        }
        val entry = entryPath
        if (entry == null || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // A top-document HTML prefix cannot guard a fresh about:blank realm.
            // Old providers must fail closed instead of running partially isolated JS.
            web.settings.javaScriptEnabled = false
            fail()
        } else {
            // This wildcard applies only to guard injection in this WebView. It
            // grants no origin network access and exposes no native JS interface.
            bootstrap = WebViewCompat.addDocumentStartJavaScript(web, PreviewBootstrap, setOf("*"))
            web.loadUrl(Uri.Builder().scheme("https").authority(host).path(entry).build().toString())
        }
    }

    fun close(rendererTerminated: Boolean = false) {
        if (!alive.compareAndSet(true, false)) return
        requests.cancel()
        // Compose releases AndroidView on the UI thread. Keep this safe if a
        // future owner also disposes the session from a background callback.
        val destroy = {
            view?.let { web ->
                (web.parent as? ViewGroup)?.removeView(web)
                if (!rendererTerminated) {
                    web.stopLoading()
                    bootstrap?.remove()
                    web.webChromeClient = null
                    web.webViewClient = WebViewClient()
                    web.removeAllViews()
                }
                bootstrap = null
                web.destroy()
            }
            view = null
            WebStorage.getInstance().deleteOrigin(origin)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) destroy() else main.post { destroy() }
    }
}
