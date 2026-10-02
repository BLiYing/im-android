package com.libeyond.imandroid.data

/**
 * 服务端告诉我们的**可见下界**（`window_resp.has_before=false`）：每会话一个位点，只往小里收，**每次连上清空**。
 *
 * 记**位点不记布尔**、记在**会话级**不记在某一窗里——与 iOS `IMBacklogTracker.noteHistoryFloor`、Web `imSdk` 的 `floorSeq` 对称：
 * - 位点：`has_before` 是相对本窗下沿说的，不是整条会话；记成布尔就等于宣布「整条会话到顶了」；
 * - 会话级：跳转 / 换窗 / 退出重进都不该把它忘掉，忘掉的表现是滚到顶反复空问服务端；
 * - 每次连上清空：下界会变小（群主关「仅可见入群后」时服务端返回更低的位点），陈旧缓存会让该会话永久翻不上去且无提示。
 *
 * 与本机清空位点（[ConversationEntity.clearedUpTo][com.libeyond.imandroid.data.db.ConversationEntity.clearedUpTo]）**各自独立存**，
 * 用时才取大（[ChatTailPlan.visibleFrom]）：后者永不回退，前者会随群设置变化。线程安全（帧分派协程写、UI 协程读）。
 */
class HistoryFloors {
    private val floors = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun get(convId: String): Long = floors[convId] ?: 0L

    /** 记一个下界（含）。`≤0` 不参与；已有更小的就保留更小的。 */
    fun note(convId: String, floor: Long) {
        if (convId.isEmpty() || floor <= 0) return
        floors.merge(convId, floor) { cur, inc -> ChatTailPlan.mergeHistoryFloor(cur, inc) }
    }

    fun clear() = floors.clear()
}
