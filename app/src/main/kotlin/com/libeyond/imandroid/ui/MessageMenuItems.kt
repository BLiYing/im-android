package com.libeyond.imandroid.ui

import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.CornerUpLeft
import com.composables.icons.lucide.Forward
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Users
import com.libeyond.imandroid.data.MessageAction
import com.libeyond.imandroid.ui.components.SheetItem

// 消息长按菜单的**拼装**（不含渲染）。从 ChatHost 拆出（CODING_STYLE §7②）：
// 那个文件是接线层，菜单怎么摆是另一件事，且它已经贴着 600 行的体量红线。

/**
 * 把动作列表拼成菜单项，**两档删除收进一个「删除」子菜单**。
 *
 * 对齐 iOS `deleteMenuActionForMessage:`：两档删除是**同一个动作的两种范围**，
 * 摊成两个平级项会让人以为是两件不同的事（而且「为所有人删除」这种长文案会把菜单撑宽）。
 * 只有一档可用时不套子菜单——为一个选项造一层菜单是纯粹的多余点击。
 */
internal fun buildMessageMenu(
    actions: List<MessageAction>,
    run: (MessageAction) -> Unit,
): List<SheetItem> {
    val deletes = actions.filter {
        it == MessageAction.DeleteForEveryone || it == MessageAction.HideForMe
    }
    val head = actions.filterNot { it in deletes }.map { a ->
        SheetItem(a.label, a.destructive, icon = messageActionIcon(a)) { run(a) }
    }
    val tail = when {
        deletes.isEmpty() -> emptyList()
        deletes.size == 1 -> listOf(
            SheetItem("删除", destructive = true, icon = Lucide.Trash2) { run(deletes.first()) },
        )
        else -> listOf(
            SheetItem(
                "删除", destructive = true, icon = Lucide.Trash2,
                submenu = deletes.map { a ->
                    SheetItem(a.label, destructive = true, icon = messageActionIcon(a)) { run(a) }
                },
            ),
        )
    }
    return head + tail
}

/**
 * 消息菜单项图标。**逐项对齐 iOS `messageActionsForMessage:` 里的 SF Symbol**
 * （doc.on.doc / arrowshape.turn.up.left / arrowshape.turn.up.right /
 * arrow.uturn.backward / trash），用 Lucide 里语义最近的一枚。
 */
internal fun messageActionIcon(a: MessageAction) = when (a) {
    MessageAction.Copy -> Lucide.Copy
    MessageAction.Reply -> Lucide.CornerUpLeft
    MessageAction.Forward -> Lucide.Forward
    MessageAction.Recall -> Lucide.Undo2
    MessageAction.DeleteForEveryone -> Lucide.Users
    MessageAction.HideForMe -> Lucide.User
}
