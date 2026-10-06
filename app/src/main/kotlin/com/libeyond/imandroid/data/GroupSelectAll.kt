package com.libeyond.imandroid.data

/**
 * 建群选人页「全选 / 取消全选」的纯逻辑（口径见 `IMServer/docs/design/CREATE_GROUP_SELECT_ALL_DESIGN.md` §0）。
 *
 * - 只作用于**可见行**（搜索过滤后的列表）；
 * - 全选 = 保留已选 + 按可见顺序补，补到 [limit] 为止（[limit] <= 0 不截断）；
 * - 可见行已全部选中时变「取消全选」，只取消可见行；
 * - 可见行为 0 时按钮不显示。
 */
object GroupSelectAll {
    /** 可见行非空且全部已选中 → 按钮显示「取消全选」。 */
    fun allSelected(selected: Set<String>, visible: List<String>): Boolean =
        visible.isNotEmpty() && visible.all { it in selected }

    /** 按钮是否显示：可见行至少 1 个。 */
    fun isVisible(visible: List<String>): Boolean = visible.isNotEmpty()

    /** 群成员上限（含群主）→ 可选好友数上限；`maxMembers <= 0`（配置未加载）返回 0 = 不截断。 */
    fun limitOf(maxMembers: Int): Int = if (maxMembers > 0) maxMembers - 1 else 0

    /** 点按钮后的新选中集合。 */
    fun next(selected: Set<String>, visible: List<String>, limit: Int): Set<String> {
        if (visible.isEmpty()) return selected
        if (allSelected(selected, visible)) return selected - visible.toSet()
        val result = LinkedHashSet(selected)
        for (id in visible) {
            if (limit > 0 && result.size >= limit) break
            result.add(id)
        }
        return result
    }
}
