package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/**
 * 「发个人名片」多选 + 发送确认的纯判据（对齐 iOS `IMChatViewController+Contact.m`：
 * `kIMContactMaxSelection` / `contactRestSummaryForCards:` / `presentContactSendConfirmForCards:` 的标题）。
 */
object ContactCardSend {

    /** 一次最多发几张名片（iOS `kIMContactMaxSelection`，与转发选择页同上限；不设上限「一次发 200 张」就是刷屏）。 */
    const val MAX_SELECTION = 9

    /** 折叠摘要里最多列几个名字，再多显「等 N 人」（iOS `names.count >= 3`）。 */
    const val REST_NAMES_MAX = 3

    /** 摘要里名字之间的分隔（iOS 「 · 」，不随语言变）。 */
    const val REST_SEPARATOR = " · "

    /** 选中顺序去重、丢空 uid、截到上限。多选页自己也会拦，这里是发送前的最后一道。 */
    fun normalize(uids: List<String>): List<String> =
        uids.filter { it.isNotBlank() }.distinct().take(MAX_SELECTION)

    /**
     * 确认 sheet 标题：1 张「发送名片给「X」」，多张「发送 N 张名片给「X」」。
     * @param to 目标会话显示名。
     */
    fun title(count: Int, to: String): String =
        if (count == 1) Str.s(R.string.contact_card_send_one, to) else Str.p(R.plurals.contact_card_send_many, count, count, to)

    /**
     * 首张之外的折叠摘要：「小红 · 老王」；超过 [REST_NAMES_MAX] 个在末尾加「 等 N 人」（N = 首张之外的总人数）。
     * 不足 2 张返回空串（不画这一行）。
     *
     * @param names 全部选中名片的**公开昵称**（顺序 = 选中顺序，首项是预览卡那一张）；
     *   调用方负责空昵称退到 uid 等兜底，同 iOS `nickname.length > 0 ? nickname : userID`。
     */
    fun restSummary(names: List<String>): String {
        if (names.size < 2) return ""
        val rest = names.drop(1)
        val shown = rest.take(REST_NAMES_MAX).joinToString(REST_SEPARATOR)
        return if (rest.size > REST_NAMES_MAX) {
            shown + Str.p(R.plurals.chat_contact_rest_more_count, rest.size, rest.size)
        } else {
            shown
        }
    }
}
