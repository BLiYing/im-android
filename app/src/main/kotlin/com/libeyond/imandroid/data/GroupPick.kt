package com.libeyond.imandroid.data

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
        PickPurpose.Transfer -> members
            .filter { it.userId != myUid }
            .map { it.toCandidate() }
        PickPurpose.Invite -> {
            val inGroup = members.mapTo(HashSet()) { it.userId }
            friends.filter { it.userId !in inGroup }.map { it.toCandidate() }
        }
    }

    fun title(purpose: PickPurpose): String = when (purpose) {
        PickPurpose.AddAdmin -> "添加管理员"
        PickPurpose.Transfer -> "选择新群主"
        PickPurpose.Invite -> "邀请入群"
    }

    fun emptyText(purpose: PickPurpose): String = when (purpose) {
        PickPurpose.AddAdmin -> "没有可设为管理员的普通成员"
        PickPurpose.Transfer -> "群里还没有别人"
        PickPurpose.Invite -> "好友都已在群里"
    }

    /** 只有邀请是多选（攒够了按右上角确认）；另两件事都是**选中即执行**。 */
    fun isMultiSelect(purpose: PickPurpose): Boolean = purpose == PickPurpose.Invite
}

private fun GroupMember.toCandidate() = GroupPickCandidate(userId, displayName, avatarUrl, handle)

private fun FriendEntry.toCandidate() = GroupPickCandidate(userId, displayName, avatarUrl, handle)
