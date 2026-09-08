package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.api.MediaKind
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.rememberFrostedPainter
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.components.FileTypeIcon
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 会话媒体归档（详情页的「聊天媒体」，M4.5-3）。
 *
 * **过滤全在服务端**（撤回 / 为所有人删除 / 「仅为我删除」 / `history_visible` 下界都已滤掉），
 * 端上不再判一遍——判据分叉的话，这一页会出现聊天页里看不到的消息。
 *
 * **没有「链接」这一格**：链接不是独立的 `content_type`，是从文本里识别出来的，
 * 服务端没有可索引的列（`internal/conversation/media.go` 开头写明了这是本接口不覆盖的一格）。
 * 与其放一个永远空的 Tab，不如不放。
 */
@Composable
internal fun ConvMediaScreen(
    kind: String,
    onKindChange: (String) -> Unit,
    items: List<ConvMediaItem>,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onOpen: (ConvMediaItem) -> Unit,
    host: String,
    useTls: Boolean,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = "聊天媒体", onLeft = onBack)

        // 底轨 + 药丸，与详情页内联页签同一套（DetailTabBar）——两处长得不一样才是 bug
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = 10.dp)
                .clip(RoundedCornerShape(18.dp)).background(c.subtleFill).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MediaSeg("图片与视频", kind == MediaKind.MEDIA) { onKindChange(MediaKind.MEDIA) }
            MediaSeg("文件", kind == MediaKind.FILE) { onKindChange(MediaKind.FILE) }
        }

        when {
            loading && items.isEmpty() -> Hint("加载中…")
            items.isEmpty() -> Hint(if (kind == MediaKind.FILE) "这个会话还没有文件" else "这个会话还没有图片或视频")
            kind == MediaKind.FILE -> LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.convSeq }) { FileRow(it, onOpen) }
                if (hasMore) item { LoadMore(onLoadMore) }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items, key = { it.convSeq }) { MediaTile(it, host, useTls, onOpen) }
                if (hasMore) {
                    item { LoadMore(onLoadMore) }
                }
            }
        }
    }
}

@Composable
internal fun MediaSeg(label: String, on: Boolean, onClick: () -> Unit) {
    val c = IMTheme.colors
    // **选中/未选中同为主文字色，只以字重 + 药丸底色区分**（逐条对齐 iOS
    // `IMLiquidSegmentedControl.applyFonts`：semibold / medium，都是 label 色）。
    // 别再用「12% 主色底 + 主色字」表示选中——那在深色模式下几乎看不出来。
    Box(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (on) c.surfaceElevated else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = c.textPrimary,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = IMTheme.colors.textTertiary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 滚到底自动续拉；手点入口保留作失败重试（同群成员列表那条）。 */
@Composable
private fun LoadMore(onLoadMore: () -> Unit) {
    LaunchedEffect(Unit) { onLoadMore() }
    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
        Text("加载更多", color = IMTheme.colors.accent, modifier = Modifier.clickable { onLoadMore() })
    }
}

@Composable
internal fun MediaTile(item: ConvMediaItem, host: String, useTls: Boolean, onOpen: (ConvMediaItem) -> Unit) {
    val c = IMTheme.colors
    val isVideo = item.contentType == ContentType.VIDEO
    Box(
        Modifier.aspectRatio(1f).background(c.subtleFill).clickable { onOpen(item) },
    ) {
        // 磨砂占位（M4-7）：一屏四列十几格全从空底开始加载最难看，这一格最该有它
        val frosted = rememberFrostedPainter(item.thumb)
        AsyncImage(
            // 视频用 poster：直接把视频 URL 交给 Coil 会去下整段再抽帧
            model = MediaUrl.absolute(if (isVideo) item.poster else item.content, host, useTls),
            contentDescription = if (isVideo) "视频" else "图片",
            contentScale = ContentScale.Crop,
            placeholder = frosted,
            error = frosted,
            modifier = Modifier.fillMaxSize(),
        )
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
internal fun FileRow(item: ConvMediaItem, onOpen: (ConvMediaItem) -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.surface).clickable { onOpen(item) }
                .padding(horizontal = d.space4, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileTypeIcon(MediaUrl.displayFileName(item.content, item.fileName), size = 36.dp)
            Spacer(Modifier.width(d.space3))
            Column(Modifier.weight(1f)) {
                Text(
                    MediaUrl.displayFileName(item.content, item.fileName),
                    color = c.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                )
                val size = MediaUrl.formatSize(item.fileSize)
                val when1 = TimeFormat.conversationTime(item.timestamp)
                Text(
                    listOf(size, when1).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = c.textSecondary, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(0.5.dp).background(c.separator))
    }
}
