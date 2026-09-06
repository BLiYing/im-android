package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线态租约模型（PROTOCOL §5.5）。
 * 服务端只推上线不推下线，下线全靠这些判据在客户端自行收敛。
 */
class PresenceTest {

    private val NOW = 1_700_000_000_000L

    @Test
    fun `租约未到期显示在线`() {
        val d = Presence.display(PresenceStatus.ONLINE, onlineUntil = NOW + 60_000, lastSeen = 0, now = NOW)
        assertTrue(d is PresenceDisplay.Online)
        assertEquals("在线", Presence.label(d, NOW))
    }

    /**
     * 核心：**status=online 但租约已过期，绝不能显示在线**。
     * 没有租约就没有到期时刻，那个「在线」再也不会被时间推翻。
     */
    @Test
    fun `租约过期不得显示在线`() {
        val d = Presence.display(PresenceStatus.ONLINE, onlineUntil = NOW - 1, lastSeen = NOW - 300_000, now = NOW)
        assertTrue("过期租约必须回落", d !is PresenceDisplay.Online)
        assertEquals("5 分钟前在线", Presence.label(d, NOW))
    }

    /** 同上：租约字段缺失（服务端没下发）时也不得显示在线。 */
    @Test
    fun `缺租约不得显示在线`() {
        val d = Presence.display(PresenceStatus.ONLINE, onlineUntil = 0, lastSeen = 0, now = NOW)
        assertTrue(d !is PresenceDisplay.Online)
        assertEquals("最近在线", Presence.label(d, NOW))
    }

    /** 时间流逝本身不触发任何回调——同一份数据，时间推移后判定必须改变。 */
    @Test
    fun `同一份数据随时间推移自行降档`() {
        val until = NOW + 10_000
        assertTrue(Presence.display(PresenceStatus.ONLINE, until, 0, NOW) is PresenceDisplay.Online)
        assertTrue(
            "过了租约就该降档——这正是客户端必须敲心跳重算的原因",
            Presence.display(PresenceStatus.ONLINE, until, 0, NOW + 20_000) !is PresenceDisplay.Online,
        )
    }

    @Test
    fun `无数据时不显示`() {
        assertTrue(Presence.display("", 0, 0, NOW) is PresenceDisplay.Hidden)
        assertEquals("", Presence.label(PresenceDisplay.Hidden, NOW))
    }

    @Test
    fun `粗档位文案`() {
        assertEquals("最近在线", Presence.label(Presence.display(PresenceStatus.RECENTLY, 0, 0, NOW), NOW))
        assertEquals("一周内在线", Presence.label(Presence.display(PresenceStatus.LAST_WEEK, 0, 0, NOW), NOW))
        assertEquals("一月内在线", Presence.label(Presence.display(PresenceStatus.LAST_MONTH, 0, 0, NOW), NOW))
        assertEquals("很久以前在线", Presence.label(Presence.display(PresenceStatus.LONG_AGO, 0, 0, NOW), NOW))
    }

    @Test
    fun `精确时间优先于粗档位`() {
        val d = Presence.display(PresenceStatus.RECENTLY, 0, lastSeen = NOW - 3_600_000, now = NOW)
        assertEquals("1 小时前在线", Presence.label(d, NOW))
    }

    /** 只在不在线时才重拉快照——在线时有租约又有帧，够用了。 */
    @Test
    fun `在线时不重拉快照`() {
        assertTrue(!Presence.needsSnapshotRefresh(PresenceDisplay.Online))
        assertTrue(Presence.needsSnapshotRefresh(PresenceDisplay.Coarse(PresenceStatus.RECENTLY)))
        assertTrue(Presence.needsSnapshotRefresh(PresenceDisplay.LastSeen(NOW)))
    }
}
