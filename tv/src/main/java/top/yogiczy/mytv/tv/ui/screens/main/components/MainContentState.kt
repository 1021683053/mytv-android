package top.yogiczy.mytv.tv.ui.screens.main.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine   // 1. 新增
import androidx.compose.ui.platform.LocalContext          // 2. 新增
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import androidx.core.content.ContextCompat
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelIdx
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeReserve
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeReserveList
import top.yogiczy.mytv.core.data.utils.ChannelUtil
import top.yogiczy.mytv.core.data.utils.Loggable
import top.yogiczy.mytv.tv.ui.screens.playback.PlaybackSession
import top.yogiczy.mytv.tv.ui.screens.playback.PlaybackPhase
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.screens.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screens.videoplayer.VideoPlayerState
import top.yogiczy.mytv.tv.ui.screens.videoplayer.rememberVideoPlayerState
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@Stable
class MainContentState(
    private val coroutineScope: CoroutineScope,
    private val videoPlayerState: VideoPlayerState,
    private val channelGroupListProvider: () -> ChannelGroupList = { ChannelGroupList() },
    private val settingsViewModel: SettingsViewModel,
    private val context: Context,
) : Loggable() {
    private var _currentChannel by mutableStateOf(Channel())
    val currentChannel get() = _currentChannel

    private var _currentChannelUrlIdx by mutableIntStateOf(0)
    val currentChannelUrlIdx get() = _currentChannelUrlIdx

    private var _currentPlaybackEpgProgramme by mutableStateOf<EpgProgramme?>(null)
    val currentPlaybackEpgProgramme get() = _currentPlaybackEpgProgramme

    private var _isTempChannelScreenVisible by mutableStateOf(false)
    var isTempChannelScreenVisible
        get() = _isTempChannelScreenVisible
        set(value) {
            _isTempChannelScreenVisible = value
        }

    private var _isChannelScreenVisible by mutableStateOf(false)
    var isChannelScreenVisible
        get() = _isChannelScreenVisible
        set(value) {
            _isChannelScreenVisible = value
        }

    private var _isSettingsScreenVisible by mutableStateOf(false)
    var isSettingsScreenVisible
        get() = _isSettingsScreenVisible
        set(value) {
            _isSettingsScreenVisible = value
        }

    private var _isVideoPlayerControllerScreenVisible by mutableStateOf(false)
    var isVideoPlayerControllerScreenVisible
        get() = _isVideoPlayerControllerScreenVisible
        set(value) {
            _isVideoPlayerControllerScreenVisible = value
        }

    private var _isQuickOpScreenVisible by mutableStateOf(false)
    var isQuickOpScreenVisible
        get() = _isQuickOpScreenVisible
        set(value) {
            _isQuickOpScreenVisible = value
        }

    private var _isEpgScreenVisible by mutableStateOf(false)
    var isEpgScreenVisible
        get() = _isEpgScreenVisible
        set(value) {
            _isEpgScreenVisible = value
        }

    private var _isChannelUrlScreenVisible by mutableStateOf(false)
    var isChannelUrlScreenVisible
        get() = _isChannelUrlScreenVisible
        set(value) {
            _isChannelUrlScreenVisible = value
        }

    private var _isVideoPlayerDisplayModeScreenVisible by mutableStateOf(false)
    var isVideoPlayerDisplayModeScreenVisible
        get() = _isVideoPlayerDisplayModeScreenVisible
        set(value) {
            _isVideoPlayerDisplayModeScreenVisible = value
        }

    var playbackSession by mutableStateOf(PlaybackSession())
        private set
    var playbackRequestId by mutableIntStateOf(0)
        private set
    var isPlaybackActive by mutableStateOf(true)
        private set
    private var playbackTimeoutJob: Job? = null
    private var stablePlaybackJob: Job? = null

    private fun isWebPlayback() = ChannelUtil.isHybridWebViewUrl(
        playbackSession.urls.getOrElse(playbackSession.currentIndex) { "" },
    )

    val hasVisibleMenu: Boolean
        get() = isChannelScreenVisible || isSettingsScreenVisible || isQuickOpScreenVisible ||
            isEpgScreenVisible || isChannelUrlScreenVisible || isVideoPlayerControllerScreenVisible ||
            isVideoPlayerDisplayModeScreenVisible

    val isProgrammePlaying: Boolean
        get() = isPlaybackActive && playbackSession.phase == PlaybackPhase.PLAYING &&
            !videoPlayerState.isBuffering && (isWebPlayback() || videoPlayerState.isPlaying)

    private fun watchStablePlayback() {
        if (stablePlaybackJob != null || playbackSession.failures.isEmpty()) return
        val origin = playbackRequestId
        stablePlaybackJob = coroutineScope.launch {
            delay(15_000L)
            if (origin == playbackRequestId && playbackSession.phase == PlaybackPhase.PLAYING
                && !videoPlayerState.isBuffering
                && (isWebPlayback() || videoPlayerState.isPlaying)) playbackSession = playbackSession.stable()
            stablePlaybackJob = null
        }
    }

    fun playbackBuffering(buffering: Boolean, requestId: Int = playbackRequestId) {
        if (!isPlaybackActive || requestId != playbackRequestId) return
        videoPlayerState.isBuffering = buffering
        if (buffering) {
            stablePlaybackJob?.cancel()
            stablePlaybackJob = null
        } else if (playbackSession.phase == PlaybackPhase.PLAYING) watchStablePlayback()
    }

    fun webPageLoading(requestId: Int) {
        if (!isPlaybackActive || requestId != playbackRequestId || playbackSession.phase != PlaybackPhase.PLAYING) return
        stablePlaybackJob?.cancel()
        stablePlaybackJob = null
        playbackSession = playbackSession.copy(phase = PlaybackPhase.LOADING)
        playbackTimeoutJob?.cancel()
        playbackTimeoutJob = coroutineScope.launch {
            delay(settingsViewModel.videoPlayerLoadTimeout)
            playbackFailed("LOAD_TIMEOUT", requestId)
        }
    }

    fun playbackReady(requestId: Int = playbackRequestId) {
        if (!isPlaybackActive || requestId != playbackRequestId || playbackSession.phase == PlaybackPhase.FAILED) return
        playbackTimeoutJob?.cancel()
        if (playbackSession.phase == PlaybackPhase.PLAYING) return
        playbackSession = playbackSession.ready()
        playbackBuffering(false)
        watchStablePlayback()
        val playingUrl = playbackSession.urls.getOrNull(playbackSession.currentIndex)
        playingUrl?.let { settingsViewModel.iptvPlayableHostList += getUrlHost(it) }
        if (playingUrl in _currentChannel.urlList) {
            settingsViewModel.setIptvChannelUrlIdx(_currentChannel.name, _currentChannelUrlIdx)
        }
    }

    fun playbackFailed(error: String, requestId: Int = playbackRequestId) {
        if (!isPlaybackActive || requestId != playbackRequestId || playbackSession.phase == PlaybackPhase.FAILED) return
        playbackTimeoutJob?.cancel()
        stablePlaybackJob?.cancel()
        stablePlaybackJob = null
        if (_currentPlaybackEpgProgramme != null) {
            // Resume live on the same line once; subsequent live failures use normal failover.
            changeCurrentChannel(_currentChannel, _currentChannelUrlIdx, force = true)
            return
        }
        settingsViewModel.iptvPlayableHostList -= getUrlHost(
            playbackSession.urls.getOrElse(playbackSession.currentIndex) { "" },
        )
        playbackSession = playbackSession.fail(error)
        if (playbackSession.phase == PlaybackPhase.FAILED) {
            // Invalidate callbacks from the failed renderer before stopping it.
            playbackRequestId++
            videoPlayerState.stop()
            _isTempChannelScreenVisible = true
        } else {
            // The subscription may have refreshed while the current round was playing.
            // Keep its attempt snapshot separate from the latest channel list and UI index.
            val nextUrl = playbackSession.urls[playbackSession.currentIndex]
            _currentChannelUrlIdx = _currentChannel.urlList.indexOf(nextUrl).coerceAtLeast(0)
            startPlaybackAttempt()
        }
    }

    fun suspendPlayback() {
        isPlaybackActive = false
        stablePlaybackJob?.cancel()
        stablePlaybackJob = null
        playbackRequestId++
        playbackTimeoutJob?.cancel()
        videoPlayerState.stop()
    }

    fun retryPlayback() {
        changeCurrentChannel(_currentChannel, 0, _currentPlaybackEpgProgramme, force = true)
    }

    private fun startPlaybackAttempt() {
        isPlaybackActive = true
        stablePlaybackJob?.cancel()
        stablePlaybackJob = null
        playbackTimeoutJob?.cancel()
        playbackRequestId++
        val requestId = playbackRequestId
        var url = playbackSession.urls.getOrNull(playbackSession.currentIndex)
        if (url == null) {
            playbackSession = playbackSession.copy(phase = PlaybackPhase.FAILED)
            videoPlayerState.stop()
            return
        }
        _currentPlaybackEpgProgramme?.let { programme ->
            val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
            val query = "playseek=${format.format(programme.startAt)}-${format.format(programme.endAt)}"
            url = if (URI(url).query.isNullOrBlank()) "$url?$query" else "$url&$query"
        }
        // Covers both native and web startup, including pages which never finish loading.
        playbackTimeoutJob = coroutineScope.launch {
            delay(settingsViewModel.videoPlayerLoadTimeout)
            playbackFailed("LOAD_TIMEOUT", requestId)
        }
        videoPlayerState.isBuffering = false
        if (isWebPlayback()) videoPlayerState.stop()
        else videoPlayerState.prepare(url!!)
    }

    init {
        /* 新增：监听 RESTART_PLAY 广播，收到后重新 prepare 当前频道 */
        coroutineScope.launch {
            suspendCancellableCoroutine<Unit> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (intent.action == "top.yogiczy.mytv.tv.RESTART_PLAY") {
                            restartCurrentChannel()
                        }
                    }
                }
                val filter = IntentFilter("top.yogiczy.mytv.tv.RESTART_PLAY")
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                cont.invokeOnCancellation { context.unregisterReceiver(receiver) }
                // 永久挂起，直到作用域被取消
            }
        }

        videoPlayerState.onReady {
            if (!isWebPlayback()) playbackReady()
        }
        videoPlayerState.onError {
            if (!isWebPlayback()) playbackFailed(videoPlayerState.error ?: "UNKNOWN")
        }
        videoPlayerState.onBuffering { if (!isWebPlayback()) playbackBuffering(it) }
        videoPlayerState.onPlayingChanged { playing ->
            if (!isWebPlayback()) {
                if (!playing) {
                    stablePlaybackJob?.cancel()
                    stablePlaybackJob = null
                } else if (playbackSession.phase == PlaybackPhase.PLAYING && !videoPlayerState.isBuffering) {
                    watchStablePlayback()
                }
            }
        }
        videoPlayerState.onInterrupt {
            if (!isWebPlayback()) playbackFailed("PLAYBACK_INTERRUPTED")
        }
        val channelGroupList = channelGroupListProvider()

        changeCurrentChannel(channelGroupList.channelList.getOrElse(settingsViewModel.iptvLastChannelIdx) {
            channelGroupList.channelList.firstOrNull() ?: Channel()
        })
    }

    private fun getPrevFavoriteChannel(): Channel? {
        if (!settingsViewModel.iptvChannelFavoriteListVisible) return null

        val channelList = channelGroupListProvider().channelList

        val favoriteChannelNameList = settingsViewModel.iptvChannelFavoriteList
        val favoriteChannelList = channelList.filter { it.name in favoriteChannelNameList }

        return if (_currentChannel in favoriteChannelList && _currentChannel != favoriteChannelList.first()) {
            val currentIdx = favoriteChannelList.indexOf(_currentChannel)
            favoriteChannelList[currentIdx - 1]
        } else if (settingsViewModel.iptvChannelFavoriteChangeBoundaryJumpOut) {
            settingsViewModel.iptvChannelFavoriteListVisible = false
            channelList.lastOrNull()
        } else {
            favoriteChannelList.lastOrNull()
        }

    }

    private fun getNextFavoriteChannel(): Channel? {
        if (!settingsViewModel.iptvChannelFavoriteListVisible) return null

        val channelList = channelGroupListProvider().channelList

        val favoriteChannelNameList = settingsViewModel.iptvChannelFavoriteList
        val favoriteChannelList = channelList.filter { it.name in favoriteChannelNameList }

        return if (_currentChannel in favoriteChannelList && _currentChannel != favoriteChannelList.last()) {
            val currentIdx = favoriteChannelList.indexOf(_currentChannel)
            favoriteChannelList[currentIdx + 1]
        } else if (settingsViewModel.iptvChannelFavoriteChangeBoundaryJumpOut) {
            settingsViewModel.iptvChannelFavoriteListVisible = false
            channelList.firstOrNull()
        } else {
            favoriteChannelList.firstOrNull()
        }
    }

    private fun getPrevChannel(): Channel {
        return getPrevFavoriteChannel() ?: run {
            val channelList = channelGroupListProvider().channelList
            if (channelList.isEmpty()) return Channel()

            val currentIdx = channelList.indexOf(_currentChannel)
            // currentIdx < 0 表示当前频道已不在列表中（如直播源刷新后残留旧对象）。
            return if (currentIdx <= 0) channelList.last() else channelList[currentIdx - 1]
        }
    }

    private fun getNextChannel(): Channel {
        return getNextFavoriteChannel() ?: run {
            val channelList = channelGroupListProvider().channelList
            if (channelList.isEmpty()) return Channel()

            val currentIdx = channelList.indexOf(_currentChannel)
            return if (currentIdx < 0 || currentIdx >= channelList.lastIndex) {
                channelList.first()
            } else {
                channelList[currentIdx + 1]
            }
        }
    }

    private fun getUrlIdx(urlList: List<String>, urlIdx: Int? = null): Int {
        val idx = if (urlIdx == null) settingsViewModel.getIptvChannelUrlIdx(_currentChannel.name)
//            urlList.indexOfFirst {
//            settingsViewModel.iptvPlayableHostList.contains(getUrlHost(it))
//        }
        else (urlIdx + urlList.size) % urlList.size

        return max(0, min(idx, urlList.size - 1))
    }

    /**
     * @param force 强制重新播放。为 true 时跳过幂等守卫，
     * 即使频道、线路、回看节目都与当前完全一致也重新 prepare。
     */
    fun changeCurrentChannel(
        channel: Channel,
        urlIdx: Int? = null,
        playbackEpgProgramme: EpgProgramme? = null,
        force: Boolean = false,
    ) {
        if (!force && channel == _currentChannel && urlIdx == _currentChannelUrlIdx && playbackEpgProgramme == _currentPlaybackEpgProgramme) return

        _isTempChannelScreenVisible = true
        _currentChannel = channel
        settingsViewModel.iptvLastChannelIdx = channelGroupListProvider().channelIdx(channel)
        _currentChannelUrlIdx = if (channel.urlList.isEmpty()) 0 else getUrlIdx(channel.urlList, urlIdx)
        _currentPlaybackEpgProgramme = playbackEpgProgramme
        playbackSession = PlaybackSession(channel.urlList.toList(), _currentChannelUrlIdx)
        startPlaybackAttempt()
    }

    /**
     * 直播源刷新后，将当前播放频道重新绑定到新列表中的对应条目（仅替换引用）。
     *
     * Channel 的相等性（data class equals）包含自增 id，而每次解析直播源都会
     * 先 IdGenerator.reset() 再从 1 重新编号。若继续沿用旧 Channel 对象，
     * channelIdx() 按 id 匹配会命中错误条目（例如服务端在头部插入频道后，
     * 原 id=1 的位置已被新频道占用），换台就会跳错。这里改用跨批次稳定的
     * name/epgName 定位。
     *
     * 正在播放的内容不做任何改动：本方法不会重新 prepare，播放器继续消费当前流，
     * 即使该线路在新列表中已被移除也一样（播不动时由 onError 自行换源）。
     *
     * 新的线路要等下次切台时才生效——届时 iptvChannelUrlIdx 已被清空，
     * getUrlIdx() 会重新从线路 0 开始挑选。这里只更新内部引用，
     * 目的是让换台时的 channelIdx() 能正确定位到相邻频道。
     *
     * @param newChannelGroupList 刷新后的完整频道列表（未经分组隐藏/省份过滤）
     */
    fun relocateCurrentChannel(newChannelGroupList: ChannelGroupList) {
        val newList = newChannelGroupList.channelList
        if (newList.isEmpty()) return

        val oldChannel = _currentChannel
        if (oldChannel.name.isBlank()) return

        val target = newList.firstOrNull { it.name == oldChannel.name }
            ?: newList.firstOrNull { it.epgName == oldChannel.epgName }
            ?: run {
                log.w("直播源已更新，但当前频道已不存在：${oldChannel.name}")
                return
            }

        if (target == oldChannel || target.urlList.isEmpty()) return

        // The active attempt keeps its own source snapshot. Only rebind the channel
        // UI index here; retry or the next explicit channel selection uses the new URLs.
        _currentChannelUrlIdx = _currentChannelUrlIdx.takeIf { it <= target.urlList.lastIndex } ?: 0
        _currentChannel = target
        settingsViewModel.iptvLastChannelIdx = channelGroupListProvider().channelIdx(target)
            .takeIf { it >= 0 }
            ?: newChannelGroupList.channelIdx(target).coerceAtLeast(0)
        log.d("直播源已更新，重新绑定当前频道：${target.name}")
    }

    /**
     * 强制重新播放当前频道。
     *
     * 用于「频道/线路/回看节目都没变、但仍需重新拉流」的场景：
     * - 应用从后台回到前台：直播流在后台仍在缓冲，回到前台后播放器继续消费
     *   后台期间累积的缓存，画面停留在离开时的旧内容，必须重新 prepare 才能拉到最新画面。
     * - RESTART_PLAY 广播。
     *
     * 直接调用 changeCurrentChannel 会被其幂等守卫拦截（三者都未变化），故需 force。
     */
    fun restartCurrentChannel() {
        log.d("强制重新播放当前频道：${_currentChannel.name}")
        changeCurrentChannel(
            _currentChannel,
            _currentChannelUrlIdx,
            _currentPlaybackEpgProgramme,
            force = true,
        )
    }

    fun changeCurrentChannelToPrev() {
        changeCurrentChannel(getPrevChannel())
    }

    fun changeCurrentChannelToNext() {
        changeCurrentChannel(getNextChannel())
    }

    fun favoriteChannelOrNot(channel: Channel) {
        if (!settingsViewModel.iptvChannelFavoriteEnable) return

        if (settingsViewModel.iptvChannelFavoriteList.contains(channel.name)) {
            settingsViewModel.iptvChannelFavoriteList -= channel.name
            Snackbar.show("取消收藏：${channel.name}")
        } else {
            settingsViewModel.iptvChannelFavoriteList += channel.name
            Snackbar.show("已收藏：${channel.name}")
        }
    }

    fun reverseEpgProgrammeOrNot(channel: Channel, programme: EpgProgramme) {
        val reverse = settingsViewModel.epgChannelReserveList.firstOrNull {
            it.test(channel, programme)
        }

        if (reverse != null) {
            settingsViewModel.epgChannelReserveList =
                EpgProgrammeReserveList(settingsViewModel.epgChannelReserveList - reverse)
            Snackbar.show("取消预约：${reverse.channel} - ${reverse.programme}")
        } else {
            val newReserve = EpgProgrammeReserve(
                channel = channel.name,
                programme = programme.title,
                startAt = programme.startAt,
                endAt = programme.endAt,
            )

            settingsViewModel.epgChannelReserveList =
                EpgProgrammeReserveList(settingsViewModel.epgChannelReserveList + newReserve)
            Snackbar.show("已预约：${channel.name} - ${programme.title}")
        }
    }

    fun supportPlayback(
        channel: Channel = _currentChannel,
        urlIdx: Int? = _currentChannelUrlIdx,
    ): Boolean {
        if (channel.urlList.isEmpty()) return false
        val currentUrlIdx = getUrlIdx(channel.urlList, urlIdx)
        return ChannelUtil.urlSupportPlayback(channel.urlList[currentUrlIdx])
    }
}

@Composable
fun rememberMainContentState(
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    videoPlayerState: VideoPlayerState = rememberVideoPlayerState(),
    channelGroupListProvider: () -> ChannelGroupList = { ChannelGroupList() },
    settingsViewModel: SettingsViewModel = viewModel(),
):MainContentState {
    val context = LocalContext.current.applicationContext  // 先在Composable体内获取context
    return remember {
        MainContentState(
            coroutineScope = coroutineScope,
            videoPlayerState = videoPlayerState,
            channelGroupListProvider = channelGroupListProvider,
            settingsViewModel = settingsViewModel,
            context = context,  // 传递context
        )
    }
}

private fun getUrlHost(url: String): String {
    return url.split("://").getOrElse(1) { "" }.split("/").firstOrNull() ?: url
}