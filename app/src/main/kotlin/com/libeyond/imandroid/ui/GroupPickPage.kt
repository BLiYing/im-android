package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.rtc.RtcCall
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
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
    /** 设管理员**可撤销**，攒够（≤5 位）按确认后执行，宿主串行逐个下发。 */
    onAddAdmins: (List<String>) -> Unit,
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
                PickPurpose.Transfer -> onTransferTo(row.id)
                PickPurpose.AddAdmin, PickPurpose.Invite, PickPurpose.Call -> Unit
            }
        },
        limit = GroupPick.maxPick(purpose, members) ?: -1,
        // 对齐 iOS：确认钮就叫「确认」，标题「添加管理员」+ 副标题「已勾选x/N人」（N = 剩余名额，已有管理员占名额）
        subtitleOverride = if (purpose == PickPurpose.AddAdmin) {
            stringResource(R.string.group_admin_picker_selected_subtitle, picked.size, GroupPick.maxPick(purpose, members) ?: 0)
        } else {
            null
        },
        confirmText = stringResource(R.string.common_confirm),
        onConfirm = { if (purpose == PickPurpose.AddAdmin) onAddAdmins(picked.toList()) else onConfirmInvite(picked.toList()) },
        onBack = onBack,
    )
}

/**
 * [GroupPickPage] 的接线：从 `GroupInfoHost` 拆出（那个文件贴 600 行硬闸），
 * 只管"点下去之后做什么"——勾选限额吐司、设管理员串行下发、转让先二次确认、邀请 / 拨群通话。
 */
@Composable
internal fun GroupPickHost(
    client: IMClient,
    convId: String,
    purpose: PickPurpose,
    members: List<GroupMember>,
    friends: List<FriendEntry>,
    picked: Set<String>,
    myUid: String,
    scope: CoroutineScope,
    runManage: (String, suspend () -> Any?) -> Unit,
    onPicked: (Set<String>) -> Unit,
    onToast: (String?) -> Unit,
    onInfo: (GroupInfo) -> Unit,
    refreshMembers: suspend () -> Unit,
    onClose: () -> Unit,
    onRequestTransfer: (String) -> Unit,
) {
    GroupPickPage(
        purpose = purpose,
        members = members,
        friends = friends,
        picked = picked,
        myUid = myUid,
        onToggle = { id ->
            val next = GroupPick.toggle(purpose, picked, id, members)
            if (next == picked && id !in picked) {
                onToast(
                    if (purpose == PickPurpose.AddAdmin) Str.s(R.string.group_admin_picker_limit_toast, GroupPick.maxPick(purpose, members) ?: 0)
                    else Str.s(R.string.chat_detail_group_call_pick_max, GroupPick.MAX_CALL_PICK),
                )
            }
            onPicked(next)
        },
        // 设管理员可撤销，攒够（≤5）确认后串行下发；全失败停在选人页，其余回管理员页。转让不可逆，先二次确认
        onAddAdmins = { ids ->
            if (ids.isNotEmpty()) scope.launch {
                onPicked(emptySet()) // 串行下发期间清掉勾选：确认钮随之失效，连点不会重复下发
                val (ok, msg) = addAdminsResult(client, convId, ids)
                onToast(msg)
                if (ok == 0) onPicked(ids.toSet()) // 全失败：停在选人页，保留勾选让用户换人/重试
                else {
                    onClose()
                    runCatching { client.groups.info(convId) }.onSuccess(onInfo)
                    refreshMembers()
                }
            }
        },
        onTransferTo = onRequestTransfer,
        onConfirmInvite = { ids ->
            onClose()
            if (purpose == PickPurpose.Call) {
                // 通话界面由 im-rtc 的 Kit 接管；拨不出去才回一句原因
                if (ids.isNotEmpty()) RtcCall.placeGroup(convId, ids, onError = { onToast(it) })?.let { onToast(it) }
            } else if (ids.isNotEmpty()) runManage(Str.s(R.string.group_manage_invite_members)) { ManageToast(inviteText(client, convId, ids)) }
        },
        onBack = onClose,
    )
}
