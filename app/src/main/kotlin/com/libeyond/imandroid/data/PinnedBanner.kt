package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.api.PinnedMessage
import com.libeyond.imandroid.sdk.protocol.ContentType

/**
 * 聊天页顶部横幅（置顶 / 公告 / 入群申请）的**纯判据**，对齐 iOS `IMPinnedMessage` / `IMChatBannerStack`
 * / `canPinMessages`。全是纯函数以便单测——UI 只管画。
 */
object PinnedBanner {

    /**
     * 我能不能置顶/取消置顶。**单聊双方都可以；群聊看 `perm_pin`**：开着（默认，「仅管理员可置顶」）只有群主/管理员，
     * 关着所有成员。服务端仍是权威（越权回 300006/300204），这里只决定菜单显不显。
     * 此前 iOS 一度写死「仅 owner/admin」不看 `perm_pin`（2026-08-13 修），本端从一开始就读它。
     */
    fun canPin(isGroup: Boolean, permPin: Boolean, myRole: String?): Boolean =
        !isGroup || !permPin || myRole == GroupMember.ROLE_OWNER || myRole == GroupMember.ROLE_ADMIN

    /** 菜单项该显「置顶」还是「取消置顶」，或都不显（null）。判据见 [MessageActions]。 */
    fun pinAction(msg: com.libeyond.imandroid.data.db.MessageEntity, canPin: Boolean): MessageAction? {
        if (!canPin || msg.convSeq <= 0) return null
        // 已置顶的**撤回墓碑也要能取消**——否则一条撤回后的陈旧置顶没法清
        if ((msg.pinnedAt ?: 0L) > 0L) return MessageAction.Unpin
        val recalled = (msg.recalledAt ?: 0L) > 0L
        return if (!recalled && msg.contentType != ContentType.SYSTEM) MessageAction.Pin else null
    }

    /** 横幅第二行：一行预览（空白折叠成单个空格）。顺序与 iOS `previewText` 同：图说 > 类型占位 > 正文。 */
    fun preview(item: PinnedMessage): String {
        val body = if (item.contentType == ContentType.CHAT_RECORD) {
            val title = CardContent.parseRecord(item.content, maxLines = 0)?.title.orEmpty()
            Str.s(R.string.preview_chat_record) + if (title.isNotBlank()) " $title" else ""
        } else {
            MessagePreview.of(item.contentType, item.content, item.caption.ifBlank { null })
        }
        val oneLine = oneLine(body)
        if (oneLine.isNotEmpty()) return oneLine
        return if (item.contentType == ContentType.TEXT) Str.s(R.string.preview_empty_message) else "[${item.contentType}]"
    }

    /** 发送者标签：单聊不显；群聊 = 群昵称快照，没有就退 uid。 */
    fun senderLabel(item: PinnedMessage, isGroup: Boolean): String =
        if (!isGroup) "" else item.fromNickname.ifBlank { item.sender }

    /** 空白（含换行）折叠成单个空格并 trim。 */
    fun oneLine(s: String): String = s.replace(WS, " ").trim()
    private val WS = Regex("\\s+")

    /**
     * 「收起」签名（决策 20）：收起是**本机视图偏好**，不取消置顶、不撤公告。按内容签名记——内容一变就自动重新出现。
     * 置顶 = `条数:最新一条的 conv_seq`；公告 = 全文；待审 = 件数。
     */
    fun pinSignature(items: List<PinnedMessage>): String = if (items.isEmpty()) "" else "${items.size}:${items.first().convSeq}"

    /** 当前签名等于已收起的才隐藏；没收起过（null）或不同（内容变了）都显示。 */
    fun dismissed(current: String, stored: String?): Boolean = current.isNotEmpty() && current == stored

    /** 偏好键：带账号与会话，互不串（对齐 iOS `im_banner_dismiss_<kind>_<uid>_<conv>`）。 */
    fun dismissKey(kind: String, uid: String, convId: String) = "im_banner_dismiss_${kind}_${uid}_$convId"

    /** 轮播下标夹取：别人取消置顶缩短了列表时回到 0。 */
    fun clampIndex(index: Int, total: Int): Int = if (index < 0 || index >= total) 0 else index

    /** 点横幅：先跳到当前这条，再把下标推进到下一条（循环），连点就是逐条轮播。 */
    fun nextIndex(index: Int, total: Int): Int = if (total <= 0) 0 else (index + 1) % total

    /** 入群申请横幅要不要显：仅群主/管理员，且有待审。服务端本就只对管理层下发 `pending_count`，这里再兜一道。 */
    fun approvalCount(info: GroupInfo?): Int = if (info != null && info.iAmManager) info.pendingCount else 0

    /**
     * 进群自动弹一次公告：公告非空、有发布时间、比本机记的「已看版本」新；**我自己发布的不弹**（只记版本）。
     * @return 是否弹。无论弹不弹，调用方都要在「页面可见」时把 [GroupInfo.announcementAt] 记成已看。
     */
    fun shouldAutoPopAnnouncement(info: GroupInfo?, myUid: String, lastSeenAt: Long): Boolean =
        info != null && info.announcement.isNotBlank() && info.announcementAt > 0 &&
            info.announcementAt > lastSeenAt && info.announcementBy != myUid

    /** 公告「收起」签名：正文哈希（不把整段公告长存偏好文件）；空公告 = 空串。 */
    fun announcementSignature(text: String?): String = text?.takeIf { it.isNotBlank() }?.hashCode()?.toString().orEmpty()

    /** 公告内容的单行预览。 */
    fun announcementPreview(info: GroupInfo?): String = oneLine(info?.announcement.orEmpty())

    /** 目标消息是不是已撤回（跳转前判，避免落在一行「撤回了一条消息」上闪一下）。 */
    fun targetRecalled(row: com.libeyond.imandroid.data.db.MessageEntity?): Boolean = (row?.recalledAt ?: 0L) > 0L
}
