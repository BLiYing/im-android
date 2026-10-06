package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选人页三种用途的名单判据（2026-09-16 从 `GroupInfoHost` 的 @Composable 分支里抽出来）。
 *
 * 三条过滤写反了都**不会报错**，只会在点下去之后被服务端拒（300006 / 无意义的自转让 /
 * 邀请已在群的人必然失败），而用户看不出为什么——所以它们值得被钉住。
 */
class GroupPickTest {

    private fun member(uid: String, role: String) =
        GroupMember(userId = uid, username = uid, nickname = uid, role = role)

    private fun friend(uid: String) = FriendEntry(userId = uid, username = uid, nickname = uid)

    private val members = listOf(
        member("owner1", GroupMember.ROLE_OWNER),
        member("admin1", GroupMember.ROLE_ADMIN),
        member("m1", GroupMember.ROLE_MEMBER),
        member("m2", GroupMember.ROLE_MEMBER),
    )

    @Test
    fun add_admin_lists_plain_members_only() {
        val ids = GroupPick.candidates(PickPurpose.AddAdmin, members, emptyList(), "owner1").map { it.id }
        assertEquals(listOf("m1", "m2"), ids)
    }

    @Test
    fun transfer_excludes_myself() {
        val ids = GroupPick.candidates(PickPurpose.Transfer, members, emptyList(), "owner1").map { it.id }
        assertEquals(listOf("admin1", "m1", "m2"), ids)
        assertFalse("owner1" in ids)
    }

    @Test
    fun invite_excludes_friends_already_in_group() {
        val friends = listOf(friend("m1"), friend("f9"), friend("owner1"))
        val ids = GroupPick.candidates(PickPurpose.Invite, members, friends, "owner1").map { it.id }
        assertEquals(listOf("f9"), ids)
    }

    /** 群里只有我一个人时，转让页是空的——空态文案要对得上，不能落到通用的「没有可选的人」。 */
    @Test
    fun transfer_alone_is_empty() {
        val solo = listOf(member("owner1", GroupMember.ROLE_OWNER))
        assertTrue(GroupPick.candidates(PickPurpose.Transfer, solo, emptyList(), "owner1").isEmpty())
        assertEquals("群里还没有别人", GroupPick.emptyText(PickPurpose.Transfer))
    }

    @Test
    fun candidate_carries_display_name_and_handle() {
        val c = GroupPick.candidates(PickPurpose.AddAdmin, members, emptyList(), "owner1").first()
        assertEquals("m1", c.name)
        assertEquals("@m1", c.handle)
    }

    @Test
    fun transfer_is_the_only_single_select_besides_nothing() {
        assertTrue(GroupPick.isMultiSelect(PickPurpose.Invite))
        assertTrue(GroupPick.isMultiSelect(PickPurpose.AddAdmin))
        // 转让不可逆：选中即进二次确认，不能攒
        assertFalse(GroupPick.isMultiSelect(PickPurpose.Transfer))
    }

    /** 添加管理员一次最多 5 位（对齐 iOS `IMGroupAdminMaxBatch`），满了不再加、已选的仍可取消。 */
    @Test
    fun add_admin_caps_at_five() {
        var picked = emptySet<String>()
        for (i in 1..GroupPick.MAX_ADMIN_BATCH) picked = GroupPick.toggle(PickPurpose.AddAdmin, picked, "u$i")
        assertEquals(5, picked.size)
        assertEquals(picked, GroupPick.toggle(PickPurpose.AddAdmin, picked, "u6"))
        assertFalse("u1" in GroupPick.toggle(PickPurpose.AddAdmin, picked, "u1"))
    }

    @Test
    fun titles_match_each_purpose() {
        assertEquals("添加管理员", GroupPick.title(PickPurpose.AddAdmin))
        assertEquals("选择新群主", GroupPick.title(PickPurpose.Transfer))
        assertEquals("邀请成员", GroupPick.title(PickPurpose.Invite))
    }

    @Test
    fun call_lists_everyone_but_me_regardless_of_role() {
        val ids = GroupPick.candidates(PickPurpose.Call, members, emptyList(), "owner1").map { it.id }
        assertEquals(listOf("admin1", "m1", "m2"), ids)
    }

    @Test
    fun call_is_multi_select_and_caps_at_eight() {
        assertTrue(GroupPick.isMultiSelect(PickPurpose.Call))
        var picked = emptySet<String>()
        for (i in 1..GroupPick.MAX_CALL_PICK) picked = GroupPick.toggle(PickPurpose.Call, picked, "u$i")
        assertEquals(GroupPick.MAX_CALL_PICK, picked.size)
        // 满了再加：原样返回（调用方靠「没变」判断要提示）
        assertEquals(picked, GroupPick.toggle(PickPurpose.Call, picked, "u9"))
        // 已选的永远能取消
        assertFalse("u1" in GroupPick.toggle(PickPurpose.Call, picked, "u1"))
    }

    @Test
    fun invite_has_no_cap() {
        var picked = emptySet<String>()
        for (i in 1..20) picked = GroupPick.toggle(PickPurpose.Invite, picked, "u$i")
        assertEquals(20, picked.size)
    }

    /** 已有管理员占名额：2 位管理员 → 这次最多再勾 3 位；满 5 位则一个都勾不了（群主不算管理员）。 */
    @Test
    fun add_admin_cap_counts_existing_admins() {
        fun m(id: String, role: String) = GroupMember(userId = id, role = role)
        val two = listOf(m("o", GroupMember.ROLE_OWNER), m("a1", GroupMember.ROLE_ADMIN), m("a2", GroupMember.ROLE_ADMIN), m("x", GroupMember.ROLE_MEMBER))
        assertEquals(3, GroupPick.adminSlotsLeft(two))
        assertEquals(3, GroupPick.maxPick(PickPurpose.AddAdmin, two))
        var picked = emptySet<String>()
        for (i in 1..3) picked = GroupPick.toggle(PickPurpose.AddAdmin, picked, "u$i", two)
        assertEquals(picked, GroupPick.toggle(PickPurpose.AddAdmin, picked, "u4", two))

        val full = (1..5).map { m("a$it", GroupMember.ROLE_ADMIN) } + m("x", GroupMember.ROLE_MEMBER)
        assertEquals(0, GroupPick.adminSlotsLeft(full))
        assertEquals(emptySet<String>(), GroupPick.toggle(PickPurpose.AddAdmin, emptySet(), "x", full))
        assertEquals(5, GroupPick.adminSlotsLeft(listOf(m("o", GroupMember.ROLE_OWNER), m("x", GroupMember.ROLE_MEMBER))))
    }
}
