package top.yogiczy.mytv.core.data.repositories.epg

import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import top.yogiczy.mytv.core.data.entities.epg.Epg
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeList
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.network.OkHttp
import top.yogiczy.mytv.core.data.repositories.FileCacheRepository
import top.yogiczy.mytv.core.data.repositories.epg.fetcher.EpgFetcher.Companion.fetchStream
import top.yogiczy.mytv.core.data.utils.ChannelUtil
import top.yogiczy.mytv.core.data.utils.Constants
import top.yogiczy.mytv.core.data.utils.Logger
import top.yogiczy.mytv.core.data.utils.SP
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 节目单获取
 */
class EpgRepository(
    private val source: EpgSource,
) {
    companion object {
        // Bound memory to the most recently requested source/filter combination.
        private val refreshMutex = Mutex()
        private var parsedKey: List<Any>? = null
        private var parsedValue: EpgList? = null
    }

    private val log = Logger.create(EpgRepository::class.java.simpleName)
    private val epgXmlRepository = EpgXmlRepository(source.url)

    /**
     * 解析节目单xml
     */
    private suspend fun parseFromXml(
        inputStream: InputStream,
        filteredChannels: Set<String> = emptySet(),
    ) = withContext(Dispatchers.Default) {
        val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(inputStream, "UTF-8")
        }

        val channelNameMap = mutableMapOf<String, String>()
        val programmeMap = mutableMapOf<String, MutableList<EpgProgramme>>()
        val intervals = mutableMapOf<String, ProgrammeIntervalIndex>()
        val context = currentCoroutineContext()
        val lowerFilteredChannels = filteredChannels.map { it.lowercase() }.toSet()

        fun getSafeText(): String {
            return try {
                parser.nextText().trim()
            } catch (e: Exception) {
                ""
            }
        }

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            context.ensureActive()
            try {
                if (parser.eventType != XmlPullParser.START_TAG) continue

                when (parser.name) {
                    "channel" -> {
                        val id = parser.getAttributeValue(null, "id")
                        if (id == null) {
                            skip(parser)
                            continue
                        }

                        var name = ""
                        val depth = parser.depth
                        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
                            context.ensureActive()
                            check(parser.eventType != XmlPullParser.END_DOCUMENT) { "Unexpected end of EPG XML" }
                            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "display-name") {
                                name = ChannelUtil.standardChannelName(getSafeText())
                            }
                        }

                        if (name.isNotEmpty() && (lowerFilteredChannels.isEmpty() || name.lowercase() in lowerFilteredChannels)) {
                            channelNameMap[id] = name
                        }
                    }

                    "programme" -> {
                        val channelId = parser.getAttributeValue(null, "channel")
                        val startTime = parser.getAttributeValue(null, "start")
                        val stopTime = parser.getAttributeValue(null, "stop")

                        if (channelId == null || !channelNameMap.containsKey(channelId)) {
                            skip(parser)
                            continue
                        }

                        var title = ""
                        val depth = parser.depth
                        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
                            context.ensureActive()
                            check(parser.eventType != XmlPullParser.END_DOCUMENT) { "Unexpected end of EPG XML" }
                            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "title") {
                                title = getSafeText()
                            }
                        }

                        if (title.isNotEmpty()) {
                            val startAt = try { dateFormat.parse(startTime)?.time ?: 0L } catch (e: Exception) { 0L }
                            val endAt = try { dateFormat.parse(stopTime)?.time ?: 0L } catch (e: Exception) { 0L }

                            if (startAt != 0L && endAt > startAt) {
                                val programmes = programmeMap.getOrPut(channelId) { mutableListOf() }

                                val index = intervals.getOrPut(channelId) { ProgrammeIntervalIndex() }
                                if (index.add(startAt, endAt)) {
                                    programmes.add(EpgProgramme(startAt = startAt, endAt = endAt, title = title))
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e("解析XML标签失败", e)
                continue
            }
        }

        val epgList = channelNameMap.map { (id, name) ->
            Epg(name, EpgProgrammeList(programmeMap[id] ?: emptyList()))
        }

        log.i("解析节目单完成，共${epgList.size}个频道，${epgList.sumOf { it.programmeList.size }}个节目")
        EpgList(epgList)
    }

    private suspend fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) {
            return
        }
        var depth = 1
        while (depth != 0) {
            currentCoroutineContext().ensureActive()
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> error("Unexpected end of EPG XML")
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }

    suspend fun getEpgList(
        filteredChannels: List<String> = emptyList(),
        refreshTimeThreshold: Int,
    ): EpgList = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            try {
                val xmlFile = epgXmlRepository.getEpgXmlFile()
                val channels = filteredChannels.toSet()
                val key = listOf(source.url, xmlFile.lastModified(), xmlFile.length(), channels)
                if (key == parsedKey) return@withLock parsedValue!!
                val parsed = FileInputStream(xmlFile).buffered().use { parseFromXml(it, channels) }
                parsedKey = key
                parsedValue = parsed
                parsed
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                log.e("获取节目单失败", ex)
                EpgList()
            }
        }
    }

    suspend fun clearCache() = refreshMutex.withLock {
        parsedKey = null
        parsedValue = null
        epgXmlRepository.clearCache()
    }

}

