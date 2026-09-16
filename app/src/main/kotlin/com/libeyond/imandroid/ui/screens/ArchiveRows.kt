package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Play
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.fileHint
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.components.DownloadBadge
import com.libeyond.imandroid.ui.components.FileTypeIcon
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.components.rememberGate
import com.libeyond.imandroid.ui.rememberFrostedPainter
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 归档里**一条项目长什么样**：语音行 / 链接行 / 文件行 / 媒体格，外加它们共用的长按手势。
 *
 * 从 `DetailArchive.kt` 平移出来（2026-09-16，那个文件到 495/600 行），**行为未改**。
 * 切口是「**画一条** vs **排布与调度**」：留在 `DetailArchive.kt` 的是页签条与
 * `archiveTab` / `mediaGrid` / `archiveList`（谁先谁后、空态、分页），搬到这里的是四类项目的具体画法。
 *
 * 四类共用 [archiveItemGestures]（长按要上报自己在窗口里的矩形，菜单贴着它弹）——
 * 这是它们必须待在一起的理由：各写一遍的话迟早有一处忘了记矩形，
 * 表现是"长按有反应但菜单弹在屏幕角落"。
 *
 * 单聊详情与群资料**共用这一份**：两页"长得一样"最可靠的保证不是各写一遍对着改，而是同一段代码画的。
 */

/**
 * 长按上报「这一项在窗口里的矩形」——菜单要贴着它弹（同气泡长按那套 `boundsInWindow()`）。
 *
 * 四类归档行/格共用这一个：各写一遍的话，迟早有一处忘了记矩形，
 * 表现是"长按有反应但菜单弹在屏幕角落"。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.archiveItemGestures(
    onClick: () -> Unit,
    onLongPress: ((Rect) -> Unit)?,
): Modifier {
    var rect by remember { mutableStateOf(Rect.Zero) }
    return this
        .onGloballyPositioned { rect = it.boundsInWindow() }
        .combinedClickable(onClick = onClick, onLongClick = onLongPress?.let { cb -> { cb(rect) } })
}

/**
 * 语音行。**点不响**——归档里播放要接进聊天页那套单例播放器（否则会同时响两处，iOS 是复用
 * `IMVoicePlayer sharedPlayer`，本端还没接）。但**长按可以**：定位回聊天、转发、删除。
 */
@Composable
internal fun VoiceRow(item: ConvMediaItem, onLongPress: ((Rect) -> Unit)? = null) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.surface)
                .archiveItemGestures(onClick = {}, onLongPress = onLongPress)
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
internal fun LinkRow(
    text: String,
    timestamp: Long,
    url: String,
    onLongPress: ((Rect) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.surface)
                .archiveItemGestures(onClick = onClick, onLongPress = onLongPress)
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

/** 媒体宫格的一格（供详情页内联复用）。 */
@Composable
internal fun ArchiveTile(
    item: ConvMediaItem,
    host: String,
    useTls: Boolean,
    isGroup: Boolean,
    onOpen: (ConvMediaItem) -> Unit,
    onLongPress: ((Rect) -> Unit)? = null,
) {
    Box(Modifier.aspectRatio(1f)) { MediaTile(item, host, useTls, isGroup, onOpen, onLongPress) }
}

@Composable
internal fun MediaTile(
    item: ConvMediaItem,
    host: String,
    useTls: Boolean,
    isGroup: Boolean,
    onOpen: (ConvMediaItem) -> Unit,
    onLongPress: ((Rect) -> Unit)? = null,
) {
    val c = IMTheme.colors
    val isVideo = item.contentType == ContentType.VIDEO
    // 视频这一格显示的是**封面**（小），门控作用在视频本体上；图片这一格门控的就是它自己
    val gate = rememberGate(item.content, item.contentType, item.fileSize, isGroup, autoPrefetch = false)
    Box(
        Modifier.aspectRatio(1f).background(c.subtleFill)
            .archiveItemGestures(onClick = { onOpen(item) }, onLongPress = onLongPress),
    ) {
        // 磨砂占位（M4-7）：一屏四列十几格全从空底开始加载最难看，这一格最该有它
        val frosted = rememberFrostedPainter(item.thumb)
        AsyncImage(
            // 视频用 poster：直接把视频 URL 交给 Coil 会去下整段再抽帧
            model = if (isVideo) {
                MediaUrl.absolute(item.poster, host, useTls).takeIf { item.poster.isNotBlank() }
            } else {
                gate.model
            },
            contentDescription = if (isVideo) "视频" else "图片",
            contentScale = ContentScale.Crop,
            placeholder = frosted,
            error = frosted,
            fallback = frosted,
            modifier = Modifier.fillMaxSize(),
        )
        if (!gate.ready && !isVideo) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DownloadBadge(gate.state, item.fileSize, gate.onTap, compact = true)
            }
        }
        if (isVideo) {
            Box(
                Modifier.align(Alignment.Center).size(28.dp).clip(CircleShape).background(c.overlay),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    imageVector = Lucide.Play,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    colorFilter = ColorFilter.tint(c.onMedia),
                )
            }
            if (item.duration > 0) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(3.dp)
                        .clip(RoundedCornerShape(3.dp)).background(c.overlay)
                        .padding(horizontal = 3.dp),
                ) {
                    Text(MediaUrl.formatDuration(item.duration), color = c.onMedia, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                }
            }
        }
    }
}

@Composable
internal fun FileRow(
    item: ConvMediaItem,
    isGroup: Boolean,
    onOpen: (ConvMediaItem) -> Unit,
    onLongPress: ((Rect) -> Unit)? = null,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val name = MediaUrl.displayFileName(item.content, item.fileName)
    // 详情页是"翻历史"，**不自动下**（对齐 iOS 的 autoPrefetchEnabled = NO）：
    // 一屏能列出几十个文件，自动下会在用户只想看一眼列表时静默拉走几百 MB。
    val gate = rememberGate(item.content, item.contentType, item.fileSize, isGroup, autoPrefetch = false)
    Column {
        Row(
            // 就绪 → 点开文件；没就绪 → 点一下等于点 ↓（对齐 iOS：整行在门控态下等价于点下载）
            Modifier.fillMaxWidth().background(c.surface)
                .archiveItemGestures(
                    onClick = { if (gate.ready) onOpen(item) else gate.onTap() },
                    onLongPress = onLongPress,
                )
                .padding(horizontal = d.space4, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                FileTypeIcon(name, size = 36.dp)
                // 文件行没有"图"可以磨砂，状态只能挂在这枚图标上（同 iOS `_fileIconWrap`）
                DownloadBadge(gate.state, sizeBytes = 0, onTap = gate.onTap, compact = true)
            }
            Spacer(Modifier.width(d.space3))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                )
                val size = MediaUrl.formatSize(item.fileSize)
                val when1 = TimeFormat.conversationTime(item.timestamp)
                Text(
                    listOf(size, when1).filter { it.isNotEmpty() }.joinToString(" · ") +
                        gate.state.phase.fileHint(),
                    color = c.textSecondary, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(c.separator))
    }
}
