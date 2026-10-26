package top.yogiczy.mytv.tv.ui.screens.playback

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import top.yogiczy.mytv.core.data.utils.Logger
import top.yogiczy.mytv.tv.ui.rememberChildPadding
import top.yogiczy.mytv.tv.ui.screens.settings.components.SettingsCategoryLog
import top.yogiczy.mytv.tv.ui.screens.settings.components.SettingsListItem
import top.yogiczy.mytv.tv.ui.utils.customBackground
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunched
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun PlaybackStatus(
    session: PlaybackSession,
    requestId: Int,
    buffering: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    allowFocus: Boolean = true,
) {
    // Startup buffering may toggle during an internal retry. Keep one continuous
    // loading indicator; only a new request or a playback interruption starts a new delay.
    val isRebuffering = session.phase == PlaybackPhase.PLAYING && buffering
    var delayed by remember(requestId, isRebuffering) { mutableStateOf(false) }
    LaunchedEffect(requestId, isRebuffering) {
        kotlinx.coroutines.delay(800)
        delayed = true
    }
    val failed = session.phase == PlaybackPhase.FAILED
    val switching = session.phase == PlaybackPhase.SWITCHING
    if (session.phase != PlaybackPhase.PLAYING) Box(modifier.fillMaxSize().background(Color.Black))
    if (!failed && !switching && (!delayed || (session.phase == PlaybackPhase.PLAYING && !buffering))) return
    var showLogs by remember(requestId) { mutableStateOf(false) }
    val retryFocus = remember { FocusRequester() }
    val logFocus = remember { FocusRequester() }
    LaunchedEffect(failed, allowFocus) {
        if (failed && allowFocus) {
            // Wait for the action to attach before requesting initial focus.
            withFrameNanos { }
            retryFocus.requestFocus()
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!failed) PlaybackSpinner()
            Text(
                when {
                    failed -> "频道播放失败"
                    switching -> "正在切换线路 ${session.currentIndex + 1}/${session.urls.size}"
                    session.phase == PlaybackPhase.PLAYING -> "正在重新连接"
                    else -> "正在加载节目"
                },
                color = Color.White, fontSize = 28.sp,
                textAlign = TextAlign.Center,
            )
            val subtitle = when {
                failed -> if (session.urls.size == 1) "当前线路不可用" else "全部线路不可用"
                switching -> session.failures.lastOrNull()?.let {
                    "线路 ${it.index + 1}：${playbackErrorMessage(it.error)}"
                }
                else -> null
            }
            subtitle?.let {
                Text(it, color = Color.White.copy(alpha = 0.65f), fontSize = 17.sp,
                    textAlign = TextAlign.Center, maxLines = 2)
            }
            if (failed) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PlaybackFailureButton("重试", onRetry, Modifier.focusRequester(retryFocus))
                    PlaybackFailureButton("日志", { showLogs = true }, Modifier.focusRequester(logFocus))
                }
            }
        }
    }
    if (showLogs) {
        PlaybackLogDialog(session, onDismiss = {
            showLogs = false
            logFocus.requestFocus()
        })
    }
}

@Composable
private fun PlaybackSpinner() {
    val transition = rememberInfiniteTransition(label = "playback-loading")
    val angle by transition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "rotation")
    Canvas(Modifier.size(28.dp)) {
        drawArc(Color.White.copy(alpha = 0.75f), angle, 260f, false,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun PlaybackLogDialog(session: PlaybackSession, onDismiss: () -> Unit) {
    val history = remember(session.failures) {
        session.failures.map { failure ->
            Logger.HistoryItem(
                level = Logger.LevelType.ERROR,
                tag = "线路 ${failure.index + 1} · ${playbackErrorMessage(failure.error)}",
                message = "${logAddress(failure.url)}\n${failure.error.take(1200)}",
                time = failure.time,
            )
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val childPadding = rememberChildPadding()
        BackHandler(onBack = onDismiss)
        Column(
            Modifier.fillMaxSize().customBackground().padding(childPadding.paddingValues),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("线路播放日志", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                PlaybackAction("关闭", onDismiss, Modifier.width(112.dp).then(
                    if (history.isEmpty()) Modifier.focusOnLaunched() else Modifier,
                ))
            }
            if (history.isEmpty()) {
                Text("没有可用播放地址", style = MaterialTheme.typography.bodyMedium)
            } else {
                // Share the exact row, timestamps, spacing and scrolling used by Settings > Logs.
                SettingsCategoryLog(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    history = history,
                    requestInitialFocus = true,
                )
            }
        }
    }
}

@Composable
private fun PlaybackAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SettingsListItem(
        modifier = modifier,
        headlineContent = text,
        onSelected = onClick,
    )
}

@Composable
private fun PlaybackFailureButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Button(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape),
        shape = ButtonDefaults.shape(shape = shape),
        colors = ButtonDefaults.colors(
            containerColor = if (focused) Color.White else Color(0xFF303333),
            contentColor = if (focused) Color.Black else Color.White.copy(alpha = 0.85f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) { Text(text) }
}
