package top.yogiczy.mytv.tv.ui.screens.videoplayer

import android.view.SurfaceView
import android.view.TextureView
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yogiczy.mytv.tv.ui.screens.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screens.videoplayer.player.IJKVideoPlayer
import top.yogiczy.mytv.tv.ui.screens.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screens.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.utils.Configs

@Stable
class VideoPlayerState(
    private var instance: VideoPlayer,
    private val settingsViewModel: SettingsViewModel,
    private val context: android.content.Context,
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
    private var defaultDisplayModeProvider: () -> VideoPlayerDisplayMode = { VideoPlayerDisplayMode.ORIGINAL },
) {
    var currentUrl: String? = null
        private set

    private var currentSurface: Any? = null
    private var currentTexture: Any? = null

    /** 显示模式 */
    var displayMode by mutableStateOf(defaultDisplayModeProvider())

    /** 视频宽高比 */
    var aspectRatio by mutableFloatStateOf(16f / 9f)

    /** 错误 */
    var error by mutableStateOf<String?>(null)

    /** 正在缓冲 */
    var isBuffering by mutableStateOf(false)

    /** 正在播放 */
    var isPlaying by mutableStateOf(false)

    /** 总时长 */
    var duration by mutableLongStateOf(0L)

    /** 当前播放位置 */
    var currentPosition by mutableLongStateOf(0L)

    /** 元数据 */
    var metadata by mutableStateOf(VideoPlayer.Metadata())

    private var acceptingEvents = false
    private var requestGeneration = 0
    private var isAutoDisplayModeSet = false
    private var instanceType = when (instance) {
        is IJKVideoPlayer -> Configs.VideoPlayerType.IJK
        is Media3VideoPlayer -> Configs.VideoPlayerType.MEDIA3
        else -> Configs.videoPlayerType
    }
    private var needsSurfaceBinding = false
    private var userPaused = false
    private var pendingResumePosition: Long? = null

    private fun bindCurrentSurface() {
        if (!needsSurfaceBinding) return
        needsSurfaceBinding = false
        (currentSurface as? SurfaceView)?.let(instance::setVideoSurfaceView)
        (currentTexture as? TextureView)?.let(instance::setVideoTextureView)
    }

    fun prepare(url: String) {
        userPaused = false
        pendingResumePosition = null
        bindCurrentSurface()
        requestGeneration++
        acceptingEvents = true
        currentUrl = url
        error = null
        isAutoDisplayModeSet = false
        instance.prepare(url)
    }

    fun play() {
        userPaused = false
        val resumeAt = pendingResumePosition
        val url = currentUrl
        if (resumeAt != null && url != null) {
            prepare(url)
            instance.seekTo(resumeAt)
        }
        instance.play()
    }

    fun pause() {
        userPaused = true
        instance.pause()
    }

    fun seekTo(position: Long) {
        if (pendingResumePosition != null) {
            pendingResumePosition = position
            currentPosition = position
        } else instance.seekTo(position)
    }

    fun stop() {
        userPaused = false
        pendingResumePosition = null
        requestGeneration++
        acceptingEvents = false
        currentUrl = null
        error = null
        isBuffering = false
        isPlaying = false
        instance.stop()
    }

    fun setVideoSurfaceView(surfaceView: SurfaceView) {
        currentSurface = surfaceView
        currentTexture = null
        needsSurfaceBinding = false
        instance.setVideoSurfaceView(surfaceView)
    }

    fun setVideoTextureView(textureView: TextureView) {
        currentTexture = textureView
        currentSurface = null
        needsSurfaceBinding = false
        instance.setVideoTextureView(textureView)
    }

    private val onReadyListeners = mutableListOf<() -> Unit>()
    private val onErrorListeners = mutableListOf<() -> Unit>()
    private val onPlayingListeners = mutableListOf<(Boolean) -> Unit>()
    private val onBufferingListeners = mutableListOf<(Boolean) -> Unit>()
    private val onInterruptListeners = mutableListOf<() -> Unit>()

    fun onReady(listener: () -> Unit) {
        onReadyListeners.add(listener)
    }

    fun onError(listener: () -> Unit) {
        onErrorListeners.add(listener)
    }

    fun onPlayingChanged(listener: (Boolean) -> Unit) {
        onPlayingListeners.add(listener)
    }

    fun onBuffering(listener: (Boolean) -> Unit) {
        onBufferingListeners.add(listener)
    }

    fun onInterrupt(listener: () -> Unit) {
        onInterruptListeners.add(listener)
    }

    fun initialize() {
        instance.initialize()
        instance.onResolution { width, height ->
            if (width > 0 && height > 0) {
                aspectRatio = width.toFloat() / height

                if (settingsViewModel.videoPlayerAutoFillForSD && width == 720 && height == 576) {
                    displayMode = VideoPlayerDisplayMode.FILL
                    isAutoDisplayModeSet = true
                }
            }
        }
        
        // 监听播放器类型变化
        settingsViewModel.videoPlayerTypeValue = Configs.videoPlayerType
        settingsViewModel.onVideoPlayerTypeChanged = { type ->
            if (type != instanceType) {
                val url = currentUrl
                val paused = userPaused
                val position = pendingResumePosition ?: currentPosition
                requestGeneration++
                acceptingEvents = false
                instance.release()
                instance = when (type) {
                    Configs.VideoPlayerType.IJK -> IJKVideoPlayer(context, coroutineScope)
                    Configs.VideoPlayerType.MEDIA3 -> Media3VideoPlayer(context, coroutineScope)
                }
                instanceType = type
                needsSurfaceBinding = true
                initialize()

                if (url != null) {
                    if (paused) {
                        // Keep the user's pause; prepare the new backend only when play is requested.
                        pendingResumePosition = position
                        isPlaying = false
                        isBuffering = false
                    } else {
                        prepare(url)
                        seekTo(position)
                    }
                }
            }
        }
        instance.onError { ex ->
            if (acceptingEvents && ex != null) {
                val generation = requestGeneration
                error = "${ex.errorCodeName}(${ex.errorCode})"
                acceptingEvents = false
                coroutineScope.launch {
                    yield()
                    if (generation == requestGeneration) onErrorListeners.forEach { it.invoke() }
                }
            }
        }

        instance.onReady {
            if (!acceptingEvents) return@onReady
            onReadyListeners.forEach { it.invoke() }
            error = null
            if (!isAutoDisplayModeSet) {
                displayMode = defaultDisplayModeProvider()
            }
        }
        instance.onBuffering {
            if (!acceptingEvents) return@onBuffering
            val changed = isBuffering != it
            isBuffering = it
            if (changed) onBufferingListeners.forEach { listener -> listener(it) }
            if (it) error = null
        }
        instance.onPrepared { }
        instance.onIsPlayingChanged {
            if (acceptingEvents) {
                val changed = isPlaying != it
                isPlaying = it
                if (changed) onPlayingListeners.forEach { listener -> listener(it) }
            }
        }
        instance.onDurationChanged { duration = it }
        instance.onCurrentPositionChanged { currentPosition = it }
        instance.onMetadata { metadata = it }
        instance.onInterrupt { if (acceptingEvents) onInterruptListeners.forEach { it.invoke() } }
    }

    fun release() {
        onReadyListeners.clear()
        onErrorListeners.clear()
        onInterruptListeners.clear()
        onBufferingListeners.clear()
        onPlayingListeners.clear()
        instance.release()
    }
}

