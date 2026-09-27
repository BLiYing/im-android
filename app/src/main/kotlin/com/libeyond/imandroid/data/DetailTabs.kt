package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.MediaKind
import com.libeyond.imandroid.sdk.protocol.ContentType

/**
 * 会话详情页的内联页签（对齐 iOS `IMChatDetailTabs` 的 `IMDetailTabKind`）。
 *
 * **归档做成页签而不是"点进去一页"**，是 iOS 的形态：详情页本身就是归档索引，
 * 切 tab 不离开页面。本端 2026-09-08 之前是一行「聊天媒体」跳出去，与 iOS 差得最远的一处。
 */
enum class DetailTab { Members, Media, Files, Voice, Links }

object DetailTabs {

    /** 单聊没有「成员」。顺序即 iOS 的页签顺序。 */
    fun visible(isGroup: Boolean): List<DetailTab> = buildList {
        if (isGroup) add(DetailTab.Members)
        add(DetailTab.Media)
        add(DetailTab.Files)
        add(DetailTab.Voice)
        add(DetailTab.Links)
    }

    fun title(tab: DetailTab): String = when (tab) {
        DetailTab.Members -> Str.s(R.string.group_member_tab_label)
        DetailTab.Media -> Str.s(R.string.favorites_category_media)
        DetailTab.Files -> Str.s(R.string.common_file)
        DetailTab.Voice -> Str.s(R.string.favorites_category_voice)
        DetailTab.Links -> Str.s(R.string.favorites_category_links)
    }

    /**
     * 这个页签走服务端归档接口时用哪个 `kind`；`null` = 不走接口。
     *
     * **「链接」为什么是 null**：链接不是独立的 `content_type`，是从文本里识别出来的，
     * 服务端没有可索引的列（`internal/conversation/media.go` 开头写明这是接口不覆盖的一格）。
     * iOS 同样是本地扫（`IMFirstURLInText`）。**成员**也不是消息，走群成员接口。
     */
    fun apiKind(tab: DetailTab): String? = when (tab) {
        DetailTab.Media -> MediaKind.MEDIA
        DetailTab.Files -> MediaKind.FILE
        DetailTab.Voice -> MediaKind.VOICE
        DetailTab.Links -> null
        DetailTab.Members -> null
    }

    /**
     * 空态文案。**逐字对齐 iOS** `IMChatDetailViewController` 的 `tabCell:` / `emptyCell:`
     * （「暂无媒体 / 暂无文件 / 暂无语音 / 暂无链接」）——本端此前是自己另写的一套长句
     * （"这个会话还没有图片或视频"），同一个页签两端文案不同（2026-09-17 对照源码核出来的）。
     */
    fun emptyText(tab: DetailTab): String = when (tab) {
        DetailTab.Members -> Str.s(R.string.detail_tab_empty_members)
        DetailTab.Media -> Str.s(R.string.detail_tab_empty_media)
        DetailTab.Files -> Str.s(R.string.detail_tab_empty_files)
        DetailTab.Voice -> Str.s(R.string.detail_tab_empty_voice)
        DetailTab.Links -> Str.s(R.string.detail_tab_empty_links)
    }
}

/**
 * 从文本里挑出第一个 URL（对齐 iOS `IMFirstURLInText`）。
 *
 * **混排文本也算**：「看看 https://x.com 这个」要进链接页签。iOS 早期只认「整段 = URL」，
 * 会漏掉混排，后来改成了这个口径（见 `IMChatDetailTabs` 的注释）。
 */
object LinkScan {

    private val SCHEMES = listOf("https://", "http://")

    /** 结尾常见的中英文标点不算 URL 的一部分——不剥的话「(见 https://x.com)」会带上右括号。 */
    private const val TRAILING = ".,;:!?)）]】》\"'”’、。！？"

    /**
     * URL 里允许出现的字符（RFC 3986 的 unreserved + reserved + `%`）。
     *
     * **按字符集截断而不是"截到下一个空格"**：中文根本不打空格。
     * 「去 https://x.com，然后」按空格截会把「，然后」一起吞进 URL——
     * 写这段时的第一版就是这么写的，被单测当场抓住。
     * iOS 那边用 `NSDataDetector`，天然没有这个问题。
     */
    private fun isUrlChar(ch: Char): Boolean =
        ch.code < 128 && (ch.isLetterOrDigit() || ch in "-._~:/?#[]@!$&'()*+,;=%")

    fun firstUrl(text: String): String? {
        if (text.isBlank()) return null
        var best: Int = -1
        for (s in SCHEMES) {
            val i = text.indexOf(s, ignoreCase = true)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        if (best < 0) return null
        var end = best
        while (end < text.length && isUrlChar(text[end])) end++
        val raw = text.substring(best, end)
        val trimmed = raw.trimEnd { it in TRAILING }
        // `https://` 后面什么都没有不算链接
        return trimmed.takeIf { u -> SCHEMES.none { it.equals(u, ignoreCase = true) } && u.length > 8 }
    }

    fun hasUrl(text: String): Boolean = firstUrl(text) != null

    /**
     * 一条消息算不算「链接」。
     *
     * 与 iOS 同口径：独立的 `link` 类型，或**含 URL 的普通文本**。
     * 撤回/删除墓碑与未确认消息不进归档（归档是"索引"视角，iOS `matchesKind:` 也这么判）。
     */
    fun isLinkMessage(contentType: String, content: String, convSeq: Long): Boolean {
        if (convSeq <= 0) return false
        return when (contentType) {
            ContentType.TEXT -> hasUrl(content)
            "link" -> content.isNotBlank()
            else -> false
        }
    }
}
