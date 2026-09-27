package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.Ban
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.OctagonX
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.RotateCw
import com.libeyond.imandroid.data.DownloadLabels
import com.libeyond.imandroid.data.DownloadLabels.FileSlot
import com.libeyond.imandroid.data.DownloadLabels.Glyph
import com.libeyond.imandroid.data.DownloadPhase
import com.libeyond.imandroid.data.DownloadState
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.theme.IMTheme

// 未下载媒体的门控外观（2026-09-10 用户报 #5–#8，照 iOS 逐态对齐）。
// 该显什么字、画哪个图标全在 `data/DownloadLabels.kt`（已单测），这里只管画。
// **这几个组件都不接点击**：点这条媒体的哪儿都算点它，由外层整块接——
// 此前只有中间那枚徽标能点，点旁边的空白却落到气泡上直接打开了（#8 前半条）。
// 详情页 / 收藏页的宫格与文件行**也画这几个**（2026-09-17 起；此前那两处是另一套半透明圆徽标，已删）。

/** 图片/视频中心圆钮外那圈环的外接边长（iOS `kIMDownloadRingSide`）。 */
private val MEDIA_RING_SIDE = 56.dp

/**
 * 图片/视频气泡上的门控层（iOS `IMImageCell.renderGatedDownloadUI` + `IMMediaPlaceholder.expiredOverlay`）。
 * 中心 44dp 实心圆钮、下载中/暂停外绕 56dp 环、左上角一块胶囊；失效换成整块压暗 + ⊘ + 文案。就绪不画。
 */
