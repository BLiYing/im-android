package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.api.PinnedMessage
import com.libeyond.imandroid.sdk.protocol.ContentType
import java.util.Calendar

/** 置顶面板长按菜单的「允许项」（PINNED_PANEL_DESIGN §2.3），顺序即菜单顺序。转发排期再加。 */
enum class PinnedMenuAction { Unpin, Copy, Locate }

/**
 * 置顶面板（`ui/PinnedSheet.kt`）的**纯判据**：数据兜底、菜单过滤、权限显隐、图标左右、日期胶囊。
 * 对齐 iOS `IMPinnedPanelViewController` / Web `PinnedListModal`；UI 只管画。
 */
object PinnedPanel {

    /**
     * 一条置顶 → 气泡用的消息行。兜底顺序（设计 §4.0）：
     * ① 本地库已有该 conv_seq（聊天窗口里加载过，且没撤回）→ 本地行；
     * ② 接口带的完整消息对象 `message` → 走与 new_msg 同一份 [toEntity]；
     * ③ 都没有（旧服务端）→ 类型词纯文本气泡（[fallbackText]，一般是 [PinnedBanner.preview]）。
     */
    fun resolve(
        item: PinnedMessage,
        local: MessageEntity?,
        owner: String,
        convId: String,
        fallbackText: (PinnedMessage) -> String,
    ): MessageEntity {
        if (local != null && local.convSeq == item.convSeq && (local.recalledAt ?: 0L) <= 0L) return local
        item.message?.takeIf { it.convSeq == item.convSeq }?.let {
            return it.copy(convId = it.convId.ifEmpty { convId }).toEntity(owner)
        }
        return MessageEntity(
            ownerUid = owner, convId = convId, convSeq = item.convSeq, serverMsgId = item.serverMsgId,
            sender = item.sender, fromNickname = item.fromNickname.ifBlank { null },
            contentType = ContentType.TEXT, content = fallbackText(item), timestamp = item.timestamp,
            pinnedAt = item.pinnedAt,
        )
    }

    /** 长按菜单过滤：取消置顶（仅有权限）/ 复制（仅文本）/ 定位到消息。 */
    fun menuActions(msg: MessageEntity, canUnpin: Boolean): List<PinnedMenuAction> = buildList {
        if (canUnpin) add(PinnedMenuAction.Unpin)
        if (msg.contentType == ContentType.TEXT && msg.content.isNotEmpty()) add(PinnedMenuAction.Copy)
        add(PinnedMenuAction.Locate)
    }

    /** 标题栏副标题「长按消息可取消置顶」：仅有取消权限者。 */
    fun showsLongPressHint(canUnpin: Boolean): Boolean = canUnpin

    /** 底栏「取消所有置顶」：仅有权限且列表非空。 */
    fun showsUnpinAll(canUnpin: Boolean, count: Int): Boolean = canUnpin && count > 0

    /** 定位图标在气泡**左侧**（我发的）还是右侧（别人的）。 */
    fun locateIconLeading(mine: Boolean): Boolean = mine

    /** 长按「取消置顶」只摘所长按那一条（乐观移除，msg_op 回来后重拉对齐）。 */
    fun without(items: List<PinnedMessage>, seqs: Collection<Long>): List<PinnedMessage> =
        if (seqs.isEmpty()) items else items.filterNot { it.convSeq in seqs }

    /** 日期胶囊：首条必显，之后与上一条（列表顺序 = pinned_at 倒序）不同日才显。按本机时区。 */
    fun showsDayPill(prevTimestamp: Long?, timestamp: Long): Boolean =
        prevTimestamp == null || dayKey(prevTimestamp) != dayKey(timestamp)

    private fun dayKey(ts: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }
}
