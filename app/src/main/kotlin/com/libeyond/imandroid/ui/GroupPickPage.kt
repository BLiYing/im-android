package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import com.libeyond.imandroid.data.GroupPick
import com.libeyond.imandroid.data.PickPurpose
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.ui.screens.PickListScreen
import com.libeyond.imandroid.ui.screens.PickRow

/**
 * 群这条链上的选人页（设管理员 / 转让群主 / 邀请入群三种用途共用）。
 *
 * 从 `GroupInfoHost` 拆出（2026-09-16，那个文件到 585/600 行）：**名单、标题、空态文案
 * 这三份判据搬进了 `data/GroupPick.kt` 并配了单测**——它们过去躺在一个 @Composable 的
 * 分支里，三条容易写反的过滤（只列普通成员 / 剔掉自己 / 剔掉已在群的好友）一条也测不到。
 * 这里只剩"画出来、把点击翻译成宿主的动作"。
 *
 * **单选立即回调、多选攒着确认**的差别也在 `GroupPick.isMultiSelect` 里，别在这儿另判一次。
 */
@Composable
internal fun GroupPickPage(
    purpose: PickPurpose,
    members: List<GroupMember>,
    friends: List<FriendEntry>,
    picked: Set<String>,
    myUid: String,
    onToggle: (String) -> Unit,
    /** 设管理员**可撤销**，选中即执行。 */
    onAddAdmin: (String) -> Unit,
    /** 转让**不可逆**，宿主收到后先弹二次确认。 */
    onTransferTo: (String) -> Unit,
    onConfirmInvite: (List<String>) -> Unit,
    onBack: () -> Unit,
) {
    val rows = GroupPick.candidates(purpose, members, friends, myUid)
        .map { PickRow(it.id, it.name, it.avatarUrl, it.handle) }
    PickListScreen(
        title = GroupPick.title(purpose),
        rows = rows,
        selected = picked,
        multi = GroupPick.isMultiSelect(purpose),
        emptyText = GroupPick.emptyText(purpose),
        onToggle = onToggle,
        onPick = { row ->
            when (purpose) {
                PickPurpose.AddAdmin -> onAddAdmin(row.id)
                PickPurpose.Transfer -> onTransferTo(row.id)
                PickPurpose.Invite, PickPurpose.Call -> Unit
            }
        },
        onConfirm = { onConfirmInvite(picked.toList()) },
        onBack = onBack,
    )
}
