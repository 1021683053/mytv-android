package top.yogiczy.mytv.tv.ui.screens.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import top.yogiczy.mytv.core.data.utils.ChannelUtil

import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    modifier: Modifier = Modifier,
    urlProvider: () -> String = { "${ChannelUtil.HYBRID_WEB_VIEW_URL_PREFIX}https://tv.cctv.com/live/index.shtml" },
    onVideoResolutionChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
    onError: (String) -> Unit = {},
    onLoading: () -> Unit = {},
    onBuffering: (Boolean) -> Unit = {},
) {
    val url = urlProvider().replace(ChannelUtil.HYBRID_WEB_VIEW_URL_PREFIX, "")
    // 使用 isLoading 状态来控制加载动画和 WebView 的可见性
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<MyWebView?>(null) }
    val bridge = remember {
        MyWebViewInterface(
            onVideoResolutionChanged = { width, height ->
                isLoading = false
                onBuffering(false)
                onVideoResolutionChanged(width, height)
            },
            onError = { error -> isLoading = true; onError(error) },
            onBuffering = onBuffering,
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            bridge.dispose()
            webView?.apply {
                webViewClient = WebViewClient()
                stopLoading()
                removeJavascriptInterface("Android")
                loadUrl("about:blank")
                onPause()
                removeAllViews()
                destroy()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .background(Color.Black),
            factory = {
                MyWebView(it).apply {
                    webView = this
                    webViewClient = MyClient(
                        onPageStarted = { isLoading = true; onLoading() },
                        onError = { error -> isLoading = true; onError(error) },
                    )

                    setBackgroundColor(Color.Black.toArgb())
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )

                    settings.apply {
                        javaScriptEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        domStorageEnabled = true
                        cacheMode = WebSettings.LOAD_DEFAULT

                        // 提速优化 1：禁止自动加载图片和拦截网络图片
                        loadsImagesAutomatically = false
                        blockNetworkImage = true

                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportZoom(false)
                        displayZoomControls = false
                        builtInZoomControls = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        mediaPlaybackRequiresUserGesture = false
                    }

                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    isClickable = false
                    isFocusable = false
                    isFocusableInTouchMode = false

                    addJavascriptInterface(bridge, "Android")
                    loadUrl(url)
                }
            },

        )

        // Keep the web surface rendering under an opaque cover until a real video frame arrives.
        if (isLoading) Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

class MyClient(
    private val onPageStarted: () -> Unit,
    private val onError: (String) -> Unit,
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        onPageStarted()
        super.onPageStarted(view, url, favicon)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) onError("WEB_CONNECTION(${error.errorCode})")
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) onError("HTTP_${response.statusCode}")
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        if (error.url == view.url) onError("WEB_SSL_${error.primaryError}")
    }

    // 提速优化 2：拦截无用资源的请求，极大缩短网页加载时间
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val reqUrl = request?.url?.toString() ?: ""
        val blockKeywords = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", // 屏蔽图片
            "google-analytics", "hm.baidu.com", "umeng.com",  // 屏蔽常见的统计脚本
            "adsystem", "doubleclick"                         // 屏蔽常见广告
        )

        if (blockKeywords.any { reqUrl.contains(it, ignoreCase = true) }) {
            // 返回一个空的响应，直接掐断该网络请求
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (url == "about:blank") return
        view.evaluateJavascript(
            """
            ;(async () => {
                if (window.__mytvObserver) return;
                window.__mytvObserver = true;
                // ==========================================
                // 1. 全局注入样式：暴力霸屏，遮挡原生网页
                // ==========================================
                const style = document.createElement('style');
                style.innerHTML = `
                    /* 隐藏整个网页自带的滚动条 */
                    html, body { width: 100vw !important; height: 100vh !important; background: #000 !important; margin: 0 !important; padding: 0 !important; overflow: hidden !important; }
                    
                    /* 终极 video 霸屏 CSS */
                    video {
                        width: 100vw !important; height: 100vh !important;
                        position: fixed !important; top: 0 !important; left: 0 !important; bottom: 0 !important; right: 0 !important;
                        z-index: 2147483647 !important; background: #000 !important;
                        max-width: none !important; max-height: none !important;
                        transform: none !important; object-fit: contain !important;
                        border: none !important; margin: 0 !important; padding: 0 !important;
                        display: block !important; visibility: visible !important; opacity: 1 !important;
                    }
                    
                    /* 遮罩层，阻挡原生网页的点击暂停事件 */
                    #ytv-mask {
                        width: 100vw !important; height: 100vh !important;
                        position: fixed !important; top: 0 !important; left: 0 !important;
                        z-index: 2147483648 !important; background: transparent !important;
                    }
                `;
                document.head.appendChild(style);

                // 2. 创建防误触遮罩
                if(!document.getElementById('ytv-mask')) {
                    const mask = document.createElement('div');
                    mask.id = 'ytv-mask';
                    mask.addEventListener('click', (e) => { e.stopPropagation(); e.preventDefault(); });
                    document.body.appendChild(mask);
                }

                const timeout = ${top.yogiczy.mytv.tv.ui.utils.Configs.videoPlayerLoadTimeout};
                let boundVideo = null;
                let isReady = false;
                let failed = false;
                let lastPosition = null;
                let lastAdvance = performance.now();
                let lastBuffering = null;
                let layoutDirty = true;
                let lastLayoutCheck = -Infinity;
                let lastPlayAttempt = -Infinity;
                let playPending = false;
                let frameCallback = null;
                let cleanupVideo = () => {};
                let timer;
                const observer = new MutationObserver(() => { layoutDirty = true; });
                const observeLayout = () => observer.observe(document.documentElement, {
                    childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style']
                });
                function buffering(value) {
                    if (!failed && value !== lastBuffering) {
                        lastBuffering = value;
                        Android.buffering(value);
                    }
                }
                function fail(code) {
                    if (failed) return;
                    failed = true;
                    clearInterval(timer);
                    observer.disconnect();
                    cleanupVideo();
                    Android.videoError(code);
                }
                function ready(video) {
                    if (boundVideo !== video || failed) return;
                    if (!isReady && video.videoWidth > 0 && video.videoHeight > 0) {
                        isReady = true;
                        lastAdvance = performance.now();
                        buffering(false);
                        Android.changeVideoResolution(video.videoWidth, video.videoHeight);
                    }
                }
                function fixLayout(video, now) {
                    if (!layoutDirty || now - lastLayoutCheck < 1000) return;
                    layoutDirty = false;
                    lastLayoutCheck = now;
                    // Our own style updates must not schedule another layout pass.
                    observer.disconnect();
                    let p = video.parentElement;
                    while (p && p !== document.body && p !== document.documentElement) {
                        const computed = window.getComputedStyle(p);
                        for (const [property, field, expected] of [
                            ['transform', 'transform', 'none'], ['filter', 'filter', 'none'],
                            ['contain', 'contain', 'none'], ['clip-path', 'clipPath', 'none'],
                            ['opacity', 'opacity', '1'], ['z-index', 'zIndex', 'auto'],
                            ['position', 'position', 'static']
                        ]) {
                            if (computed[field] !== expected) p.style.setProperty(property, expected, 'important');
                        }
                        p = p.parentElement;
                    }
                    observeLayout();
                }
                function bind(video, now) {
                    cleanupVideo();
                    if (boundVideo === null) lastAdvance = now;
                    boundVideo = video;
                    isReady = false;
                    lastPosition = null;
                    layoutDirty = true;
                    lastPlayAttempt = -Infinity;
                    playPending = false;
                    buffering(true);
                    const onError = () => { if (boundVideo === video) fail('WEB_VIDEO_' + (video.error ? video.error.code : 0)); };
                    const onWaiting = () => { if (boundVideo === video) buffering(true); };
                    const onPlaying = () => { if (boundVideo === video) buffering(false); };
                    video.addEventListener('error', onError);
                    video.addEventListener('waiting', onWaiting);
                    video.addEventListener('playing', onPlaying);
                    frameCallback = typeof video.requestVideoFrameCallback === 'function'
                        ? video.requestVideoFrameCallback(() => ready(video)) : null;
                    cleanupVideo = () => {
                        video.removeEventListener('error', onError);
                        video.removeEventListener('waiting', onWaiting);
                        video.removeEventListener('playing', onPlaying);
                        if (frameCallback !== null && typeof video.cancelVideoFrameCallback === 'function') {
                            video.cancelVideoFrameCallback(frameCallback);
                        }
                        frameCallback = null;
                    };
                    if (video.error) onError();
                }
                observeLayout();
                timer = setInterval(() => {
                    if (failed) return;
                    const now = performance.now();
                    const video = document.querySelector('video');
                    if (!video) {
                        if (boundVideo && now - lastAdvance > timeout) fail('PLAYBACK_INTERRUPTED');
                        return;
                    }
                    if (boundVideo !== video) bind(video, now);
                    if (failed) return;
                    // A live stream can reset its media timeline. Compare changes, not a high-water mark.
                    if (lastPosition !== null && video.currentTime !== lastPosition
                        && video.readyState >= 2 && !video.paused) {
                        lastAdvance = now;
                        ready(video);
                        buffering(false);
                    }
                    lastPosition = video.currentTime;
                    if (now - lastAdvance > timeout) {
                        fail(isReady ? 'PLAYBACK_INTERRUPTED' : 'LOAD_TIMEOUT');
                        return;
                    }
                    if (isReady && now - lastAdvance > 1000) buffering(true);
                    fixLayout(video, now);
                    if (video.paused && !playPending && now - lastPlayAttempt >= 2000) {
                        lastPlayAttempt = now;
                        playPending = true;
                        Promise.resolve(video.play()).then(() => {
                            if (boundVideo === video) playPending = false;
                        }, error => {
                            if (boundVideo !== video) return;
                            playPending = false;
                            if (error.name === 'NotSupportedError') fail('WEB_VIDEO_UNSUPPORTED');
                        });
                    }
                }, 500);
            })();
            """.trimIndent()
        ) {}
    }
}

class MyWebView(context: Context) : WebView(context) {
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return false
    }
}

class MyWebViewInterface(
    private val onVideoResolutionChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
    private val onError: (String) -> Unit = {},
    private val onBuffering: (Boolean) -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var disposed = false
    private var failed = false
    private var lastBuffering: Boolean? = null

    fun dispose() {
        disposed = true
        mainHandler.removeCallbacksAndMessages(null)
    }

    @JavascriptInterface
    fun videoError(error: String) {
        mainHandler.post {
            if (!disposed && !failed) {
                failed = true
                onError(error.take(200))
            }
        }
    }

    @JavascriptInterface
    fun buffering(value: Boolean) {
        mainHandler.post {
            if (!disposed && !failed && lastBuffering != value) {
                lastBuffering = value
                onBuffering(value)
            }
        }
    }

    @JavascriptInterface
    fun changeVideoResolution(width: Int, height: Int) {
        mainHandler.post {
            if (!disposed && !failed && width > 0 && height > 0) onVideoResolutionChanged(width, height)
        }
    }
}