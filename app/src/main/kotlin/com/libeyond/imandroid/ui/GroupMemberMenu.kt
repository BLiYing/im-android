package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.UserPlus
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.GroupPermissions
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.components.LiftPreview
import com.libeyond.imandroid.ui.components.LocalMenuLift
import com.libeyond.imandroid.ui.components.MessageContextMenu

/**
 * 群成员长按菜单（发送消息 / 添加好友，设/撤管理员、禁言、转让、移出）。
 *
 * **原位浮起 + 贴着这一行弹**（2026-10-07，对齐 iOS 成员行的 `UIContextMenu`）：此前是底部 ActionSheet，
 * 按住的那一行毫无标记。预览是那一行自己录下的层（[LiftPreview]），锚点也从那里取。
 * 首项照 iOS：好友 →「发送消息」，非好友 →「添加好友」（非好友发消息会被 200103 拒收）；之后才是管理项。
 *
 * 从 `GroupInfoHost` 拆出（CODING_STYLE §7②）：那个文件是接线层且已经贴着 600 行硬闸，
 * 而"某个成员能对他做哪几件事"是一块自洽的东西——判据全在纯函数
 * [GroupPermissions] 里（有单测），这里只负责把它们摆成菜单。
 *
 * **可见性一律走 `GroupPermissions`**，别在这里就地写 `if (myRole == owner)`：
 * 那些判据服务端也有一份，端上判错不会越权（越权回 300006），但会摆出一排必然失败的按钮。
 *
 * @param runManage 执行一次管理动作并把成败说给用户听（宿主提供：它持有 toast 与刷新）。
 */
@Composable
internal fun GroupMemberMenu(
    member: GroupMember,
    info: GroupInfo,
    myUid: String,
    client: IMClient,
    convId: String,
    knownFriends: Map<String, com.libeyond.imandroid.sdk.api.FriendEntry>,
    runManage: (String, suspend () -> Unit) -> Unit,
    /** 「发送消息」：换到与他的单聊（宿主关掉群资料）。 */
    onOpenChat: (com.libeyond.imandroid.data.db.ConversationEntity) -> Unit,
    /** 「添加好友」：交宿主弹验证消息（请求要挂在宿主的作用域上，本菜单点完就离开组合）。 */
    onAddFriend: (GroupMember) -> Unit,
    onDismiss: () -> Unit,
) {
    // 二级：禁言时长选择单 / 移出二次确认。菜单本身点完就关，所以这两层必须挂在**本组件**（它不随菜单项点击离开组合）
    var pickingMute by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) } // "cooldown" | "forever"
    val actions = buildList {
        // 点到自己不给任何项（iOS 同：自己那一行长按不弹菜单）
        if (member.userId != myUid) {
            if (knownFriends[member.userId]?.status == com.libeyond.imandroid.sdk.api.FriendEntry.ACCEPTED) {
                add(SheetItem(stringResource(R.string.group_member_action_send_message), icon = Lucide.MessageCircle) {
                    onOpenChat(client.conversationStubFor(member.userId, member.nickname, member.avatarUrl))
                })
            } else {
                add(SheetItem(stringResource(R.string.common_add_friend), icon = Lucide.UserPlus) { onAddFriend(member) })
            }
        }
        if (GroupPermissions.canSetRole(info, member, myUid)) {
            val makeAdmin = !member.isAdmin
            val label = stringResource(
                if (makeAdmin) R.string.group_member_action_make_admin else R.string.group_member_action_revoke_admin
            )
            add(SheetItem(label) {
                runManage(label) {
                    client.groups.setRole(convId, member.userId, if (makeAdmin) "admin" else "member")
                }
            })
        }
        if (GroupPermissions.canMute(info, member, myUid)) {
            val muted = GroupPermissions.isMuteActive(member.muteUntil)
            // 未禁言：「禁言…」→ 弹时长选择单；已禁言：直接「解除禁言」（until=0，不二次确认，iOS 同）
            val label = stringResource(if (muted) R.string.group_member_action_unmute else R.string.group_member_action_mute)
            add(SheetItem(label) {
                if (muted) runManage(label) { client.groups.muteMember(convId, member.userId, 0L) }
                else pickingMute = true
            })
        }
        if (GroupPermissions.canTransfer(info, member, myUid)) {
            val label = stringResource(R.string.group_member_action_transfer_owner)
            add(SheetItem(label, destructive = true) {
                runManage(label) { client.groups.transferOwner(convId, member.userId) }
            })
        }
        if (GroupPermissions.canRemove(info, member, myUid)) {
            // 缺省 cooldown=24h：只移出（none）会让人立刻又进来（UI 不暴露，iOS 同）；
            // 永久黑名单（forever）单独一项，都要二次确认（破坏性）
            add(SheetItem(stringResource(R.string.group_member_action_remove), destructive = true) { confirmRemove = "cooldown" })
            add(SheetItem(stringResource(R.string.group_member_action_remove_and_ban), destructive = true) { confirmRemove = "forever" })
        }
    }
    // 一项都没有就别弹一个空壳（对方是群主、我是普通成员时就是这样）
    if (actions.isEmpty()) {
        onDismiss()
        return
    }
    if (pickingMute) {
        // 禁言时长：10 分钟 / 1 小时 / 1 天 / 永久(-1)。**到期时刻在点选那一下算**，不是弹出选择单时
        val label = stringResource(R.string.group_member_action_mute)
        @Composable
        fun item(res: Int, ms: Long?) = SheetItem(stringResource(res)) {
            runManage(label) { client.groups.muteMember(convId, member.userId, ms?.let { System.currentTimeMillis() + it } ?: -1L) }
        }
        ActionSheet(
            title = stringResource(R.string.mute_title) + " · " + member.displayName,
            items = listOf(
                item(R.string.mute_10m, 10 * 60_000L), item(R.string.mute_1h, 60 * 60_000L),
                item(R.string.mute_1d, 24 * 60 * 60_000L), item(R.string.common_permanent, null),
            ),
            onDismiss = { pickingMute = false; onDismiss() },
        )
        return
    }
    confirmRemove?.let { ban ->
        val forever = ban == "forever"
        val label = stringResource(if (forever) R.string.group_member_action_remove_and_ban else R.string.group_member_action_remove)
        IMConfirmDialog(
            title = stringResource(
                if (forever) R.string.chat_detail_remove_member_ban_confirm_title else R.string.chat_detail_remove_member_confirm_title,
                member.displayName,
            ),
            message = stringResource(
                if (forever) R.string.chat_detail_remove_member_ban_message else R.string.group_member_action_remove_confirm_message,
                member.displayName,
            ),
            confirmText = stringResource(R.string.common_remove),
            onConfirm = { confirmRemove = null; runManage(label) { client.groups.removeMember(convId, member.userId, ban = ban) }; onDismiss() },
            onDismiss = { confirmRemove = null; onDismiss() },
        )
        return
    }
    // 点「禁言…」/「移出…」时菜单项先把二级状态置位（本层随即换成二级层），菜单收起后才回调——
    // 此时不能把宿主的 memberMenu 清掉，否则本组件离开组合、二级层跟着没了
    val lift = LocalMenuLift.current
    if (lift != null) {
        MessageContextMenu(
            anchor = lift.area, mine = false, items = actions,
            preview = { LiftPreview(lift) },
            onDismiss = { if (!pickingMute && confirmRemove == null) onDismiss() },
        )
    } else {
        ActionSheet(
            title = member.displayName, items = actions,
            onDismiss = { if (!pickingMute && confirmRemove == null) onDismiss() },
        )
    }
}
