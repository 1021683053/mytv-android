package top.yogiczy.mytv.tv.ui.screens.playback

/** One bounded round of attempts. Indices refer to the captured channel URL list. */
data class PlaybackSession(
    val urls: List<String> = emptyList(),
    val currentIndex: Int = 0,
    val phase: PlaybackPhase = PlaybackPhase.LOADING,
    val failures: List<PlaybackFailure> = emptyList(),
) {
    fun fail(error: String): PlaybackSession {
        if (urls.isEmpty()) return copy(phase = PlaybackPhase.FAILED)
        if (phase == PlaybackPhase.FAILED || currentIndex in failures.map { it.index }) return this
        val records = failures + PlaybackFailure(currentIndex, urls.getOrElse(currentIndex) { "" }, error)
        val next = (1..urls.size).map { (currentIndex + it) % urls.size }
            .firstOrNull { index -> records.none { it.index == index } }
        return copy(
            currentIndex = next ?: currentIndex,
            phase = if (next == null) PlaybackPhase.FAILED else PlaybackPhase.SWITCHING,
            failures = records,
        )
    }

    fun ready() = copy(phase = PlaybackPhase.PLAYING)

    // A short-lived first frame must not erase the bounded recovery round.
    fun stable() = copy(failures = emptyList())
}

enum class PlaybackPhase { LOADING, SWITCHING, PLAYING, FAILED }

data class PlaybackFailure(
    val index: Int,
    val url: String,
    val error: String,
    val time: Long = System.currentTimeMillis(),
)

fun playbackErrorMessage(error: String): String = when {
    error.contains("TIMEOUT", true) -> "连接超时"
    error.contains("NETWORK_CONNECTION_FAILED") || error.startsWith("WEB_CONNECTION") -> "无法连接播放服务器"
    error.contains("BAD_HTTP_STATUS") || error.startsWith("HTTP_") -> "播放地址暂时无法访问"
    error.contains("FILE_NOT_FOUND") -> "播放地址不存在"
    error.contains("SSL", true) -> "播放连接验证失败"
    error.contains("DECOD", true) -> "当前设备无法解码此节目"
    error.contains("UNSUPPORTED", true) -> "不支持此节目的播放格式"
    error.contains("WEB_VIDEO") -> "节目视频加载失败"
    error.contains("INTERRUPT") -> "播放连接中断"
    else -> "播放请求失败"
}

// Avoid displaying credentials and signed URL query strings in the diagnostics panel.
internal fun logAddress(url: String): String = runCatching {
    val uri = java.net.URI(url.substringAfter("webview://", url))
    java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
}.getOrDefault("播放地址")
