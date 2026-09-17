package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.DownloadLabels
import com.libeyond.imandroid.data.DownloadPhase
import com.libeyond.imandroid.data.DownloadPolicy
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.components.AlbumTileGate
import com.libeyond.imandroid.ui.components.FileGateSlot
import com.libeyond.imandroid.ui.components.LocalMediaGate
import com.libeyond.imandroid.ui.components.MiddleEllipsisText
import com.libeyond.imandroid.ui.components.TileDurationChip
import com.libeyond.imandroid.ui.components.VideoPlayBadge
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
    // **只记坐标对象、长按那一刻才换算矩形**（2026-09-17「详情页很卡」一并收的）：
    // `onGloballyPositioned` 在滚动时对屏上每一格每帧都回调，此前在回调里就地 `boundsInWindow()`
    // ——一屏十几格、每格每帧沿祖先链做一遍坐标变换，算出来的矩形绝大多数永远没人读。
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onGloballyPositioned { coords[0] = it }
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongPress?.let { cb ->
                { cb(coords[0]?.takeIf { it.isAttached }?.boundsInWindow() ?: Rect.Zero) }
            },
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
    /** 「来自X」那一行（收藏页）；空串不画。 */
    source: String = "",
    onLongPress: ((Rect) -> Unit)? = null,
    /** 行尾槽（「从收藏发送」的勾选框，见 [PickCheckButton]）；null 不画。 */
    trailing: (@Composable () -> Unit)? = null,
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
                SourceLine(source)
            }
            trailing?.invoke()
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
    /** 「来自X」那一行（收藏页）；空串不画。 */
    source: String = "",
    onLongPress: ((Rect) -> Unit)? = null,
    /** 行尾槽（「从收藏发送」的勾选框）；null 不画。 */
    trailing: (@Composable () -> Unit)? = null,
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
                SourceLine(source)
            }
            trailing?.invoke()
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
    /** 「从收藏发送」：这一格勾没勾（null = 不在选择模式，不画勾选框）。 */
    picked: Boolean? = null,
    onTogglePick: () -> Unit = {},
) {
    Box(Modifier.aspectRatio(1f)) { MediaTile(item, host, useTls, isGroup, onOpen, onLongPress, picked, onTogglePick) }
}

@Composable
internal fun MediaTile(
    item: ConvMediaItem,
    host: String,
    useTls: Boolean,
    isGroup: Boolean,
    onOpen: (ConvMediaItem) -> Unit,
    onLongPress: ((Rect) -> Unit)? = null,
    picked: Boolean? = null,
    onTogglePick: () -> Unit = {},
) {
    val c = IMTheme.colors
    val isVideo = item.contentType == ContentType.VIDEO
    // 翻历史**不自动下**（iOS 详情页 / 收藏页 `autoPrefetchEnabled = NO`），只反映状态、下不下由用户点
    val gate = rememberGate(item.content, item.contentType, item.fileSize, isGroup, autoPrefetch = false)
    val myUid = LocalMediaGate.current?.myUid?.invoke().orEmpty()
    // 哪几格绕过门控直接显示：自己发的 / 图片且策略放行（判据与理由见 archiveTileUngated）
    val ungated = DownloadPolicy.archiveTileUngated(
        isVideo = isVideo,
        mine = myUid.isNotEmpty() && item.sender == myUid,
        phase = gate.state.phase,
        imageAutoAllowed = gate.autoAllowed,
    )
    val shown = ungated || gate.ready
    Box(
        Modifier.aspectRatio(1f).background(c.subtleFill)
            // 没下下来的格子点一下是下载（开始 / 暂停 / 重试，失效不做事），**不打开**——
            // 与聊天页相册宫格同一条（iOS `IMDetailMediaContainerCell.didSelectItem` 铁律①「不跳页」）
            .archiveItemGestures(
                onClick = { if (shown) onOpen(item) else gate.onTap() },
                onLongPress = onLongPress,
            ),
    ) {
        // 磨砂占位（M4-7）：一屏十几格全从空底开始加载最难看，这一格最该有它
        val frosted = rememberFrostedPainter(item.thumb)
        AsyncImage(
            model = when {
                // 视频一格显示**封面**（几十 KB，是"信封"的一部分，同聊天页 `VideoContent`）：
                // 门控作用在视频本体上。直接把视频 URL 交给 Coil 会去下整段再抽帧
                isVideo -> MediaUrl.absolute(item.poster, host, useTls).takeIf { item.poster.isNotBlank() }
                // 已在本机 → 本地原件
                gate.model != null -> gate.model
                // 豁免门控 → 按地址显示（图片加载器自带缓存，同 iOS `IMImageLoader`）
                ungated -> MediaUrl.absolute(item.content, host, useTls).takeIf { item.content.isNotBlank() }
                // 被门控挡着：**只给磨砂**，不给远端地址——给了等于 Coil 照样把原图拉下来，门控成了装饰
                else -> null
            },
            contentDescription = if (isVideo) "视频" else "图片",
            contentScale = ContentScale.Crop,
            placeholder = frosted,
            error = frosted,
            fallback = frosted,
            modifier = Modifier.fillMaxSize(),
        )
        // 门控层 = **聊天页相册宫格那一格的同一个组件**（压暗 + 裸字形 + 36dp 环 + 左上角一项角标；
        // iOS `IMMediaTileCell.applyGate:` 与 `IMAlbumTileView` 同形）。此前这里是另一套半透明圆徽标，
        // 与聊天页两样，且图清清楚楚地显示着、上面却压着「↓」（2026-09-17 用户报）
        if (!shown) AlbumTileGate(gate.state, item.fileSize)
        // 就绪的视频格：中心播放角标 + 左上角时长，与聊天页宫格同一对组件、同一尺寸
        if (isVideo && shown) {
            VideoPlayBadge(modifier = Modifier.align(Alignment.Center), diameter = 32.dp, iconSize = 16.dp)
            TileDurationChip(item.duration)
        }
        // 「从收藏发送」：右上角勾选框，**只有它切换选中**——点格子本身仍是预览 / 下载
        // （iOS `IMFavoritesViewController` 铁律：曾把点格复用成切换选中，用户看不到预览就得盲发）
        if (picked != null) {
            PickCheckButton(picked, onTogglePick, Modifier.align(Alignment.TopEnd), overMedia = true)
        }
    }
}

