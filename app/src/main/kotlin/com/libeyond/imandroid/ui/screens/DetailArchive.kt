package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.libeyond.imandroid.data.DetailTab
import com.libeyond.imandroid.data.DetailTabs
import com.libeyond.imandroid.data.LinkScan
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 详情页的内联页签条（对齐 iOS 的 `pillsView` / `IMLiquidSegmentedControl`）。
 *
 * **归档在详情页内切 tab，不跳出去**——这是 iOS 的形态，也是本端 2026-09-08 之前
 * 与 iOS 差得最远的一处（当时是一行「聊天媒体」push 出去一整页）。
 */
@Composable
internal fun DetailTabBar(tabs: List<DetailTab>, current: DetailTab, onSelect: (DetailTab) -> Unit) {
    val c = IMTheme.colors
    // **底轨 + 药丸**（对齐 iOS `IMLiquidSegmentedControl`：track 玻璃、pill 浮在上面）。
    // 起初这里只有一排裸按钮、选中态是 12% 绿底 + 绿字：深色模式下几乎看不出选了哪个
    // （2026-09-08 用户报的「选中态颜色太暗」）。iOS 的做法是**选中与未选中同为主文字色，
    // 只靠字重与药丸底色区分**——照抄这一条，别再用低透明度主色去表达"选中"。
    Row(
        Modifier.fillMaxWidth().padding(horizontal = IMTheme.dimens.space4, vertical = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.subtleFill)
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { t -> MediaSeg(DetailTabs.title(t), t == current) { onSelect(t) } }
    }
}

/**
 * 语音行。**不可点**——归档里播放要接进聊天页那套单例播放器（否则会同时响两处），
 * 「定位到聊天」也还没有（本端没有跳转到指定 conv_seq 的能力）。
 * 与其给一个点了没反应的行，不如不给点击态，并在页签下注明。
 */
@Composable
internal fun VoiceRow(item: ConvMediaItem) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.surface)
                .padding(horizontal = d.space4, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(c.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    Lucide.Mic, "语音", Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.accent),
                )
            }
            Spacer(Modifier.width(d.space3))
            Column(Modifier.weight(1f)) {
                Text(
                    MediaUrl.formatDuration(item.duration).ifBlank { "语音" },
                    color = c.textPrimary, style = MaterialTheme.typography.bodyLarge,
                )
                Text(TimeFormat.conversationTime(item.timestamp),
                    color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(c.separator))
    }
}

/** 链接行。左边一枚图标 + URL + 原文摘要。 */
@Composable
internal fun LinkRow(text: String, timestamp: Long, url: String, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.surface).clickable(onClick = onClick)
                .padding(horizontal = d.space4, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(c.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    Lucide.Link, "链接", Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.accent),
                )
            }
            Spacer(Modifier.width(d.space3))
            Column(Modifier.weight(1f)) {
                Text(url, color = c.accent, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                // 原文与 URL 不同才显摘要——整条就是个链接时再显一遍是纯噪音
                if (text.trim() != url) {
                    Text(text, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(TimeFormat.conversationTime(timestamp),
                    color = c.textTertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(c.separator))
    }
}

/**
 * 「链接」页签的脚注。**必须说清楚它只覆盖本地已加载的消息**——
 * 服务端没有可索引的链接列（`internal/conversation/media.go`），这一格只能扫本地文本，
 * 与其他几格的"全量"语义不同。不说的话用户会以为链接丢了。
 */
internal const val LINK_TAB_NOTE = "链接由本机已加载的聊天记录扫出，往上翻得越多、这里越全。点一条用浏览器打开。"

/** 语音页签的脚注。如实写清楚这一格现在能做什么、不能做什么。 */
internal const val VOICE_TAB_NOTE = "归档里暂不能播放，也还不能定位回聊天。"

/** 判定一条本地消息是不是链接（薄封装，方便调用点读起来短）。 */
internal fun linkUrlOf(contentType: String, content: String, convSeq: Long): String? =
    if (LinkScan.isLinkMessage(contentType, content, convSeq)) LinkScan.firstUrl(content) else null

/** 媒体宫格的一格（供详情页内联复用）。 */
@Composable
internal fun ArchiveTile(
    item: ConvMediaItem,
    host: String,
    useTls: Boolean,
    isGroup: Boolean,
    onOpen: (ConvMediaItem) -> Unit,
) {
    Box(Modifier.aspectRatio(1f)) { MediaTile(item, host, useTls, isGroup, onOpen) }
}
