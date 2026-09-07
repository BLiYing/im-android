package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Play
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.data.Waveform
import androidx.compose.runtime.remember
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 媒体气泡内容（图片 / 视频 / 语音 / 文件）。
 *
 * **不为了拿尺寸或时长去预下载媒体**（PROTOCOL §4.3 明文）：
 * `media_w/media_h/duration/file_size` 服务端会随消息下发，缺字段就按未知处理，
 * 用一个中性占位比先下完再排版好——后者会让长列表滚动时疯狂拉流量。
 */
@Composable
fun MediaContent(msg: MessageEntity, host: String, useTls: Boolean, maxWidth: androidx.compose.ui.unit.Dp = 240.dp) {
    val url = MediaUrl.absolute(msg.content, host, useTls)
    when (msg.contentType) {
        "image" -> ImageContent(url, msg, maxWidth)
        "video" -> VideoContent(url, msg, maxWidth)
        "voice" -> VoiceContent(msg)
        else -> FileContent(msg)
    }
}

@Composable
private fun ImageContent(url: String, msg: MessageEntity, maxWidth: androidx.compose.ui.unit.Dp) {
    val c = IMTheme.colors
    // 有服务端给的宽高就按原比例占位，避免加载完跳一下把下面的消息挤走
    val ratio = if ((msg.mediaW ?: 0) > 0 && (msg.mediaH ?: 0) > 0) {
        (msg.mediaW!!.toFloat() / msg.mediaH!!.toFloat()).coerceIn(0.5f, 2f)
    } else 1f
    Column {
        AsyncImage(
            model = url,
            contentDescription = "图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(10.dp))
                .background(c.subtleFill),
        )
        // 图说「有字显字」（Telegram 图说模型）
        if (!msg.caption.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(msg.caption, color = c.textPrimary, fontSize = IMTheme.appearance.chatFontSize)
        }
    }
}

@Composable
private fun VideoContent(url: String, msg: MessageEntity, maxWidth: androidx.compose.ui.unit.Dp) {
    val c = IMTheme.colors
    Column {
        Box(
            modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp)).background(c.subtleFill),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(c.overlay),
                contentAlignment = Alignment.Center,
            ) {
                Image(Lucide.Play, "播放", Modifier.size(20.dp), colorFilter = ColorFilter.tint(c.onMedia))
            }
            // 时长角标：服务端给了才显，**不为拿它去下载视频**
            val dur = MediaUrl.formatDuration(msg.duration)
            if (msg.duration != null && msg.duration > 0) {
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .clip(RoundedCornerShape(4.dp)).background(c.overlay)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) { Text(dur, color = c.onMedia, fontSize = 10.sp) }
            }
        }
        if (!msg.caption.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(msg.caption, color = c.textPrimary, fontSize = IMTheme.appearance.chatFontSize)
        }
    }
}

@Composable
private fun VoiceContent(msg: MessageEntity) {
    val c = IMTheme.colors
    // 宽度按时长走，与 iOS `IMVoiceBubbleCell` 同一个式子：MIN(240, MAX(160, 96 + dur*3.6))。
    // **下限是 160 不是 96**——本端一开始写成 96，一秒的语音气泡只有 iOS 的一半宽。
    val secs = ((msg.duration ?: 0) / 1000f)
    val w = minOf(240f, maxOf(160f, 96f + secs * 3.6f)).dp
    Row(
        modifier = Modifier.width(w).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(c.accentSoft),
            contentAlignment = Alignment.Center,
        ) { Image(Lucide.Mic, "语音", Modifier.size(14.dp), colorFilter = ColorFilter.tint(c.accent)) }
        Spacer(Modifier.width(8.dp))
        // 真波形：waveform(base64) → 0~1 柱高。缺字段时 Waveform 自己退化成等高条纹
        // （协议允许的合法状态，不是错误）。桶内取**最大值**不是平均——取平均会把波形抹平。
        val bars = remember(msg.waveform) { Waveform.barsOf(msg.waveform, BAR_COUNT) }
        Row(
            Modifier.weight(1f).height(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bars.forEach { h ->
                Box(
                    Modifier.padding(end = 2.dp).width(2.dp)
                        // 最低 3dp：振幅为 0 的静音段也要看得见柱子，否则波形中间会"断掉"
                        .height((3f + h * 15f).dp)
                        .background(c.accent.copy(alpha = 0.7f), RoundedCornerShape(1.dp)),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(MediaUrl.formatDuration(msg.duration), color = c.textSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun FileContent(msg: MessageEntity) {
    val c = IMTheme.colors
    Row(
        modifier = Modifier.widthIn(max = 240.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(c.accentSoft),
            contentAlignment = Alignment.Center,
        ) { Image(Lucide.File, "文件", Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.accent)) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = MediaUrl.displayFileName(msg.content, msg.fileName.orEmpty()),
                color = c.textPrimary,
                fontSize = 13.sp,
                maxLines = 2,
            )
            // 大小直接用服务端给的字节数格式化，**不重新下载文件去算**
            val size = MediaUrl.formatSize(msg.fileSize ?: 0)
            if (size.isNotEmpty()) Text(size, color = c.textSecondary, fontSize = 11.sp)
        }
    }
}

/** 语音波形柱数。与气泡宽度无关（下采样已按比例取），够看出起伏即可。 */
private const val BAR_COUNT = 24
