package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ContentType

/**
 * 会话列表副标题——**在渲染那一刻现算**，不是 [ConversationEntity.lastContent] 直接显示。
 *
 * 对齐 iOS `IMConversation.lastPreviewTextForSelfUID:` + 列表 cell 的群/单聊两个分支、
 * Web `convPreview()`（2026-09-22 用户报：安卓群聊列表没有"昵称: "前缀）。
 *
 * `lastContent` 本身仍在写库那一刻由 [MessagePreview.of] 烤好（正文/占位符/通话文案……），
 * 这里只再叠两层、且必须现算才对：
 * - **撤回**：本地收到 `msg_op RECALL` 只翻 [ConversationEntity.lastRecalled] 一个位
 *   （[MessageRepository.applyMsgOp]），文案在这里现拼，撤回才能立刻反映到列表上。
 * - **群聊"谁发的"前缀**：要用**当前**备注/昵称（改备注后旧预览也要跟着变），
 *   烤进 `lastContent` 的话就成了改备注前的快照，一直不刷新。
 */
object ConversationPreview {

    /**
     * @param myUid 我的 uid——判断"我发的"显示「我」。
     * @param nameOf 本机对某 uid 的显示名（备注 > 昵称），取不到回 null——
     *   由本函数回退到服务端快照里的 [ConversationEntity.lastFromNickname]，再退到 uid 本身
     *   （与 iOS `IMRemarkStore displayNameForUser:fallback:` 同一条退化路径）。
     */
    fun of(conv: ConversationEntity, myUid: String, nameOf: (String) -> String?): String {
        if (conv.lastRecalled) {
            return when {
                conv.lastFrom == myUid -> Str.s(R.string.conv_list_recalled_self)
                conv.isGroup && conv.lastFrom.isNotBlank() -> Str.s(R.string.conv_list_recalled_member, displayNameOf(conv, nameOf))
                conv.isGroup -> Str.s(R.string.conv_list_recalled_unknown)
                else -> Str.s(R.string.conv_list_recalled_peer)
            }
        }

        // 结构化事件（P3）按当前语言现算：群系统消息 → 模板 + 服务端公开昵称；系统通知单聊 → 多行正文（列表截断）。
        // 不认识/为空回退烤好的 lastContent（服务端中文整句）
        val body = sysEventPreview(conv) ?: conv.lastContent
        if (body.isBlank()) return Str.s(R.string.conv_list_no_message)

        // 群聊文本/媒体一律带"昵称: "前缀；系统消息（无真实发送者）与 lastFrom 为空的（老数据/系统通知）
        // 不加——同 iOS「who 解析不出来就不包前缀」的退化路径，不必对 system 单独判一遍。
        if (!conv.isGroup || conv.lastContentType == ContentType.SYSTEM || conv.lastFrom.isBlank()) return body

        val who = if (conv.lastFrom == myUid) Str.s(R.string.common_me) else displayNameOf(conv, nameOf)
        return Str.s(R.string.conv_list_sender_prefix, who, body)
    }

    private fun sysEventPreview(conv: ConversationEntity): String? {
        if (conv.lastSysEvent.isEmpty()) return null
        return if (conv.lastContentType == ContentType.SYSTEM) {
            SysEvents.groupText(conv.lastSysEvent, conv.lastSysArgs, conv.lastSysSegments)
        } else if (DetailActions.isSystemPeer(conv.lastFrom)) {
            SysEvents.noticeText(conv.lastSysEvent, conv.lastSysArgs)
        } else {
            null
        }
    }

    private fun displayNameOf(conv: ConversationEntity, nameOf: (String) -> String?): String =
        nameOf(conv.lastFrom)?.takeIf { it.isNotBlank() }
            ?: conv.lastFromNickname.ifBlank { conv.lastFrom }
}
