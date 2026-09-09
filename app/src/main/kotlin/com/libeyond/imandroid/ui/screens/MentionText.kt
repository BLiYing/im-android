package com.libeyond.imandroid.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.libeyond.imandroid.data.ChatSearch
import com.libeyond.imandroid.data.Mention
import com.libeyond.imandroid.sdk.protocol.MentionSpan

/**
 * 气泡正文的富文本：**@提及高亮（可点）+ 会话内搜索命中底色**。
 *
 * 两种装饰互相正交、可以叠在同一段字上（搜「开会」而那句正好是「@小明 开会」），
 * 所以这里是一次遍历同时铺两层，而不是先切段再各画各的。
 *
 * ### 走哪条路由 iOS 那侧的判据决定（`IMBubbleCell.attributedContent:...spans:`）
 * **有可用片段就走片段**——位置由发送方给出，不查任何成员表；超级群（2 万人）不下发
 * 成员表，下面那条老路在那里对普通成员必然失效。片段与本地文本对不上（编辑过的老消息、
 * 脏数据）会被 [Mention.validSpans] 逐段丢掉，**一段不剩才回落**按昵称扫文本。
 *
 * ### 点击
 * 用 `LinkAnnotation.Clickable` 而不是 `ClickableText`：后者已废弃，而且它自己吃掉 tap
 * 手势，气泡的长按菜单会跟着失灵。`@所有人` 的 uid 为空 —— **只高亮不可点**（同 iOS）。
 */
internal fun chatBodyText(
    text: String,
    /** 落库的片段（已解析）。空表 = 老消息/老客户端，走昵称老路。 */
    spans: List<MentionSpan>,
    /** 老路用的本群成员显示名。拿不到（超级群）就传空表：那时 @ 不高亮，与协议里写的降级一致。 */
    memberNames: List<String>,
    /** 会话内搜索的命中词；空串 = 不在搜索态。 */
    searchNeedle: String,
    highlightBackground: Color,
    mentionColor: Color,
    /** 点 @某人。null = 不可点（如长按菜单里的原位重绘，那时不该再响应点击）。 */
    onTapMention: ((String) -> Unit)?,
): AnnotatedString {
    val valid = Mention.validSpans(text, spans)
    val segs =
        if (valid.isNotEmpty()) Mention.segmentBySpans(text, valid)
        else Mention.segmentByNames(text, memberNames)
    val hits = ChatSearch.matchRanges(text, searchNeedle)
    // 只有"既没有提及、也没有命中"才走快速通道。
    // 原来写的是 `segs.size <= 1`——而**整条正文就是一段提及**时（选完人直接发，
    // 正文恰好是 `@小明`）也只有一段，于是那条消息不高亮也点不动。
    // 真机那次验的是「@用户3472 kaihui」，两段，正好绕过了这个洞（2026-09-09 `/code-review` 抓出）。
    if (hits.isEmpty() && segs.none { it.mention }) return AnnotatedString(text)

    val mentionStyle = SpanStyle(color = mentionColor, fontWeight = FontWeight.Medium)
    return buildAnnotatedString {
        var at = 0
        for (seg in segs) {
            val start = at
            val end = at + seg.text.length
            val uid = seg.uid
            when {
                seg.mention && uid != null && onTapMention != null ->
                    withLinkCompat(uid, mentionStyle, onTapMention) { appendHighlighted(seg.text, start, hits, highlightBackground) }
                seg.mention ->
                    withStyle(mentionStyle) { appendHighlighted(seg.text, start, hits, highlightBackground) }
                else -> appendHighlighted(seg.text, start, hits, highlightBackground)
            }
            at = end
        }
    }
}

/**
 * 把 [piece] 追加进来，并给落在它身上的搜索命中铺底色。
 *
 * [hits] 是**相对整段正文**的区间，所以要拿 [pieceStart] 换算回段内坐标——
 * 直接用整段坐标去切子串是这段最容易写错的地方（命中会整体偏移到别的字上）。
 */
private fun AnnotatedString.Builder.appendHighlighted(
    piece: String,
    pieceStart: Int,
    hits: List<IntRange>,
    background: Color,
) {
    if (hits.isEmpty()) {
        append(piece)
        return
    }
    val pieceEnd = pieceStart + piece.length
    var i = 0
    for (h in hits) {
        val from = maxOf(h.first, pieceStart)
        val to = minOf(h.last + 1, pieceEnd)
        if (from >= to) continue
        val localFrom = from - pieceStart
        val localTo = to - pieceStart
        if (localFrom > i) append(piece.substring(i, localFrom))
        withStyle(SpanStyle(background = background)) { append(piece.substring(localFrom, localTo)) }
        i = localTo
    }
    if (i < piece.length) append(piece.substring(i))
}

/** `withLink` 的一层薄封装：把样式与点击绑在一起，免得每处都拼一遍 [TextLinkStyles]。 */
private inline fun AnnotatedString.Builder.withLinkCompat(
    uid: String,
    style: SpanStyle,
    noinline onTap: (String) -> Unit,
    block: AnnotatedString.Builder.() -> Unit,
) {
    pushLink(
        LinkAnnotation.Clickable(
            tag = uid,
            styles = TextLinkStyles(style = style),
            linkInteractionListener = { onTap(uid) },
        ),
    )
    block()
    pop()
}
