package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupMember

/**
 * 选人页的一个候选。
 *
 * 不直接用 `ui.screens.PickRow` 是**为了不让 data 层反向依赖 ui**：这里只算「谁该出现在名单里」，
 * 画成什么样归 UI。两者字段一一对应，UI 侧一行 map 过去。
 */
data class GroupPickCandidate(
    val id: String,
    val name: String,
    val avatarUrl: String,
    val handle: String,
)

/**
 * 群这条链上「选人页该列谁、叫什么、空了说什么」——三种用途共用一个页面（见 `PickListScreen`）。
 *
 * 从 `GroupInfoHost` 抽出来（2026-09-16，那个文件到 585/600 行）：抽的是**判据**而不是 UI
 * （CODING_STYLE §7③ 那一档），**行为未改**。三份名单各有一条容易写反的过滤，
 * 而它们过去躺在一个 @Composable 的分支里，单测碰不到：
 * - **设管理员**只列普通成员——把已经是管理员/群主的也列出来，点了服务端回 300006；
 * - **转让群主**要剔掉自己——「转让给自己」是个没有意义还不可撤销的操作；
 * - **邀请入群**要剔掉已在群里的好友——列出来点了必然失败，而用户看不出为什么。
 */
object GroupPick {

    fun candidates(
        purpose: PickPurpose,
        members: List<GroupMember>,
        friends: List<FriendEntry>,
        myUid: String,
    ): List<GroupPickCandidate> = when (purpose) {
        PickPurpose.AddAdmin -> members
            .filter { it.role == GroupMember.ROLE_MEMBER }
            .map { it.toCandidate() }
        // 群通话：除自己外都能选。不筛角色 / 是否好友——通话对象是「群成员」，不是「我的好友」。
        PickPurpose.Transfer, PickPurpose.Call -> members
            .filter { it.userId != myUid }
            .map { it.toCandidate() }
        PickPurpose.Invite -> {
            val inGroup = members.mapTo(HashSet()) { it.userId }
            friends.filter { it.userId !in inGroup }.map { it.toCandidate() }
        }
    }

    fun title(purpose: PickPurpose): String = when (purpose) {
        PickPurpose.AddAdmin -> Str.s(R.string.group_admin_picker_title)
        PickPurpose.Transfer -> Str.s(R.string.group_transfer_owner_title)
        PickPurpose.Invite -> Str.s(R.string.friend_picker_default_title)
        PickPurpose.Call -> Str.s(R.string.group_call_picker_title)
    }

    fun emptyText(purpose: PickPurpose): String = when (purpose) {
        PickPurpose.AddAdmin -> Str.s(R.string.group_admin_picker_empty)
        PickPurpose.Transfer -> Str.s(R.string.group_pick_no_other_members)
        PickPurpose.Invite -> Str.s(R.string.friend_picker_default_empty)
        PickPurpose.Call -> Str.s(R.string.group_pick_no_other_members)
    }

    /** 邀请、群通话、添加管理员是多选（攒够了按右上角确认）；转让群主不可逆，仍是**选中即确认**。 */
    fun isMultiSelect(purpose: PickPurpose): Boolean =
        purpose == PickPurpose.Invite || purpose == PickPurpose.Call || purpose == PickPurpose.AddAdmin

    /** 群通话房内含主叫最多 9 人（im-rtc 协议上限），所以被叫最多选 8 个。 */
    const val MAX_CALL_PICK = 8

    /** 一次最多添加几位管理员（对齐 iOS `IMGroupAdminMaxBatch`；服务端没有批量接口，端上串行逐个 PUT）。 */
    const val MAX_ADMIN_BATCH = 5

    /** 多选上限；`null` = 不限。 */
    fun maxPick(purpose: PickPurpose): Int? = when (purpose) {
        PickPurpose.Call -> MAX_CALL_PICK
        PickPurpose.AddAdmin -> MAX_ADMIN_BATCH
        else -> null
    }

    /**
     * 勾选 / 取消勾选一个人。已选的永远能取消；有上限的用途（[maxPick]）满了之后**不再加**（返回原集合，
     * 调用方比较前后是否相等来决定要不要提示）。其余用途不设上限。
     */
    fun toggle(purpose: PickPurpose, picked: Set<String>, id: String): Set<String> {
        val cap = maxPick(purpose)
        return when {
            id in picked -> picked - id
            cap != null && picked.size >= cap -> picked
            else -> picked + id
        }
    }
}

private fun GroupMember.toCandidate() = GroupPickCandidate(userId, displayName, avatarUrl, handle)

private fun FriendEntry.toCandidate() = GroupPickCandidate(userId, displayName, avatarUrl, handle)
