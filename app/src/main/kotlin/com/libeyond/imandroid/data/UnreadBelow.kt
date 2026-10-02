package com.libeyond.imandroid.data

/**
 * ↓N 徽标的取数判据（设计 §4.9 第 8 项；对称 Web `unreadBelow.ts` 的 `unreadBelowCount`、iOS `windowUnreadBelowCount`）。
 *
 * 抽成纯函数是因为这里错得**很安静**：数出来的 89 看着就是个正常数字，没有报错、没有空白，
 * 只是它本该是 10 万——此前 Android 一律数「已加载窗口里的行」，大群积压时恒等于窗口条数。
 *
 * 先问**区间清单**（正面证据）：已滚入位点到 tip 之间服务端给过的都在本地 → 下面一件不缺，数本地就是精确值；
 * 盖不住才退 `tip − pendingRead`（近似：会把本人消息 / 系统行也算进去，积压成千上万时这点偏差无意义，
 * 而「10 万条未读显示成 ↓89」会让人以为消息丢了）。
 * 不能用「head 与本地最新一条比」当正面证据：`msg_op` 事件行 / 墓碑 / 对我不可见的行占 conv_seq 却永远不成为消息，
 * 滚到底后 ↓ 会永远挂着一条不存在的未读（2026-09-05 实测）。
 */
object UnreadBelow {

    /**
     * @param tip           会话最新位点（[ChatTailPlan.tip]）；未知 `≤0` 时退回数本地——宁可偏小也不拿没依据的数糊弄
     * @param pendingRead   已滚入（看过）的位点
     * @param loadedBelow   已渲染消息里位于 [pendingRead] 之下的对端消息数
     * @param covered       区间清单是否**同一段**盖住 `(max(pendingRead, floor), tip]`
     * @param localNewest   本地已下载到的最大 conv_seq（清单没盖住时的第二条「本地不全」证据，不依赖连接级内存标志）
     * @param floor         有效可见下界（`visibleFrom - 1`）：`conv_seq ≤ floor` 的对本端不存在，不能算进 ↓N
     *                      （清空聊天记录后读位点仍在被清掉的那段里，`tip − pendingRead` 会把刚清掉的几千条算成「未读」）
     */
    fun count(tip: Long, pendingRead: Long, loadedBelow: Int, covered: Boolean, localNewest: Long, floor: Long): Int {
        if (tip > 0 && tip <= floor) return 0 // 可见范围内一条都没有：没有「下面还有」
        val pending = maxOf(pendingRead, floor) // 读位点落在下界以内 ⇒ 从下界数起
        if (tip > 0 && covered) return loadedBelow
        if (tip > 0 && tip > localNewest) return if (tip > pending) (tip - pending).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
        return loadedBelow
    }
}
