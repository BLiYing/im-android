package com.libeyond.imandroid.ui

import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderMinus
import com.composables.icons.lucide.FolderPlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChatFolder
import com.libeyond.imandroid.data.FolderSnapshot
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.ui.components.SheetItem

/**
 * 会话长按菜单里的**文件夹项**（设计 CHAT_FOLDERS_DESIGN §4.4），放在菜单**第一位**：
 *
 * | 该会话当前状态 | 菜单顶部 |
 * |---|---|
 * | 不在任何文件夹 | 添加到文件夹 › |
 * | 正在查看某文件夹 Tab 且它在该夹内 / 「全部」里恰在 1 个夹内 | 从「X」移出（一步完成）、添加到文件夹 › |
 * | 在 ≥2 个夹内（且不在文件夹 Tab 里查看） | 从文件夹移出 ›（只列它所在的夹）、添加到文件夹 › |
 *
 * 子菜单在**同一菜单内就地展开**（[SheetItem.submenu]，对齐 iOS 嵌套 `UIMenu`）；点某个文件夹 = 切换归属并收起。
 * 还没有任何文件夹时不展开二级，直接弹新建输入框（[onNew]）。
 *
 * 纯函数（给定快照 → 菜单项）：动作经回调交给调用方，便于单测钉住「顺序 / 文案 / 已满态」。
 *
 * @param currentFolderId 列表当前所在的文件夹 Tab（null = 「全部」）。
 * @param onToggle 把会话放入（member=true）/ 移出（false）某文件夹。
 * @param onNew 弹「新建文件夹」输入框（创建后直接放入这个会话）。
 * @param onToast 上限类提示（不发请求）。
 */
internal fun conversationFolderItems(
    snapshot: FolderSnapshot,
    convId: String,
    currentFolderId: String?,
    onToggle: (ChatFolder, Boolean) -> Unit,
    onNew: () -> Unit,
    onToast: (String) -> Unit,
): List<SheetItem> {
    if (convId.isEmpty()) return emptyList()
    val out = mutableListOf<SheetItem>()
    val containing = snapshot.containing(convId)
    val current = snapshot.folder(currentFolderId)

    val removeTarget = when {
        current != null && current.contains(convId) -> current
        containing.size == 1 -> containing.first()
        else -> null
    }
    if (removeTarget != null) {
        out += SheetItem(Str.s(R.string.folder_menu_remove_from, removeTarget.name), icon = Lucide.FolderMinus) {
            onToggle(removeTarget, false)
        }
    } else if (containing.size > 1) {
        out += SheetItem(
            Str.s(R.string.folder_menu_remove_from_multi), icon = Lucide.FolderMinus,
            submenu = containing.map { f -> SheetItem(f.name) { onToggle(f, false) } },
        )
    }

    if (snapshot.folders.isEmpty()) {
        // 没有任何文件夹：不展开二级，直接弹新建输入框
        out += SheetItem(Str.s(R.string.folder_menu_add), icon = Lucide.FolderPlus) { onNew() }
        return out
    }
    val limits = snapshot.limits
    val kids = snapshot.folders.map { f ->
        val has = f.contains(convId)
        val full = !has && f.convIds.size >= limits.maxConvs
        val label = if (full) "${f.name} · ${Str.s(R.string.folder_menu_full, limits.maxConvs)}" else f.name
        SheetItem(label, icon = if (has) Lucide.Check else null) {
            if (full) onToast(Str.s(R.string.err_300302)) else onToggle(f, !has)
        }
    }.toMutableList()
    val maxed = snapshot.folders.size >= limits.maxFolders
    val newLabel = if (maxed) "${Str.s(R.string.folder_menu_new)} · ${Str.s(R.string.folder_menu_max_reached, limits.maxFolders)}"
    else Str.s(R.string.folder_menu_new)
    kids += SheetItem(newLabel, icon = Lucide.Plus) {
        if (maxed) onToast(Str.s(R.string.err_300301)) else onNew()
    }
    out += SheetItem(Str.s(R.string.folder_menu_add), icon = Lucide.Folder, submenu = kids)
    return out
}