/**
 * 节目单xml获取
 */
private class EpgXmlRepository(
    private val url: String
) : FileCacheRepository("epg-${url.hashCode().toUInt().toString(16)}.xml") {
    private val log = Logger.create(EpgXmlRepository::class.java.simpleName)
    private val epgClient = OkHttp.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val etagKey = "epg_etag_${url.hashCode().toUInt().toString(16)}"
    private var etag: String
        get() = SP.getString(etagKey, "")
        set(value) = SP.putString(etagKey, value)

    /**
     * 获取并缓存远程xml
     */
    private suspend fun downloadAndCacheXml(file: File) = withContext(Dispatchers.IO) {
        var retryCount = 0
        while (retryCount <= Constants.HTTP_RETRY_COUNT) {
            try {
                log.i("下载节目单xml (第 ${retryCount + 1}/${Constants.HTTP_RETRY_COUNT + 1} 次): $url")
                val request = Request.Builder().url(url).apply {
                    if (etag.isNotEmpty() && file.exists()) {
                        header("If-None-Match", etag)
                    }
                }.build()

                epgClient.newCall(request).execute().use { response ->
                    if (response.code == 304) {
                        log.i("节目单内容未变动 (304 Not Modified): $url")
                        file.setLastModified(System.currentTimeMillis())
                        return@withContext
                    }

                    if (!response.isSuccessful) throw Exception("${response.code}: ${response.message}")

                    val temporary = File.createTempFile("epg-", ".tmp", file.parentFile)
                    try {
                        response.fetchStream().use { inputStream ->
                            temporary.outputStream().use { outputStream ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = inputStream.read(buffer)
                                    if (count < 0) break
                                    outputStream.write(buffer, 0, count)
                                }
                            }
                        }
                        check(temporary.renameTo(file)) { "Cannot replace EPG cache" }
                        etag = response.header("ETag") ?: ""
                    } finally {
                        temporary.delete()
                    }
                }
                return@withContext // 成功下载并缓存，跳出循环
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                retryCount++
                if (retryCount > Constants.HTTP_RETRY_COUNT) {
                    log.e("下载节目单xml最终失败: $url", ex)
                    throw ex
                }
                log.w("下载节目单xml失败 (${ex.message}), 准备第 ${retryCount + 1} 次重试...")
                delay(Constants.HTTP_RETRY_INTERVAL)
            }
        }
    }

    /**
     * 获取缓存文件，如果过期则重新下载
     */
    suspend fun getEpgXmlFile(): File = withContext(Dispatchers.IO) {
        val file = getCacheFile()
        
        val isExpired = !file.exists() || 
                System.currentTimeMillis() - file.lastModified() >= 30 * 60 * 1000

        if (isExpired) {
            try {
                downloadAndCacheXml(file)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                if (!file.exists()) throw ex
                log.w("下载节目单失败，使用过期缓存: ${ex.message}")
            }
        }
        file
    }
}
