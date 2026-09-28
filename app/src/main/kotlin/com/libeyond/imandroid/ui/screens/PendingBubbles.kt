package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.AlbumLayout
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.components.FileTypeIcon
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.passThroughTap
import com.libeyond.imandroid.ui.theme.IMTheme
import com.libeyond.imandroid.ui.voice.LocalVoicePlayer
import com.libeyond.imandroid.ui.voice.WaveformBars
import com.libeyond.imandroid.voice.VoiceRules

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
                contentDescription = if (isVideo) stringResource(R.string.common_video) else stringResource(R.string.common_image),
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
                        contentDescription = stringResource(R.string.common_video),
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
                FileTypeIcon(fileName, size = 36.dp)
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

/**
 * 待发**语音**气泡。与图片/视频/文件同一个坑（本文件头两个函数的注释）：语音待发行的 `content`
 * 是本地 `file://` 路径，按文本画就会在屏幕上出现一条写着 `file:///data/user/0/.../xxx.m4a`
 * 的绿气泡、ack 落地后又"跳变"成正常语音气泡——录制功能上线后用户在真机上报的第一个问题
 * （2026-09-28）。播放走本地文件预览（[VoicePlayer.toggleFile]），不是确认气泡 `VoiceBubbleBody`
 * 那条「按服务端地址找下载缓存」的路——这条消息还没上传完，压根没有服务端地址。
 * `id` 用 `voice-pending:` 前缀而非 [VoiceRules.playableId]，刻意避免撞上 ack 落地那一刻
 * 确认气泡的 id（`cid:$clientMsgId`）——两边是两条独立的播放态，没必要也不该共用。
 */
@Composable
internal fun PendingVoiceBubble(
    clientMsgId: String,
    convId: String,
    localUri: String,
    durationMs: Long,
    waveform: String?,
    timestamp: Long,
    sending: Boolean,
    failed: Boolean,
    progress: Int?,
    onRetry: () -> Unit,
) {
    val c = IMTheme.colors
    val player = LocalVoicePlayer.current
    val previewId = remember(clientMsgId) { "voice-pending:$clientMsgId" }
    val pb = player?.state?.collectAsState()?.value?.takeIf { it.id == previewId }
    var toast by remember { mutableStateOf<String?>(null) }
    toast?.let { IMToast(it) { toast = null } }
    val width = VoiceRules.bubbleWidthDp(durationMs).dp.coerceAtLeast(160.dp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (failed) RetryMark(onRetry)
        Column(
            modifier = Modifier
                .width(width)
                .clip(RoundedCornerShape(IMTheme.appearance.bubbleRadius))
                .background(c.bubbleMe)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .passThroughTap {
                    val f = java.io.File(localUri.removePrefix("file://"))
                    if (f.exists()) player?.toggleFile(previewId, convId, f, durationMs) { toast = it }
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(c.voicePlayMine), contentAlignment = Alignment.Center) {
                    Image(
                        if (pb?.playing == true) Lucide.Pause else Lucide.Play, null, Modifier.size(15.dp),
                        colorFilter = ColorFilter.tint(c.onAccent),
                    )
                }
                Spacer(Modifier.width(9.dp))
                WaveformBars(
                    waveform = waveform, progress = pb?.progress ?: 0f,
                    active = c.textPrimary, inactive = c.voiceWaveInactiveMine,
                    modifier = Modifier.weight(1f).height(24.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    MediaUrl.formatDuration(VoiceRules.shownMillis(durationMs, pb?.progress ?: 0f, pb?.playing == true).toInt()),
                    color = c.textSecondary, fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                if (progress != null && sending) {
                    Text("$progress%", color = c.textSecondary, fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(TimeFormat.bubbleTime(timestamp), color = c.textSecondary, fontSize = 10.sp)
            }
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
