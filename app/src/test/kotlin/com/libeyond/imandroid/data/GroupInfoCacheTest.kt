package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GroupInfoCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val info = GroupInfo(
        convId = "g_1", name = "大家庭", myRole = "member", memberCount = 2000, permInvite = true,
        joinApproval = true, intro = "简介", members = listOf(GroupMember(userId = "u1")),
    )

    @Test fun `存了再读 权限开关与人数原样回来 成员表被剔掉`() {
        val c = GroupInfoCache(tmp.newFolder())
        c.save("me", info)
        val got = c.load("me", "g_1")!!
        assertEquals("大家庭", got.name)
        assertEquals(2000, got.memberCount)
        assertTrue(got.permInvite)       // 权限开关必须在：占位页缺它会让「仅管理员可邀请」被当成关
        assertTrue(got.joinApproval)
        assertTrue(got.members.isEmpty())
    }

    @Test fun `换账号不串`() {
        val c = GroupInfoCache(tmp.newFolder())
        c.save("me", info)
        assertNull(c.load("other", "g_1"))
    }

    @Test fun `没存过或文件损坏都当没有快照`() {
        val dir = tmp.newFolder()
        val c = GroupInfoCache(dir)
        assertNull(c.load("me", "g_x"))
        dir.resolve("me_g_1.json").writeText("{not json")
        assertNull(c.load("me", "g_1"))
    }
}
