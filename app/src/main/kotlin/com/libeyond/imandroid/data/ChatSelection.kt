package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType

/**
 * 聊天页**多选态**的判据（M4-3 的另一半）。
 *
 * 对端 iOS `Modules/Chat/IMChatSelectionState.h` + `IMChatViewController+Selection.m`、
 * im-web `src/messageContent.ts`（`selectableInMultiSelect` / `SELECT_MAX`）与 `src/selection.ts`。
 * 结构清单见 `docs/UI_PARITY_IOS.md` §4.7。
 *
 * ### ⚠️ 勾选态**按 `conv_seq` 记，且要连消息一起存**
 * 这是 iOS 2026-09-06 用一个线上 bug 换来的结论，本端的窗口化列表**同样中招**，
 * 所以判据从一开始就照抄，不重走一遍：
 *
 * - **不能按行下标记**：向上翻页会在头部插 N 条，所有下标整体平移，勾选就指到别人身上了。
 *   iOS 那侧还叠了个 `reloadData` 清空行选中，表现是「勾两条 → 上滚拉历史 → 再勾一条，
 *   前两条静默消失、只剩 1 条」。
 * - **不能只存 seq**：勾过的消息会被窗口裁掉（本端 `ChatWindow.Tail` 只留 200 条、
 *   `Anchored` 前后各 100），只留 seq 的话「已选 N 条」与真正能转发的条数会对不上。
 *   存着实体则转发/删除都不必回查数据库。
 *
 * 所以本文件所有函数**都不接收窗口、行号或任何列表状态**——签名里拿不到，就没法退回去按行号记。
 */
object ChatSelection {

    /**
     * 一次最多勾几条。**一道闸管住转发/收藏/举报三件事**，不给每个动作各设一个数字：
     * 三套阈值 = 三套文案，用户记不住也难维护。不限的话「选 200 条 × 9 个会话」会串行发出 1800 条。
     * 服务端另有独立上限（举报 100 条/单、收藏 300 次/分），与本常量互不依赖。
     *
     * 值与 iOS `kIMSelectionMaxCount`、Web `SELECT_MAX` 同为 100（沿用 [Forward.MAX_SELECTION]，
     * 不另立一个常量——两个 100 早晚会漂成一个 100 一个 50）。
     */
    const val MAX = Forward.MAX_SELECTION

    /**
     * 多选态下这条能不能勾。**与 iOS `isSelectableMessage:` / Web `selectableInMultiSelect` 同语义**：
     * 系统提示、撤回墓碑、发送中·失败的本地件（`convSeq <= 0`，服务端没有它，转出去是空的）都不可选。
     *
     * 注意它比 [Forward.canForward] **宽**：那个还要求内容非空、未被删除。
     * 两者刻意分开——"能勾"是交互，"能转发"是动作前的复核，勾了却转不出去的那几条由
     * [forwardable] 在发送前滤掉并如实告诉用户（同 iOS：先数一次再发）。
     */
    fun selectable(msg: MessageEntity): Boolean =
        msg.convSeq > 0 &&
            (msg.recalledAt ?: 0L) <= 0L &&
            msg.contentType != ContentType.SYSTEM &&
            msg.contentType != ContentType.CALL   // 通话记录不可勾选（不可转发 / 收藏）

    /**
     * 勾选 / 取消勾选的**唯一写入口**。到上限就拒（返回 null），由调用方吐司说明——
     * **绝不静默吞掉这一下点击**。
     *
     * 取消永远允许：选满时若连取消都拒，用户就被卡死在"选满了又改不了"。
     */
    fun toggle(
        selected: Map<Long, MessageEntity>,
        msg: MessageEntity,
        max: Int = MAX,
    ): Map<Long, MessageEntity>? {
        if (!selectable(msg)) return selected          // 不可选的行点了当没发生
        if (selected.containsKey(msg.convSeq)) return selected - msg.convSeq
        if (selected.size >= max) return null
        return selected + (msg.convSeq to msg)
    }

    /**
     * 导出已选消息，**按 `conv_seq` 升序**（= 会话时序）。
     * 转发要按时序发、合并转发的卡片条目也要按时序排，所以顺序是判据不是巧合。
     */
    fun ordered(selected: Map<Long, MessageEntity>): List<MessageEntity> =
        selected.entries.sortedBy { it.key }.map { it.value }

    /** 标题栏文案：没勾时「选择消息」，勾了显条数（同 iOS `updateSelectionUI`）。 */
    fun titleOf(count: Int): String = if (count > 0) "已选择 $count 条" else "选择消息"

    /** 超限吐司文案。 */
    fun overflowNotice(max: Int = MAX): String = "最多选择 $max 条"

    /**
     * 这批里真正转得出去的几条（撤回/空内容/系统/未确认一律滤掉）。
     *
     * **发送前滤一次而不是勾选时就禁**：勾选判据宽一档，用户勾完点转发才发现少了几条会莫名其妙，
     * 所以调用方要拿 `ordered().size - forwardable().size` 的差额如实提示（同 iOS：先数一次再发）。
     */
    fun forwardable(msgs: List<MessageEntity>): List<MessageEntity> = msgs.filter(Forward::canForward)
}