@Composable
fun BoxScope.MediaGateOverlay(
    state: DownloadState,
    sizeBytes: Long,
    /** 视频时长（m:ss）；图片传 null。 */
    durationText: String?,
    /** 失效时的文案：「图片已失效」/「视频已失效」。 */
    expiredCaption: String,
) {
    val c = IMTheme.colors
    if (state.phase == DownloadPhase.Ready) return
    if (state.phase == DownloadPhase.Expired) {
        ExpiredCover(expiredCaption)
        return
    }
    Box(Modifier.align(Alignment.Center).size(MEDIA_RING_SIDE), contentAlignment = Alignment.Center) {
        if (DownloadLabels.showsRing(state.phase)) {
            DownloadRing(
                fraction = DownloadLabels.ringFraction(state, sizeBytes),
                radius = MEDIA_RING_SIDE / 2 - 3.dp,
                track = c.onMedia.copy(alpha = 0.32f),
                progress = c.onMedia,
                modifier = Modifier.size(MEDIA_RING_SIDE),
            )
        }
        glyphVector(DownloadLabels.glyphOf(state.phase))?.let { (icon, labelRes) ->
            // iOS 用 `arrow.down.circle.fill` 这类实心圆符号：白圆、字形镂空。Lucide 没有实心版，
            // 用白圆 + 深色字形拼出同一个观感
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(c.onMedia.copy(alpha = 0.95f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(icon, stringResource(labelRes), Modifier.size(22.dp), colorFilter = ColorFilter.tint(c.overlayStrong))
            }
        }
    }
    DownloadLabels.mediaCapsule(state, sizeBytes, durationText)?.let { text ->
        Box(
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (DownloadLabels.capsuleIsDanger(state.phase)) c.danger else c.overlay)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = c.onMedia, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/**
 * 宫格一格的门控层（iOS `IMAlbumTileView.setDownloadState:` / `setExpired:`）：
 * 整格压暗、中心裸字形（格子小，不要圆底）、下载中/暂停绕 36dp 环、左上角一项角标。失效只剩 ⊘。
 */
@Composable
fun BoxScope.AlbumTileGate(state: DownloadState, sizeBytes: Long) {
    val c = IMTheme.colors
    if (state.phase == DownloadPhase.Ready) return
    Box(Modifier.matchParentSize().background(c.overlay))
    if (state.phase == DownloadPhase.Expired) {
        Image(
            Lucide.Ban, stringResource(R.string.media_placeholder_expired), Modifier.align(Alignment.Center).size(22.dp),
            colorFilter = ColorFilter.tint(c.onMedia),
        )
        return
    }
    Box(Modifier.align(Alignment.Center).size(TILE_RING_SIDE), contentAlignment = Alignment.Center) {
        if (DownloadLabels.showsRing(state.phase)) {
            DownloadRing(
                fraction = DownloadLabels.ringFraction(state, sizeBytes),
                radius = TILE_RING_SIDE / 2 - 3.dp,
                track = c.onMedia.copy(alpha = 0.35f),
                progress = c.onMedia,
                modifier = Modifier.size(TILE_RING_SIDE),
            )
        }
        glyphVector(DownloadLabels.glyphOf(state.phase))?.let { (icon, labelRes) ->
            Image(icon, stringResource(labelRes), Modifier.size(15.dp), colorFilter = ColorFilter.tint(c.onMedia))
        }
    }
    DownloadLabels.tileCaption(state, sizeBytes)?.let { text ->
        Box(
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                .clip(RoundedCornerShape(4.dp)).background(c.overlay)
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text(text, color = c.onMedia, fontSize = 9.sp, maxLines = 1)
        }
    }
}

private val TILE_RING_SIDE = 36.dp

/**
 * 文件图标位（iOS `IMBubbleCell` 的 `_fileIconWrap`，气泡里 44dp）。
 * **只有就绪才显类型图标**（#8 后半条）；其余态画下载状态，口径见 [DownloadLabels.fileSlotOf]。
 *
 * **详情页 / 收藏页的文件行也用它**（[side] 传 36dp，iOS `IMDetailFileCell` 的图标位）：
 * iOS 那一行的圆底 / 进度环 / ↓⏸↻ 注释里写明「与聊天页文件气泡同款」。本端此前那一行是
 * 类型图标上再压一枚半透明徽标（另一套画法），与气泡两样（2026-09-17 用户报）。
 * 环、圆底、字形一律按 [side] 等比缩放，两处只有大小不同。
 */
@Composable
fun FileGateSlot(state: DownloadState, sizeBytes: Long, fileName: String, side: Dp = 44.dp) {
    val c = IMTheme.colors
    val slot = DownloadLabels.fileSlotOf(state.phase)
    val k = side / 44.dp
    val ringRadius = FILE_RING_RADIUS * k
    Box(Modifier.size(side), contentAlignment = Alignment.Center) {
        when (slot) {
            FileSlot.TypeIcon -> FileTypeIcon(fileName, size = side)
            FileSlot.StartDisc -> Box(
                Modifier.size(ringRadius * 2).clip(CircleShape).background(c.accent),
                contentAlignment = Alignment.Center,
            ) {
                Image(Lucide.ArrowDown, stringResource(R.string.common_download), Modifier.size(18.dp * k), colorFilter = ColorFilter.tint(c.onAccent))
            }
            FileSlot.RingPause, FileSlot.RingResume -> {
                DownloadRing(
                    fraction = DownloadLabels.ringFraction(state, sizeBytes),
                    radius = ringRadius,
                    track = c.textSecondary.copy(alpha = 0.25f),
                    progress = c.accent,
                    modifier = Modifier.size(side),
                )
                val pause = slot == FileSlot.RingPause
                Image(
                    if (pause) Lucide.Pause else Lucide.ArrowDown,
                    stringResource(if (pause) R.string.common_pause else R.string.media_download_a11y_resume),
                    Modifier.size(16.dp * k), colorFilter = ColorFilter.tint(c.accent),
                )
            }
            FileSlot.Retry -> Image(
                Lucide.RotateCw, stringResource(R.string.common_retry), Modifier.size(20.dp * k), colorFilter = ColorFilter.tint(c.danger),
            )
            FileSlot.Expired -> Image(
                Lucide.OctagonX, stringResource(R.string.chat_file_expired), Modifier.size(22.dp * k), colorFilter = ColorFilter.tint(c.danger),
            )
        }
    }
}

/** iOS 文件图标位的环半径 19.5pt（线宽 3）。 */
private val FILE_RING_RADIUS = 19.5.dp

/**
 * 宫格一格左上角的视频时长角标（iOS `IMAlbumTileView` 的时长角标）。
 *
 * **聊天页相册宫格、详情页 / 收藏页的媒体宫格共用这一枚**：此前详情页那一格自己画了一个
 * 右下角、另一种圆角的版本，与聊天页宫格两样。**只在就绪（或豁免门控）时画**——
 * 没下下来时左上角让给 [AlbumTileGate] 的大小角标，格子窄，容不下两项（iOS 同）。
 */
@Composable
fun BoxScope.TileDurationChip(durationMs: Int?) {
    val c = IMTheme.colors
    if ((durationMs ?: 0) <= 0) return
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(c.overlay)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(com.libeyond.imandroid.data.MediaUrl.formatDuration(durationMs), color = c.onMedia, fontSize = 9.sp)
    }
}

/** 失效覆盖层：整块压暗 + ⊘ + 一行文案，**不可点**（终态，点了也是 404）。 */
@Composable
private fun BoxScope.ExpiredCover(caption: String) {
    val c = IMTheme.colors
    Box(Modifier.matchParentSize().background(c.overlay))
    Column(
        Modifier.align(Alignment.Center).offset(y = (-8).dp).padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(Lucide.OctagonX, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(c.onMedia))
        Spacer(Modifier.height(4.dp))
        Text(caption, color = c.onMedia, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * 进度环：底环一整圈 + 从 12 点顺时针的进度弧（圆头）。
 * 不用 `CircularProgressIndicator`：它的底环与线宽口径和 iOS 的 `CAShapeLayer` 对不上，且画不出"最少露一点头"。
 */
@Composable
private fun DownloadRing(fraction: Float, radius: Dp, track: Color, progress: Color, modifier: Modifier) {
    Canvas(modifier) {
        val r = radius.toPx()
        val w = 3.dp.toPx()
        val topLeft = Offset(center.x - r, center.y - r)
        drawCircle(track, radius = r, center = center, style = Stroke(width = w))
        drawArc(
            color = progress, startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
            topLeft = topLeft, size = Size(r * 2, r * 2), style = Stroke(width = w, cap = StrokeCap.Round),
        )
    }
}

private fun glyphVector(g: Glyph): Pair<ImageVector, Int>? = when (g) {
    Glyph.Download -> Lucide.ArrowDown to R.string.common_download
    Glyph.Pause -> Lucide.Pause to R.string.common_pause
    Glyph.Retry -> Lucide.RotateCw to R.string.common_retry
    Glyph.None -> null
}
