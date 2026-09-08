package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import com.libeyond.imandroid.data.GroupPermissions
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.SheetItem

/**
 * 群成员长按菜单（设/撤管理员、禁言、转让、移出）。
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
    runManage: (String, suspend () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val actions = buildList {
        if (GroupPermissions.canSetRole(info, member, myUid)) {
            val makeAdmin = !member.isAdmin
            add(SheetItem(if (makeAdmin) "设为管理员" else "撤销管理员") {
                runManage(if (makeAdmin) "设为管理员" else "撤销管理员") {
                    client.groups.setRole(convId, member.userId, if (makeAdmin) "admin" else "member")
                }
            })
        }
        if (GroupPermissions.canMute(info, member, myUid)) {
            val muted = GroupPermissions.isMuteActive(member.muteUntil)
            add(SheetItem(if (muted) "解除禁言" else "禁言") {
                runManage(if (muted) "解除禁言" else "禁言") {
                    client.groups.muteMember(convId, member.userId, if (muted) 0L else -1L)
                }
            })
        }
        if (GroupPermissions.canTransfer(info, member, myUid)) {
            add(SheetItem("转让群主", destructive = true) {
                runManage("转让群主") { client.groups.transferOwner(convId, member.userId) }
            })
        }
        if (GroupPermissions.canRemove(info, member, myUid)) {
            add(SheetItem("移出群聊", destructive = true) {
                // 缺省 cooldown=24h：只移出（none）会让人立刻又进来，
                // 永久黑名单（forever）对一次误操作又太重（PROTOCOL §11 三档）
                runManage("移出群聊") {
                    client.groups.removeMember(convId, member.userId, ban = "cooldown")
                }
            })
        }
    }
    // 一项都没有就别弹一个空壳（对方是群主、我是普通成员时就是这样）
    if (actions.isEmpty()) {
        onDismiss()
        return
    }
    ActionSheet(title = member.displayName, items = actions, onDismiss = onDismiss)
}
