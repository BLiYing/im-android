package com.libeyond.imandroid.data

import com.imrtc.engine.IMCallHistoryRecord

/**
 * 「设置 ▸ 最近通话」列表的纯判据（`../IMServer/docs/design/CALL_HISTORY_DESIGN.md` §1/§3.5）。
 *
 * reason 的文案 / 未接判色令牌**不在这里**——那是 [CallRecord] 的活（聊天记录气泡与这个列表
 * 共用同一批通话事实，判定顺序也该同一份，见设计文档 §0.7）。这里只管列表特有的东西：
 * 谁是对方、群通话人数、按天分组、翻页去重、「未接」tab 的自动续页。
 */
object CallHistory {

    /** 每页条数：与 SDK 默认值同值（`IMCallHistory.DEFAULT_LIMIT` 是 SDK 内部 `internal`，摸不到，另存一份）。 */
    const val PAGE_SIZE = 20

    /** 未接判定：我是被叫 且 没接通（对齐 im-rtc Demo `CallHistory` 的 missed 判据，也是聊天气泡未接判色规则）。 */
    fun isMissed(record: IMCallHistoryRecord, me: String): Boolean =
        record.caller != me && record.durationSec == 0

    /**
     * 1v1 对方 uid：我是被叫就看主叫；我是主叫就看 `members` 里第一个不是自己的人
     * （同 im-rtc Demo `HistoryScreen.peerText`，不重新发明）。查不到给空串，调用方兜底。
     */
    fun peerUid(record: IMCallHistoryRecord, me: String): String =
        if (record.caller != me) record.caller else record.members.firstOrNull { it.uid != me }?.uid.orEmpty()

    /**
     * 群通话参与人数：`members` 条数，若发起人本人不在 `members` 里再 +1
     * （同 im-rtc Demo `peerText`：发起人有时不算进参与者列表）。
     */
    fun groupMemberCount(record: IMCallHistoryRecord): Int {
        val callerCounted = record.members.any { it.uid == record.caller }
        return maxOf(record.members.size, 1) + if (callerCounted) 0 else 1
    }

    /** 翻页累积去重（按 `callId`）：服务端游标翻页理论上不重复，这里只是防御一下，同 `Favorites.merge`。 */
    fun merge(loaded: List<IMCallHistoryRecord>, page: List<IMCallHistoryRecord>): List<IMCallHistoryRecord> {
        val seen = loaded.mapTo(HashSet()) { it.callId }
        return loaded + page.filter { it.callId !in seen }
    }

    /** 「未接」tab：只从已加载数据里筛，两个 tab 共用同一份数据（设计文档 §3.5），不是单独的数据源。 */
    fun missedOnly(records: List<IMCallHistoryRecord>, me: String): List<IMCallHistoryRecord> =
        records.filter { isMissed(it, me) }

    /**
     * 「未接」tab 翻页是否该自动续拉下一页：过滤完不够一屏（[PAGE_SIZE] 当"一屏"的量）
     * 且服务端还有下一页。「全部」tab 不吃这条——正常一页一页加载（设计文档 §3.5 / UX 稿 §04-C）。
     */
    fun shouldAutoContinue(filteredCount: Int, hasMore: Boolean): Boolean =
        hasMore && filteredCount < PAGE_SIZE

    /** 一个日期分组：`label` 由调用方给的判定函数算出（复用 `TimeFormat.dayLabel`，这里不重新实现）。 */
    data class DayGroup(val label: String, val records: List<IMCallHistoryRecord>)

    /**
     * 按自然日分组，组内保持原有顺序（调用方需保证 `records` 已按 `startedAtMs` 倒序——服务端保证，
     * 见设计文档 §1，这里不再排一遍）。
     *
     * `labelOf` 故意注入而不是这里直接依赖 `TimeFormat`：那是 `ui.components` 包的东西，
     * `data/` 层不该反向依赖 UI 层，测试也更好写（传个假的 labelOf 就行，不用管日期本身对不对）。
     */
    fun groupByDay(records: List<IMCallHistoryRecord>, now: Long, labelOf: (Long, Long) -> String): List<DayGroup> {
        val groups = mutableListOf<DayGroup>()
        for (r in records) {
            val label = labelOf(r.startedAtMs, now)
            val last = groups.lastOrNull()
            if (last != null && last.label == label) {
                groups[groups.lastIndex] = last.copy(records = last.records + r)
            } else {
                groups.add(DayGroup(label, listOf(r)))
            }
        }
        return groups
    }
}