@Composable
fun rememberVideoPlayerState(
    defaultDisplayModeProvider: () -> VideoPlayerDisplayMode = { VideoPlayerDisplayMode.ORIGINAL },
    settingsViewModel: SettingsViewModel = viewModel(),
): VideoPlayerState {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val state = remember {
        val initialPlayer = when (Configs.videoPlayerType) {
            Configs.VideoPlayerType.IJK -> IJKVideoPlayer(context, coroutineScope)
            Configs.VideoPlayerType.MEDIA3 -> Media3VideoPlayer(context, coroutineScope)
        }
        VideoPlayerState(
            initialPlayer,
            settingsViewModel,
            context,
            coroutineScope,
            defaultDisplayModeProvider,
        )
    }

    DisposableEffect(Unit) {
        state.initialize()
        onDispose { state.release() }
    }

    return state
}

enum class VideoPlayerDisplayMode(
    val label: String,
    val value: Int,
) {
    /** 原始 */
    ORIGINAL("原始", 0),

    /** 填充 */
    FILL("填充", 1),

    /** 裁剪 */
    CROP("裁剪", 2),

    /** 4:3 */
    FOUR_THREE("4:3", 3),

    /** 16:9 */
    SIXTEEN_NINE("16:9", 4),

    /** 2.35:1 */
    WIDE("2.35:1", 5);

    companion object {
        fun fromValue(value: Int): VideoPlayerDisplayMode {
            return entries.firstOrNull { it.value == value } ?: ORIGINAL
        }
    }
}