/**
 * 「从收藏发送」的勾选框：36dp 点击区里画聊天页多选那枚 [SelectionCheck]。
 * 行尾与宫格右上角共用；**自己吃掉点击**，不会连带触发行 / 格的打开。
 */
@Composable
internal fun PickCheckButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overMedia: Boolean = false,
) {
    Box(
        modifier.size(36.dp).clickable(onClickLabel = if (selected) "取消选择" else "选择", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SelectionCheck(selected, overMedia = overMedia)
    }
}

/**
 * 文件行。**三行**（对齐 iOS `IMDetailFileCell`：文件名 / 状态副行 / 时间行，行高 74pt），
 * 收藏页复用时再加第四行「来自X」（[source] 非空才画，同 iOS `sourceName`）。
 *
 * 图标位 = **聊天页文件气泡的同一个组件**（[FileGateSlot]，36dp）：未下载实心圆底 + ↓、
 * 下载中 / 暂停 环 + ⏸/↓、失败红 ↻、失效红 ⊘，就绪才是类型图标。此前这里是类型图标上
 * 压一枚半透明徽标，看着像已经能打开（聊天页 2026-09-10 #8 修过的同一个问题，这一侧没跟）。
 */
@Composable
internal fun FileRow(
    item: ConvMediaItem,
    isGroup: Boolean,
    onOpen: (ConvMediaItem) -> Unit,
    /** 「来自X」那一行（收藏页）；空串不画（详情页）。 */
    source: String = "",
    onLongPress: ((Rect) -> Unit)? = null,
    /** 行尾槽（「从收藏发送」的勾选框）；null 不画。 */
    trailing: (@Composable () -> Unit)? = null,
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
            FileGateSlot(gate.state, item.fileSize, name, side = 36.dp)
            Spacer(Modifier.width(d.space3))
            Column(Modifier.weight(1f)) {
                // 放不下**截中间**（iOS `NSLineBreakByTruncatingMiddle`）：截尾会把扩展名切掉
                MiddleEllipsisText(name, color = c.textPrimary, fontSize = 16.sp, maxLines = 1)
                val tint = if (DownloadLabels.fileStatusIsDanger(gate.state.phase)) c.danger else c.textSecondary
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 暂停态行首一枚小 ⏸（iOS `pausedSubtitle:`，与聊天页文件气泡同款）
                    if (gate.state.phase == DownloadPhase.Paused) {
                        androidx.compose.foundation.Image(
                            Lucide.Pause, null, Modifier.size(10.dp), colorFilter = ColorFilter.tint(tint),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        DownloadLabels.archiveFileLine(gate.state, item.fileSize),
                        color = tint, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    TimeFormat.fileDateTime(item.timestamp),
                    color = c.textTertiary, style = MaterialTheme.typography.bodySmall,
                )
                SourceLine(source)
            }
            trailing?.invoke()
        }
        Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(c.separator))
    }
}

/**
 * 「来自X」一行（收藏页的文件 / 语音 / 链接 / 文本行共用）。**独占一行、强调色**——
 * iOS 曾把它与时间挤在一行，备注名一长就把时间截没（FAVORITES_DESIGN §14 末段）。空串不画。
 */
@Composable
internal fun SourceLine(source: String) {
    if (source.isBlank()) return
    Text(
        "来自$source",
        color = IMTheme.colors.accent, style = MaterialTheme.typography.bodySmall,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}
