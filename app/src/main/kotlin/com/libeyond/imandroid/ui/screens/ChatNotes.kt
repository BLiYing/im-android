package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChatSearch
import com.libeyond.imandroid.data.SysSegments
import com.libeyond.imandroid.sdk.protocol.SysSegment
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

// 聊天流里**不是气泡**的那几种行：日期胶囊、系统消息、未读分割线，外加搜索命中的高亮工具。
// 从 Bubbles.kt 拆出（2026-09-10，十七条对齐第二轮给气泡加发送者头/记录卡点击后那份文件逼近 600 行）。

@Composable
internal fun DaySeparator(ts: Long) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            // 胶囊高 24、圆角 = 半高（UI_SPEC §3，iOS _datePillHeight/_datePill.cornerRadius 同值）
            modifier = Modifier
                .height(d.datePillHeight)
                .clip(RoundedCornerShape(d.datePillHeight / 2))
                .background(c.datePillBackground)
                .padding(horizontal = d.space3),
            contentAlignment = Alignment.Center,
        ) {
            Text(TimeFormat.dayLabel(ts), color = c.onMedia, fontSize = 11.sp)
        }
    }
}

/**
 * 系统消息：**居中灰字，不是气泡**（与 iOS `IMSystemCell` / Web `.sys-note` 同一形态）。
 *
 * 本端此前把 `content_type=system` 当成对方的普通消息画成左气泡，还占了群头像列——
 * 「光辉岁月 被设为管理员」显示成有人在说话。2026-09-07 实测发现。
 *
 * 字号走 `sysFontSize`（= 聊天字号 × 0.8，跟随用户设置），宽度上限 80%——
 * 与 Web `.sys-note span { max-width: 80% }` 同口径，长系统消息换行而不贴边。
 */
@Composable
internal fun SystemNote(
    text: String,
    /** 落库的分段 JSON；null/坏数据 → 回退整句（历史消息本来就没有分段）。 */
    sysSegments: String? = null,
    /** 按 App 语言重拼好的分段（`SysEvents.groupSegments`）；非 null 时优先于 [sysSegments]/[text]。 */
    localized: List<SysSegment>? = null,
    /** 名字段的本地显示名：uid → 备注/群昵称。返回 null 用服务端给的公开昵称。 */
    localName: (String) -> String? = { null },
    /** 点名字。不传则名字只染色不可点（与 iOS `onTapUID` 为空时同）。 */
    onTapUid: ((String) -> Unit)? = null,
    /** 本人 uid：名字段命中时恒显示「我」（对齐 iOS）。 */
    myUid: String? = null,
) {
    val c = IMTheme.colors
    val appearance = IMTheme.appearance
    val segs = remember(localized, sysSegments, text) { localized ?: SysSegments.render(sysSegments, text) }

    // 名字段用**琥珀色半粗**，不用 accent：胶囊底是主题绿，把名字染成同样是绿的 accent
    // 两者色相几乎重合，看不出哪几个字是名字（iOS 2026-08-30 用户反馈过）。
    // 也不用白——那与胶囊正文同色，只剩粗细之差。琥珀在绿胶囊与黑胶囊上都跳得出来。
    val annotated = remember(segs, sysSegments, myUid) {
        buildAnnotatedString {
            segs.forEach { seg ->
                if (!SysSegments.isName(seg)) {
                    append(seg.text)
                    return@forEach
                }
                val shown = SysSegments.displayName(seg, localName(seg.uid), null, myUid)
                pushStringAnnotation(SYS_NAME_TAG, seg.uid)
                withStyle(SpanStyle(color = SYS_NAME_COLOR, fontWeight = FontWeight.SemiBold)) {
                    append(shown)
                }
                pop()
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 胶囊：对齐 iOS `IMSystemCell` 的 _pill（圆角 11、datePillBg、内边距 10/4）
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(c.datePillBackground)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            ClickableText(
                text = annotated,
                style = TextStyle(
                    color = c.onMedia,
                    fontSize = appearance.sysFontSize,
                    textAlign = TextAlign.Center,
                ),
                onClick = { offset ->
                    // 点在名字上才响应，点在固定文案上不动作（同 iOS 的 TextKit 反查）
                    annotated.getStringAnnotations(SYS_NAME_TAG, offset, offset)
                        .firstOrNull()?.let { onTapUid?.invoke(it.item) }
                },
            )
        }
    }
}

/**
 * 把命中词底色标出来（会话内搜索，SEARCH_DESIGN §13.5）。
 *
 * 底色用 **accentSoft**（accent 的低透明版），**不硬编码黄**——三端同一条：
 * iOS `+[IMBubbleCell applySearchHighlight:toMutable:]`、im-web `<mark class="search-hit">`。
 * 位置判定在纯函数 [ChatSearch.matchRanges] 里（有单测），这里只负责画。
 */
internal fun highlightedText(
    text: String,
    needle: String,
    background: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    val ranges = ChatSearch.matchRanges(text, needle)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        for (r in ranges) {
            if (r.first > i) append(text.substring(i, r.first))
            withStyle(SpanStyle(background = background)) { append(text.substring(r.first, r.last + 1)) }
            i = r.last + 1
        }
        if (i < text.length) append(text.substring(i))
    }
}

/** 名字段的标注 tag 与颜色（iOS `datePillNameText` = #FFD98A）。 */
private const val SYS_NAME_TAG = "sysName"
private val SYS_NAME_COLOR = androidx.compose.ui.graphics.Color(0xFFFFD98A)

@Composable
internal fun UnreadDividerRow() {
    val c = IMTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(0.5.dp).background(c.separator))
        Text(
            text = stringResource(R.string.chat_unread_divider),
            color = c.textTertiary,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box(Modifier.weight(1f).height(0.5.dp).background(c.separator))
    }
}

/**
 * 被服务端明确拒收后，气泡下方的说明行（对齐 iOS `IMRejectNoteView`）：12sp 次要色居中；
 * [actionable]（目前只有 200103 非好友）时整行可点，并追加一行强调色「发送好友申请」。
 * 整行热区而非精确命中动作文字——12sp 逐字命中太难点，且这行没有别的可点元素。
 */
@Composable
internal fun RejectNote(text: String, actionable: Boolean, onAction: () -> Unit) {
    val c = IMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp)
            .then(if (actionable) Modifier.clickable(onClick = onAction) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = c.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
        if (actionable) {
            Text(
                stringResource(R.string.chat_system_send_friend_request),
                color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            )
        }
    }
}
