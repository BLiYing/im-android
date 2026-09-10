package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.libeyond.imandroid.data.CardContent
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.components.FileTypeIcon
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/** 详情页里点开的一条图/视频（记录里只有地址，没有整条消息）。 */
internal data class RecordMedia(val contentType: String, val content: String)

/**
 * 聊天记录详情页：点合并转发卡进来，逐条列出记录里的消息（十七条对齐 #17）。
 *
 * 布局照 iOS `IMChatRecordViewController`：头行 = 28 头像 + 名字 + 右侧时间，
 * 正文与名字左对齐；**连续同一人**的后几条收起头像和名字（判据 [CardContent.RecordItem.senderKey]）。
 * 此前本端点记录卡没有任何反应——卡片只能看三行摘要。
 */
@Composable
internal fun ChatRecordScreen(
    content: String,
    host: String,
    useTls: Boolean,
    onBack: () -> Unit,
    /** 记录里嵌套的记录：往里再压一层。 */
    onOpenRecord: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenMedia: (RecordMedia) -> Unit,
) {
    val c = IMTheme.colors
    val doc = remember(content) { CardContent.parseRecordDoc(content) }
    val uri = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.pageBackground)
            // 盖在聊天页之上：只有背景的层**不拦触摸**，点空白处会点穿到底下那页的气泡
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding(),
    ) {
        IMTopBar(title = doc?.title ?: "聊天记录", onLeft = onBack)
        val items = doc?.items.orEmpty()
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(items) { i, item ->
                RecordRow(
                    item = item,
                    continued = i > 0 && items[i - 1].senderKey == item.senderKey,
                    host = host,
                    useTls = useTls,
                    onTap = recordTap(item, host, useTls, uri, onOpenRecord, onOpenUser, onOpenMedia),
                )
            }
        }
    }
}

/** 点一条记录做什么（iOS `didSelectRowAtIndexPath`）；null = 这一条不可点。 */
private fun recordTap(
    item: CardContent.RecordItem,
    host: String,
    useTls: Boolean,
    uri: UriHandler,
    onOpenRecord: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenMedia: (RecordMedia) -> Unit,
): (() -> Unit)? {
    when (item.contentType) {
        // 坏数据不下钻，否则推出一页空白的「聊天记录」
        ContentType.CHAT_RECORD -> if (CardContent.looksLikeRecord(item.content)) {
            return { onOpenRecord(item.content) }
        }
        ContentType.CONTACT -> CardContent.parseContact(item.content)?.let { card ->
            return { onOpenUser(card.uid) }
        }
        ContentType.IMAGE, ContentType.VIDEO -> if (item.content.isNotBlank()) {
            return { onOpenMedia(RecordMedia(item.contentType, item.content)) }
        }
        // 文件交给浏览器（记录里的文件不走聊天页的下载门控，iOS 同样是外部打开）；只认 http(s)
        ContentType.FILE -> {
            val url = MediaUrl.absolute(item.content, host, useTls)
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return {
                    runCatching { uri.openUri(url) }
                        .onFailure { IMLog.tag("IM.Chat").w("record_file_open_failed", "error" to it.javaClass.simpleName) }
                }
            }
        }
    }
    return null
}

@Composable
private fun RecordRow(
    item: CardContent.RecordItem,
    continued: Boolean,
    host: String,
    useTls: Boolean,
    onTap: (() -> Unit)?,
) {
    val c = IMTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
    ) {
        // 连续同一人：头像与名字收起、头行矮到 16，但**头像列宽照留**——正文左缘不能跟着跳
        Row(
            Modifier.fillMaxWidth().height(if (continued) 16.dp else 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(28.dp)) {
                if (!continued) {
                    val seed = item.name.ifBlank { item.uid }
                    IMAvatar(displayName = seed, seed = seed, size = 28.dp, avatarUrl = item.avatarUrl)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (continued) "" else item.name,
                color = c.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(CardContent.recordItemTime(item.timestamp), color = c.textTertiary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        // 左缘 = 头像 28 + 间距 8，与名字对齐
        Column(
            Modifier.padding(start = 36.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RecordBody(item, host, useTls)
        }
    }
}

@Composable
private fun RecordBody(item: CardContent.RecordItem, host: String, useTls: Boolean) {
    val c = IMTheme.colors
    when (item.contentType) {
        ContentType.IMAGE, ContentType.VIDEO -> {
            val isVideo = item.contentType == ContentType.VIDEO
            Box(
                Modifier.size(width = 200.dp, height = 140.dp).clip(RoundedCornerShape(8.dp)).background(c.subtleFill),
                contentAlignment = Alignment.Center,
            ) {
                // 视频**不拿本体抽帧**：记录里没有封面地址，抽帧等于把整段视频下下来
                if (!isVideo && item.content.isNotBlank()) {
                    AsyncImage(
                        model = MediaUrl.absolute(item.content, host, useTls),
                        contentDescription = "图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isVideo) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(c.overlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(Lucide.Play, "播放", Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.onMedia))
                    }
                }
            }
            if (item.caption.isNotBlank()) Text(item.caption, color = c.textPrimary, fontSize = 15.sp)
        }
        ContentType.FILE -> {
            val name = item.fileName.ifBlank { MediaUrl.displayFileName(item.content) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileTypeIcon(name, size = 24.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = c.accent, fontSize = 16.sp)) { append(name.ifBlank { "[文件]" }) }
                        if (item.fileSize > 0) {
                            withStyle(SpanStyle(color = c.textSecondary, fontSize = 13.sp)) {
                                append(" · " + MediaUrl.formatSize(item.fileSize))
                            }
                        }
                    },
                )
            }
        }
        ContentType.CONTACT -> if (CardContent.parseContact(item.content) == null) {
            Text("[个人名片]", color = c.textTertiary, fontSize = 16.sp)
        } else {
            RecordCardFrame { ContactCardContent(item.content, width = RECORD_CARD_INNER) }
        }
        ContentType.CHAT_RECORD -> if (CardContent.parseRecord(item.content) == null) {
            Text("[聊天记录]", color = c.textTertiary, fontSize = 16.sp)
        } else {
            RecordCardFrame { ChatRecordCardContent(item.content, width = RECORD_CARD_INNER, previewLines = 2) }
        }
        ContentType.VOICE, "audio" -> VoiceContent(item.durationMs, item.waveform)
        else -> Text(item.content, color = c.textPrimary, fontSize = 16.sp)
    }
}

/** 详情页里的卡片外框（iOS：宽 240、圆角 10、0.5 描边、次级底色、内边距 12）。气泡里的卡片由气泡当框。 */
@Composable
private fun RecordCardFrame(content: @Composable () -> Unit) {
    val c = IMTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .width(RECORD_CARD_INNER + 24.dp)
            .clip(shape)
            .background(c.cardBackground)
            .border(0.5.dp, c.separator, shape)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
    ) { content() }
}

private val RECORD_CARD_INNER = 216.dp
