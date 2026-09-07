package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.libeyond.imandroid.data.AlbumLayout
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 待发消息的气泡（发送中 / 失败）。
 *
 * **为什么这些要单独画**：`ChatRow.Pending` 原本一律画成文本气泡，`text = msg.content`——
 * 而媒体/文件待发行的 `content` 是本地 `content://…` URI，于是屏幕上出现一条绿色文本气泡
 * 写着 `content://media/external/video/media/30`（2026-09-07 真机实测撞见）。
 * 多图那条路早有 [AlbumBubble] 兜着，所以只有**单条**会露出来，
 * 而单条通常几百毫秒就 ack 了、一闪而过——**只有发失败时才一直挂着**。
 *
 * [progress] 是分片上传的百分比（`null` = 不在分片上传中，例如图片走的是整包上传）。
 */
@Composable
internal fun PendingMediaBubble(
    localUri: String,
    isVideo: Boolean,
    timestamp: Long,
    sending: Boolean,
    failed: Boolean,
    progress: Int?,
    onRetry: () -> Unit,
) {
    val c = IMTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (failed) RetryMark(onRetry)
        Box(
            modifier = Modifier
                .width(AlbumLayout.WIDTH.dp)
                .clip(RoundedCornerShape(IMTheme.appearance.bubbleRadius))
                .background(c.subtleFill),
        ) {
            AsyncImage(
                // 本地 content:// URI，Coil 直接能加载（视频靠 coil-video 出首帧；
                // 没注册解码器时是空白底，不崩）
                model = localUri,
                contentDescription = if (isVideo) "视频" else "图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(AlbumLayout.WIDTH.dp).height(AlbumLayout.SINGLE_ROW_HEIGHT.dp),
            )
            if (sending || failed) {
                Box(
                    Modifier
                        .width(AlbumLayout.WIDTH.dp)
                        .height(AlbumLayout.SINGLE_ROW_HEIGHT.dp)
                        .background(c.overlay),
                )
            }
            // 上传中显示进度环，**顶掉播放钮**：这条还没发出去，播放钮既没用也误导。
            if (progress != null && sending) {
                Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.size(44.dp),
                        color = c.onMedia,
                        trackColor = c.overlay,
                    )
                    Text("$progress%", color = c.onMedia, fontSize = 11.sp)
                }
            } else if (isVideo) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(c.overlay),
                    contentAlignment = Alignment.Center,
                ) {
                    // **不用 "▶" 文字字形**：部分设备（实测 OPPO ColorOS）用彩色 emoji 字体
                    // 渲染这类符号，会变成一个橙色方块，看着像坏了。VideoPlayer 里同样的坑。
                    androidx.compose.foundation.Image(
                        imageVector = Lucide.Play,
                        contentDescription = "视频",
                        modifier = Modifier.size(16.dp),
                        colorFilter = ColorFilter.tint(c.onMedia),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.overlay)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(TimeFormat.bubbleTime(timestamp), color = c.onMedia, fontSize = 10.sp)
            }
        }
    }
}

/**
 * 待发**文件**气泡。形状对齐已确认文件气泡的 `FileContent`（图标 + 名字 + 大小），
 * 底下多一条进度。
 *
 * 没有它的话文件待发行会走到文本分支，屏幕上就是一条绿气泡写着
 * `content://com.android.providers.media.documents/document/…`——
 * 与图片/视频那条是同一个坑，只是当时只补了媒体两种。
 */
@Composable
internal fun PendingFileBubble(
    fileName: String,
    fileSize: Long?,
    timestamp: Long,
    sending: Boolean,
    failed: Boolean,
    progress: Int?,
    onRetry: () -> Unit,
) {
    val c = IMTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (failed) RetryMark(onRetry)
        Column(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(IMTheme.appearance.bubbleRadius))
                .background(c.bubbleMe)
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(c.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        imageVector = Lucide.File,
                        contentDescription = "文件",
                        modifier = Modifier.size(18.dp),
                        colorFilter = ColorFilter.tint(c.accent),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(fileName, color = c.textPrimary, fontSize = 13.sp, maxLines = 2)
                    val size = MediaUrl.formatSize(fileSize ?: 0)
                    if (size.isNotEmpty()) Text(size, color = c.textSecondary, fontSize = 11.sp)
                }
            }
            if (progress != null && sending) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = c.accent,
                    trackColor = c.subtleFill,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                TimeFormat.bubbleTime(timestamp),
                color = c.textSecondary,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** 红❗在气泡左侧，点了重发——与文本失败气泡同一套语义。 */
@Composable
private fun RetryMark(onRetry: () -> Unit) {
    Text(
        "！",
        color = IMTheme.colors.danger,
        fontSize = 18.sp,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onRetry)
            .padding(horizontal = 6.dp),
    )
}
