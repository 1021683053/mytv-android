package top.yogiczy.mytv.tv

import android.app.Application
import android.util.Log
import androidx.media3.common.util.Log as Media3Log
import androidx.media3.common.util.UnstableApi
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.Decoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.Logger
import top.yogiczy.mytv.core.data.AppData
import top.yogiczy.mytv.core.data.network.OkHttp
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.util.utils.LogGate
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.pragma.DebugLog

class MyTVApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        LogGate.addListener { enabled -> applyPlayerLog(enabled) }
        AppData.init(applicationContext)
        UnsafeTrustManager.enableUnsafeTrustManager()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .logger(SwitchCoilLogger())
            .components {
                    add(ImageDecoderDecoder.Factory() as Decoder.Factory)
            }
            .okHttpClient(OkHttp.client)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    // 升级保留：使用 filesDir 下的 cache（AppData.init 已配置并自动从旧 cacheDir 迁移）
                    .directory(Globals.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.1) // 增加到 10%
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()
    }
}

@OptIn(UnstableApi::class)
private fun applyPlayerLog(enabled: Boolean) {
    DebugLog.setEnabled(enabled)
    Media3Log.setLogLevel(if (enabled) Media3Log.LOG_LEVEL_ALL else Media3Log.LOG_LEVEL_OFF)
    runCatching {
        IjkMediaPlayer.loadLibrariesOnce(null)
        IjkMediaPlayer.native_setLogLevel(
            if (enabled) IjkMediaPlayer.IJK_LOG_DEBUG else IjkMediaPlayer.IJK_LOG_SILENT
        )
    }
}

private class SwitchCoilLogger : Logger {
    override var level: Int = Log.VERBOSE

    override fun log(tag: String, priority: Int, message: String?, throwable: Throwable?) {
        if (!LogGate.enabled || priority < level) return
        if (message != null) {
            Log.println(priority, tag, message)
        }
        if (throwable != null) {
            Log.println(priority, tag, Log.getStackTraceString(throwable))
        }
    }
}
