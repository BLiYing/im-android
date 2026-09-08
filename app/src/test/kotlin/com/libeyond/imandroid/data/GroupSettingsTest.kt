package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.GroupInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开关组的**整体替换**纪律。这里每一条对应的都是同一种事故：
 * 用户点了 A，B 被顺手关掉，而界面上 B 只是变灰了，没人会怀疑是刚才那一下。
 */
class GroupSettingsTest {

    /** 五个开关全开的群。 */
    private val allOn = GroupInfo(
        convId = "g1", myRole = "owner",
        joinApproval = true, permInvite = true, permEditInfo = true,
        permPin = true, historyVisible = true,
    )

    @Test
    fun `of 读全五个，不漏字段`() {
        assertEquals(
            GroupSettings.Values(true, true, true, true, true),
            GroupSettings.of(allOn),
        )
        assertEquals(
            GroupSettings.Values(false, false, false, false, false),
            GroupSettings.of(GroupInfo(convId = "g1")),
        )
    }

    /**
     * **本文件的核心**：翻一个，其余四个必须原样带回。
     * 逐个 key 都试一遍——漏掉哪个都是"改这一项会清掉别的项"。
     */
    @Test
    fun `翻转一个开关，其余四个原样带回`() {
        for (k in GroupSettings.Key.entries) {
            val v = GroupSettings.toggled(allOn, k)
            assertFalse("翻转的那个应该变 false：$k", GroupSettings.run { readFor(v, k) })
            val stillOn = GroupSettings.Key.entries.filter { it != k }
            for (other in stillOn) {
                assertTrue("翻 $k 时把 $other 顺手关掉了", GroupSettings.run { readFor(v, other) })
            }
        }
    }

    @Test
    fun `从全关的群翻一个只开那一个`() {
        val off = GroupInfo(convId = "g1")
        val v = GroupSettings.toggled(off, GroupSettings.Key.PermPin)
        assertEquals(GroupSettings.Values(false, false, false, true, false), v)
    }

    @Test
    fun `isOn 与 of 一致`() {
        assertTrue(GroupSettings.isOn(allOn, GroupSettings.Key.HistoryVisible))
        assertFalse(GroupSettings.isOn(GroupInfo(convId = "g1"), GroupSettings.Key.HistoryVisible))
    }

    /** 文案是跨端契约：同一个开关在 iOS/Web/Android 上必须叫同一个名字。 */
    @Test
    fun `文案与 im-web 逐字一致`() {
        assertEquals("进群确认", GroupSettings.label(GroupSettings.Key.JoinApproval))
        assertEquals("仅管理员可邀请", GroupSettings.label(GroupSettings.Key.PermInvite))
        assertEquals("仅管理员可改群资料", GroupSettings.label(GroupSettings.Key.PermEditInfo))
        assertEquals("仅管理员可置顶消息", GroupSettings.label(GroupSettings.Key.PermPin))
        assertEquals("新成员仅可见入群后历史", GroupSettings.label(GroupSettings.Key.HistoryVisible))
    }

    /** 分组要把五个开关**全都**排进去，漏一个就是界面上少一个开关而没人发现。 */
    @Test
    fun `两个分组合起来覆盖全部五个开关`() {
        val all = GroupSettings.JOIN_GROUP + GroupSettings.PERM_GROUP
        assertEquals(GroupSettings.Key.entries.toSet(), all.toSet())
        assertEquals("有重复项", all.size, all.toSet().size)
    }
}

/** 测试要按 key 读 [GroupSettings.Values]，而生产代码里那个是 private。 */
private fun GroupSettings.readFor(v: GroupSettings.Values, k: GroupSettings.Key): Boolean = when (k) {
    GroupSettings.Key.JoinApproval -> v.joinApproval
    GroupSettings.Key.PermInvite -> v.permInvite
    GroupSettings.Key.PermEditInfo -> v.permEditInfo
    GroupSettings.Key.PermPin -> v.permPin
    GroupSettings.Key.HistoryVisible -> v.historyVisible
}
