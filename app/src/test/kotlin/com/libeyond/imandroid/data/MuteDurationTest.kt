package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 时长菜单 → 绝对 `mute_until`（NOTIFICATIONS_P1_DESIGN §4.1）：5 项逐一钉住偏移量与「永久=0」。 */
class MuteDurationTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `1 小时`() {
        assertEquals(now + 60 * 60 * 1000L, MuteDuration.OneHour.muteUntil(now))
    }

    @Test
    fun `8 小时`() {
        assertEquals(now + 8 * 60 * 60 * 1000L, MuteDuration.EightHours.muteUntil(now))
    }

    @Test
    fun `1 天`() {
        assertEquals(now + 24 * 60 * 60 * 1000L, MuteDuration.OneDay.muteUntil(now))
    }

    @Test
    fun `7 天`() {
        assertEquals(now + 7 * 24 * 60 * 60 * 1000L, MuteDuration.SevenDays.muteUntil(now))
    }

    @Test
    fun `永久是 0，不是 now 加任何偏移`() {
        assertEquals(0L, MuteDuration.Forever.muteUntil(now))
    }

    @Test
    fun `五个选项互不相同——不许两项算出同一个偏移`() {
        val values = MuteDuration.entries.map { it.muteUntil(now) }
        assertEquals("有重复的 mute_until", values.size, values.toSet().size)
    }
}
