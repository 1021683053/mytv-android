package top.yogiczy.mytv.core.data.repositories.epg

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ProgrammeIntervalIndexTest {
    @Test fun handlesAdjacentDuplicateAndEnclosingIntervals() {
        val index = ProgrammeIntervalIndex()
        assertTrue(index.add(20, 30))
        assertTrue(index.add(10, 20))
        assertTrue(index.add(30, 40))
        assertFalse(index.add(20, 30))
        assertFalse(index.add(5, 45))
        assertFalse(index.add(22, 25))
        assertFalse(index.add(50, 50))
        assertFalse(index.add(60, 50))
    }

    @Test fun preservesOriginalFirstWinsPolicyForUnsortedInput() {
        val random = Random(42)
        repeat(100) {
            val index = ProgrammeIntervalIndex()
            val accepted = mutableListOf<Pair<Long, Long>>()
            repeat(1000) {
                val start = random.nextLong(1, 100000)
                val end = start + random.nextLong(1, 500)
                val expected = accepted.none { start < it.second && end > it.first }
                assertEquals(expected, index.add(start, end))
                if (expected) accepted.add(start to end)
            }
        }
    }
}
