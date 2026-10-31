package top.yogiczy.mytv.tv.ui.screens.channelurl.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.yogiczy.mytv.core.data.network.await
import okhttp3.Request
import top.yogiczy.mytv.core.data.network.OkHttp
import top.yogiczy.mytv.core.data.utils.ChannelUtil
import top.yogiczy.mytv.core.util.utils.isIPv6
import top.yogiczy.mytv.tv.ui.material.Tag
import top.yogiczy.mytv.tv.ui.theme.MyTVTheme
import top.yogiczy.mytv.tv.ui.utils.focusOnLaunchedSaveable
import top.yogiczy.mytv.tv.ui.utils.handleKeyEvents
import top.yogiczy.mytv.tv.ui.utils.ifElse

@Composable
fun ChannelUrlItem(
    modifier: Modifier = Modifier,
    urlProvider: () -> String = { "" },
    urlIdxProvider: () -> Int = { 0 },
    isSelectedProvider: () -> Boolean = { false },
    onSelected: () -> Unit = {},
) {
    val url = urlProvider()
    val urlIdx = urlIdxProvider()
    val isSelected = isSelectedProvider()

    val urlDelay = rememberUrlDelay(url)

    ListItem(
        modifier = modifier
            .ifElse(isSelected, Modifier.focusOnLaunchedSaveable())
            .handleKeyEvents(onSelect = onSelected),
        selected = false,
        onClick = {},
        headlineContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("线路${urlIdx + 1}")

                if (ChannelUtil.isHybridWebViewUrl(url)) {
                    Tag("混合")
                    Tag(ChannelUtil.getHybridWebViewUrlProvider(url))
                } else {
                    if (ChannelUtil.urlSupportPlayback(url)) Tag("回放")
                    Tag(if (url.isIPv6()) "IPV6" else "IPV4")
                    if (urlDelay != 0L) Tag("$urlDelay ms")
                }
            }
        },
        supportingContent = { Text(url, maxLines = 1) },
        trailingContent = {
            RadioButton(selected = isSelected, onClick = {})
        },
    )
}

private val probePermits = Semaphore(3)
private val probeCache = object : LinkedHashMap<String, Pair<Long, Long>>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Long>>?) = size > 128
}

@Composable
private fun rememberUrlDelay(url: String): Long {
    var elapsedTime by remember(url) { mutableLongStateOf(0) }
    LaunchedEffect(url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@LaunchedEffect
        probePermits.withPermit {
            val now = android.os.SystemClock.elapsedRealtime()
            val cached = probeCache[url]
            if (cached != null && now - cached.first < 60_000L) {
                elapsedTime = cached.second
                return@withPermit
            }
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                elapsedTime = withContext(Dispatchers.IO) {
                    OkHttp.client.newCall(Request.Builder().url(url).build()).await().use { response ->
                        if (response.isSuccessful)
                            android.os.SystemClock.elapsedRealtime() - started else 0L
                    }
                }
                probeCache[url] = android.os.SystemClock.elapsedRealtime() to elapsedTime
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                probeCache[url] = android.os.SystemClock.elapsedRealtime() to 0L
            }
        }
    }
    return elapsedTime
}

@Preview
@Composable
private fun ChannelUrlItemPreview() {
    MyTVTheme {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ChannelUrlItem(
                urlProvider = { "http://dbiptv.sn.chinamobile.com/PLTV/88888890/224/3221226231/index.m3u8" },
                urlIdxProvider = { 0 },
                isSelectedProvider = { true },
            )

            ChannelUrlItem(
                urlProvider = { "http://[2409:8087:5e01:34::20]:6610/ZTE_CMS/00000001000000060000000000000131/index.m3u8?IAS" },
                urlIdxProvider = { 0 },
            )

            ChannelUrlItem(
                urlProvider = { ChannelUtil.getHybridWebViewUrl("cctv1")!!.first() },
                urlIdxProvider = { 0 },
                isSelectedProvider = { true },
            )
        }
    }
}