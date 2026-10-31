package top.yogiczy.mytv.core.data.repositories.epg

import java.util.TreeMap

/** Keeps the first accepted programme when intervals overlap, regardless of input order. */
internal class ProgrammeIntervalIndex {
    private val intervals = TreeMap<Long, Long>()

    fun add(start: Long, end: Long): Boolean {
        if (end <= start) return false
        val previous = intervals.floorEntry(start)
        val next = intervals.ceilingEntry(start)
        if (previous != null && previous.value > start) return false
        if (next != null && next.key < end) return false
        intervals[start] = end
        return true
    }
}
