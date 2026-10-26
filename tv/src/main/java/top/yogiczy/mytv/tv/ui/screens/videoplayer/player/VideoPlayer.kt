package top.yogiczy.mytv.tv.ui.screens.videoplayer.player

import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import top.yogiczy.mytv.tv.ui.utils.Configs

abstract class VideoPlayer(
    private val coroutineScope: CoroutineScope,
) {
    protected var metadata = Metadata()
    @Volatile protected var requestId = 0
        private set
    @Volatile private var active = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameReady = false
    private var progressWatchPaused = false
    private var currentPosition: Long? = null

    protected fun isCurrentRequest(id: Int) = active && id == requestId

    protected fun beginPlaybackRequest(): Int {
        requestId++
        active = true
        progressWatchPaused = false
        loadTimeoutJob?.cancel()
        interruptJob?.cancel()
        frameReady = false
        currentPosition = null
        return requestId
    }

    // Capture the origin before posting: an old IO callback cannot become a new UI event.
    private fun dispatch(action: () -> Unit) {
        requestEvent(requestId, action)
    }

    protected fun suspendProgressWatch() {
        progressWatchPaused = true
        interruptJob?.cancel()
    }

    protected fun resumeProgressWatch() {
        progressWatchPaused = false
        if (active && frameReady) watchForInterruption()
    }

    protected fun requestEvent(origin: Int, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (isCurrentRequest(origin)) action()
        } else {
            mainHandler.post { if (isCurrentRequest(origin)) action() }
        }
    }

    private fun watchForInterruption() {
        if (progressWatchPaused) return
        val origin = requestId
        interruptJob?.cancel()
        interruptJob = coroutineScope.launch {
            delay(Configs.videoPlayerLoadTimeout)
            if (isCurrentRequest(origin)) onInterruptListeners.forEach { it() }
        }
    }

    open fun initialize() {
        clearAllListeners()
    }

    open fun release() {
        active = false
        requestId++
        mainHandler.removeCallbacksAndMessages(null)
        loadTimeoutJob?.cancel()
        interruptJob?.cancel()
        clearAllListeners()
    }

    abstract fun prepare(url: String)

    abstract fun play()

    abstract fun pause()

    abstract fun seekTo(position: Long)

    open fun stop() {
        active = false
        requestId++
        mainHandler.removeCallbacksAndMessages(null)
        loadTimeoutJob?.cancel()
        interruptJob?.cancel()
        currentPosition = null
        frameReady = false
    }

    abstract fun setVideoSurfaceView(surfaceView: SurfaceView)
    abstract fun setVideoTextureView(textureView: TextureView)

    private var loadTimeoutJob: Job? = null
    private var interruptJob: Job? = null

    private val onResolutionListeners = mutableListOf<(width: Int, height: Int) -> Unit>()
    private val onErrorListeners = mutableListOf<(error: PlaybackException?) -> Unit>()
    private val onReadyListeners = mutableListOf<() -> Unit>()
    private val onBufferingListeners = mutableListOf<(buffering: Boolean) -> Unit>()
    private val onPreparedListeners = mutableListOf<() -> Unit>()

    private val onIsPlayingChanged = mutableListOf<(isPlaying: Boolean) -> Unit>()
    private val onDurationChanged = mutableListOf<(duration: Long) -> Unit>()
    private val onCurrentPositionChanged = mutableListOf<(position: Long) -> Unit>()
    private val onMetadataListeners = mutableListOf<(metadata: Metadata) -> Unit>()
    private val onInterruptListeners = mutableListOf<() -> Unit>()

    private fun clearAllListeners() {
        onResolutionListeners.clear()
        onErrorListeners.clear()
        onReadyListeners.clear()
        onBufferingListeners.clear()
        onPreparedListeners.clear()
        onIsPlayingChanged.clear()
        onDurationChanged.clear()
        onCurrentPositionChanged.clear()
        onMetadataListeners.clear()

        onInterruptListeners.clear()
    }

    protected fun triggerResolution(width: Int, height: Int) = dispatch {
        onResolutionListeners.forEach { it(width, height) }
    }

    protected fun triggerError(error: PlaybackException?) = dispatch {
        if (error != null) {
            loadTimeoutJob?.cancel()
            interruptJob?.cancel()
        }
        onErrorListeners.forEach { it(error) }
    }

    protected fun triggerReady() = dispatch {
        if (!frameReady) {
            frameReady = true
            loadTimeoutJob?.cancel()
            watchForInterruption()
            onReadyListeners.forEach { it() }
        }
    }

    protected fun triggerBuffering(buffering: Boolean) = dispatch {
        onBufferingListeners.forEach { it(buffering) }
    }

    protected fun triggerPrepared() = dispatch {
        val origin = requestId
        frameReady = false
        currentPosition = null
        interruptJob?.cancel()
        loadTimeoutJob?.cancel()
        metadata = Metadata()
        loadTimeoutJob = coroutineScope.launch {
            delay(Configs.videoPlayerLoadTimeout)
            if (isCurrentRequest(origin)) triggerError(PlaybackException.LOAD_TIMEOUT)
        }
        onPreparedListeners.forEach { it() }
    }

    protected fun triggerIsPlayingChanged(isPlaying: Boolean) = dispatch {
        onIsPlayingChanged.forEach { it(isPlaying) }
    }

    protected fun triggerDuration(duration: Long) = dispatch {
        onDurationChanged.forEach { it(duration) }
    }

    protected fun triggerMetadata(metadata: Metadata) = dispatch {
        onMetadataListeners.forEach { it(metadata) }
    }

    protected fun triggerCurrentPosition(position: Long) = dispatch {
        val previous = currentPosition
        if (!progressWatchPaused && previous != null && position != previous) {
            // Supports audio-only streams and media timelines which restart at zero.
            triggerReady()
            watchForInterruption()
        }
        currentPosition = position
        onCurrentPositionChanged.forEach { it(position) }
    }

    fun onResolution(listener: (width: Int, height: Int) -> Unit) {
        onResolutionListeners.add(listener)
    }

    fun onError(listener: (error: PlaybackException?) -> Unit) {
        onErrorListeners.add(listener)
    }

    fun onReady(listener: () -> Unit) {
        onReadyListeners.add(listener)
    }

    fun onBuffering(listener: (buffering: Boolean) -> Unit) {
        onBufferingListeners.add(listener)
    }

    fun onPrepared(listener: () -> Unit) {
        onPreparedListeners.add(listener)
    }

    fun onIsPlayingChanged(listener: (isPlaying: Boolean) -> Unit) {
        onIsPlayingChanged.add(listener)
    }

    fun onDurationChanged(listener: (duration: Long) -> Unit) {
        onDurationChanged.add(listener)
    }

    fun onCurrentPositionChanged(listener: (position: Long) -> Unit) {
        onCurrentPositionChanged.add(listener)
    }

    fun onMetadata(listener: (metadata: Metadata) -> Unit) {
        onMetadataListeners.add(listener)
    }

    fun onInterrupt(listener: () -> Unit) {
        onInterruptListeners.add(listener)
    }

    data class PlaybackException(val errorCodeName: String, val errorCode: Int) :
        Exception(errorCodeName) {
        companion object {
            val UNSUPPORTED_TYPE = PlaybackException("UNSUPPORTED_TYPE", 10002)
            val LOAD_TIMEOUT = PlaybackException("LOAD_TIMEOUT", 10003)
        }
    }

    /** 元数据 */
    data class Metadata(
        /** 视频编码 */
        val videoMimeType: String = "",
        /** 视频宽度 */
        val videoWidth: Int = 0,
        /** 视频高度 */
        val videoHeight: Int = 0,
        /** 视频颜色 */
        val videoColor: String = "",
        /** 视频帧率 */
        val videoFrameRate: Float = 0f,
        /** 视频比特率 */
        val videoBitrate: Int = 0,
        /** 视频解码器 */
        val videoDecoder: String = "",

        /** 音频编码 */
        val audioMimeType: String = "",
        /** 音频通道 */
        val audioChannels: Int = 0,
        /** 音频采样率 */
        val audioSampleRate: Int = 0,
        /** 音频解码器 */
        val audioDecoder: String = "",
    )
}