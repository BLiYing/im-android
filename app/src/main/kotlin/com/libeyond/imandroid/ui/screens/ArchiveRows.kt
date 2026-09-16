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
/**
 * 只为装一个「稍后在回调里读一次」的值。
 *
 * **不用 `mutableStateOf`**：`onGloballyPositioned` 在滚动时每一帧都回调，
 * 而这个矩形没有任何**组合期**读取方（只在长按回调里读），进快照系统纯属白开销。
 * （注：它也**不是**「详情页划不动」的原因——那条实测下来是宫格里的图全被门控挡成了空格子，
 * 见 [MediaTile] 的注释；这里只是顺手把无谓的每帧状态写去掉。）
 */
internal class RectHolder {
    var value: Rect = Rect.Zero
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.archiveItemGestures(
    onClick: () -> Unit,
    onLongPress: ((Rect) -> Unit)?,
): Modifier {
    val rect = remember { RectHolder() }
    return this
        .onGloballyPositioned { rect.value = it.boundsInWindow() }
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongPress?.let { cb -> { cb(rect.value) } },
        )
}

/**
 * 语音行。**三行结构，逐条对齐 iOS** `IMChatDetailViewController.decorateVoiceRow3Cell:`
 * （发送者名 / 迷你播放器（波形 + 时长）/ 完整时间，行高 106pt）：
 *
 * 1. **发送者名**——此前本端整行只有"时长 + 相对时间"，看不出是谁发的，而语音恰恰最需要这个；
 * 2. **波形**：直接复用聊天页语音气泡那一份 [VoiceContent]（iOS 那侧同样是复用
 *    `IMVoiceMiniPlayerView`）。两份实现必然在柱数/归一化上分叉，而那没有任何自动手段能发现；
 * 3. **完整年月日时分**（[TimeFormat.fileDateTime]，同 iOS `IMFormatFileDateTime`）。
 *
 * **仍与 iOS 差一条：点不响。** iOS 那行的 ▶ 与波形都能就地播（`IMVoicePlayer sharedPlayer`），
 * 本端**整个 App 还没有语音播放器**（聊天页的语音气泡同样只画波形），
 * 所以这里刻意不画播放按钮——画一个按下去没反应的 ▶ 比没有更糟。页签脚注 [VOICE_TAB_NOTE] 如实说了这件事。
 *
 * 长按仍可用：定位回聊天、转发、删除。
 *
 * @param senderName 发送者显示名；空则不画第一行（拿不到成员表时，比如超级群）。
 * @param waveform   振幅指纹。**服务端归档接口不回带这个字段**，由调用方从本地消息表按
 *   `conv_seq` 兜底取；取不到就是等高条纹（协议允许的合法状态，见 [com.libeyond.imandroid.data.Waveform]）。
 */
@Composable
internal fun VoiceRow(
    item: ConvMediaItem,
    senderName: String = "",
    waveform: String? = null,
    onLongPress: ((Rect) -> Unit)? = null,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.surface)
                .archiveItemGestures(onClick = {}, onLongPress = onLongPress)
                .padding(horizontal = d.space4, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (senderName.isNotBlank()) {
                    Text(
                        senderName,
                        color = c.textPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                VoiceContent(item.duration.toLong(), waveform)
                Text(
                    TimeFormat.fileDateTime(item.timestamp),
                    color = c.textTertiary, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = d.space4).height(0.5.dp).background(c.separator))
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
                // **三行**（对齐 iOS `IMLinkRowView`：t1 标题 / t2 host+path 等宽 / t3 完整时间）。
                // iOS 的 t1 优先用 og:title，拿不到才回落 host——本端归档里不逐行拉预览
                // （一屏几十条各发一次 HTTP），所以 t1 用**原文摘要**、没有摘要才回落 host。
                Text(
                    text.trim().takeIf { it.isNotEmpty() && it != url } ?: hostOf(url),
                    color = c.textPrimary, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    url, color = c.accent, style = MaterialTheme.typography.bodyMedium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(TimeFormat.fileDateTime(timestamp),
                    color = c.textTertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(c.separator))
    }
}

/**
 * 链接行第一行的兜底：URL 里的 host（iOS `IMLinkRowView` 的 t1 在没有 og:title 时同样回落 host）。
 * 纯字符串切分，不解析 URL——这里只要给人看，解析失败也不该抛。
 */
private fun hostOf(url: String): String =
    url.substringAfter("://", url).substringBefore('/').ifBlank { url }

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
        // 磨砂占位（M4-7）：一屏十几格全从空底开始加载最难看，这一格最该有它
        val frosted = rememberFrostedPainter(item.thumb)
        AsyncImage(
            // 视频用 poster：直接把视频 URL 交给 Coil 会去下整段再抽帧。
            //
            // 图片**一律用远端地址，不走 `gate.model`**（2026-09-17 用户报「媒体库里看不到图」）：
            // 门控未就绪时 `gate.model` 是 null，于是整个归档宫格只剩一片磨砂 + 一排 ↓ 徽标，
            // 而这一页的**全部意义**就是让人一眼扫过去找那张图。iOS 两处宫格
            // （`IMConversationMediaViewController` / `IMDetailMediaContainerCell` 里的
            // `IMMediaTileCell`）都是直接按 URL 加载缩略，门控只作为**盖在上面的状态层**存在
            // （`autoPrefetchEnabled = NO` 管的是"不自动整包预取原件"，不是"不显示这张图"）。
            //
            // 聊天气泡那侧**仍然守门控**（`MediaBubbles.ImageContent` 的 `gate.model`）——
            // 那里一屏只有一两张、且紧跟着就是原图查看，与"翻历史找图"不是一回事。
            model = if (isVideo) {
                MediaUrl.absolute(item.poster, host, useTls).takeIf { item.poster.isNotBlank() }
            } else {
                MediaUrl.absolute(item.content, host, useTls).takeIf { item.content.isNotBlank() }
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
            // **三行**（对齐 iOS `IMDetailFileCell`：文件名 / 状态副行 / 时间行，行高 74pt）：
            // 此前是两行、且把时间挤进了状态行，于是"1.3 MB · 昨天 · 已下载"读起来像一句话，
            // 时间还用的相对口径（2026-09-17 对齐 iOS 时改的）。
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                )
                Text(
                    MediaUrl.formatSize(item.fileSize) + gate.state.phase.fileHint(),
                    color = c.textSecondary, style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    TimeFormat.fileDateTime(item.timestamp),
                    color = c.textTertiary, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(c.separator))
    }
}
