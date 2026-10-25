package top.yogiczy.mytv.core.data.entities.epg

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.epg.Epg.Companion.recentProgramme

/**
 * 频道节目单列表
 */
@Serializable
@Immutable
data class EpgList(
    val value: List<Epg> = emptyList(),
) : List<Epg> by value {
    companion object {
        private val matchCache =
            object : LinkedHashMap<String, Epg?>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Epg?>?): Boolean {
                    return size > 1024
                }
            }

        private class RecentEntry(
            val epg: Epg,
            val recent: EpgProgrammeRecent,
            val validUntil: Long,
        )

        private val recentCache =
            object : LinkedHashMap<String, RecentEntry>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RecentEntry>?): Boolean {
                    return size > 1024
                }
            }

        fun EpgList.recentProgramme(channel: Channel): EpgProgrammeRecent? {
            if (isEmpty()) return null

            val epg = match(channel) ?: return null
            val now = System.currentTimeMillis()
            synchronized(recentCache) {
                val cached = recentCache[channel.epgName]
                if (cached != null && cached.epg === epg && now < cached.validUntil) {
                    return cached.recent
                }
            }

            val recent = epg.recentProgramme()
            val validUntil = when {
                recent.now != null && recent.now.endAt > now -> recent.now.endAt
                recent.next != null && recent.next.startAt > now -> recent.next.startAt
                else -> now + 60_000L
            }
            synchronized(recentCache) {
                recentCache[channel.epgName] = RecentEntry(epg, recent, validUntil)
            }
            return recent
        }

        fun EpgList.match(channel: Channel): Epg? {
            if (isEmpty()) return null

            return matchCache.getOrPut(channel.epgName) {
                firstOrNull { epg -> epg.channel.equals(channel.epgName, ignoreCase = true) }
                    ?: Epg(channel = channel.epgName)
            }
        }

        fun clearCache() {
            matchCache.clear()
            synchronized(recentCache) {
                recentCache.clear()
            }
        }

        fun example(channelList: ChannelList): EpgList {
            return EpgList(channelList.map(Epg.Companion::example))
        }
    }
}