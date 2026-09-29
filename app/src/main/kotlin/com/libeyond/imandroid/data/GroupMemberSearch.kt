package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.GroupMember

/**
 * 群成员搜索的判据（对齐 iOS `IMGroupMemberSearchViewController` 顶部那组 C 函数）。
 *
 * **恒走服务端 `?q=`，不做本地过滤**——超级群本地只有已翻到的那几页（2 万人群里通常就 50 个），
 * 拿它过滤 = 在 50 人里搜 2 万人，界面看着正常、结果悄悄是错的；普通群 `GroupInfo.members`
 * 虽然是全的也不走第二套口径——两套迟早分叉，而分叉那天没人会发现。
 */
object GroupMemberSearch {

    /** 群总人数超过这个数才提供搜索入口（同 iOS `kIMMemberSearchMinMembers`）。 */
    const val MIN_MEMBERS_TO_OFFER = 50

    /** 结果每页条数——命中本身也可能很长（搜 "big" 能命中几千人），所以结果也要翻页（同 iOS）。 */
    const val PAGE_SIZE = 50

    /** 输入到发请求之间的静默期，毫秒（同 iOS / `rememberMentionComposer` 的 0.3s，别各调各的）。 */
    const val DEBOUNCE_MS = 300L

    /** 触底自动续拉的提前量（同 iOS `kIMMemberAutoLoadLeadRows`）。 */
    const val AUTO_LOAD_LEAD_ROWS = 15

    /** 群总人数优先用 `memberCount`，回退已加载数（超级群列表页只有我自己，不能当成"人少"）。 */
    fun shouldOffer(memberCount: Int, loadedCount: Int): Boolean {
        val total = if (memberCount > 0) memberCount else loadedCount
        return total > MIN_MEMBERS_TO_OFFER
    }

    /**
     * 滚到「末尾提前量」之内即触发续拉。用 `>=` 不用 `==`：行可能被跳着显示
     * （快速甩动、复用后从中间开始），只认相等的话滚得快一点就整个错过、再也不续拉。
     */
    fun shouldAutoLoadMore(displayedIndex: Int, loadedCount: Int, hasMore: Boolean, loading: Boolean): Boolean {
        if (!hasMore || loading || loadedCount <= 0) return false
        return displayedIndex >= loadedCount - AUTO_LOAD_LEAD_ROWS
    }

    /**
     * 首页整页替换、续页追加，**按 userId 去重**：keyset 游标翻页期间有人进群/退群，
     * 相邻两页可能覆盖同一个人（同 iOS `fetchPageWithQuery:` 的去重逻辑）。
     */
    fun mergePage(existing: List<GroupMember>, incoming: List<GroupMember>, isFirstPage: Boolean): List<GroupMember> {
        val base = if (isFirstPage) emptyList() else existing
        val seen = base.mapTo(HashSet()) { it.userId }
        return base + incoming.filter { it.userId !in seen }
    }
}
