package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.IdCard
import com.composables.icons.lucide.Image as LucideImageIcon
// `Lucide.Image` 与 foundation 的 `Image` 组件同名，故起个别名——两者在同一文件里都要用
import com.libeyond.imandroid.data.MediaUrl
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Video
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.imandroid.ui.components.FileTypeIcon
import com.libeyond.imandroid.ui.theme.IMTheme

// 从 Bubbles.kt 拆出（2026-09-08，那份文件到 603/600 行）。
// 拆的边界是「引用这件事怎么显示」：引用块本体 + 快照的本地化 + 类型图标 + 摘要生成。
// 它们必须待在一起——**输入栏的引用条与气泡里的引用块显示的是同一句话**，
// 分开放迟早会有一处改了另一处没改（本端已经踩过一次：输入栏显示 /uploads/xxx.jpg）。

/**
 * 气泡顶部的引用块（M4-2），结构对齐 iOS `IMBubbleCell` 的引用段：
 * **左侧竖条 + 群聊两行式（被引用者昵称独占一行）+ 类型图标 + 灰字快照**。
 *
 * 此前本端只有一个灰底圆角框套一行小字——竖条、昵称行、类型图标三样都没有，
 * 与 iOS 差得最明显的就是"看不出引用的是什么类型"。
 *
 * **文件类快照用文件类型图标**（对齐 iOS 的 `IMFileTypeIconForName`）：
 * 快照形如 `[文件] 报表.xlsx`，能取到名字就按扩展名给图。
 */
@Composable
internal fun QuoteBlock(
    snapshot: String,
    fromName: String?,
    modifier: Modifier = Modifier,
    /**
     * 被引用消息的极小缩略（M4-7）。**由调用方从本地反查原消息拿到**——
     * 引用快照是发送时冻结的一串**文字**，本身不带 thumb。
     * 原消息不在本地（翻不到 / 已删）就没有，退回类型图标。
     */
    thumb: String? = null,
    /** 点引用块跳到原消息。null = 不可点（原消息不在本地）。 */
    onTap: (() -> Unit)? = null,
) {
    val c = IMTheme.colors
    val localized = localizeReplySnapshot(snapshot)
    val kindGlyph = quoteGlyphFor(localized)
    val fileName = quoteFileNameOf(localized)

    val frosted = com.libeyond.imandroid.ui.rememberFrostedPainter(thumb)
    Row(
        modifier = modifier.height(IntrinsicSize.Min)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
    ) {
        // 竖条：iOS 用 `▏` 字形，本端画一条真的——字形在不同字体下宽窄不一
        Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(c.accent))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            if (!fromName.isNullOrBlank()) {
                Text(
                    fromName,
                    color = c.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    // 真缩略优先（对齐 iOS：引用图片/视频时内嵌 24×24 的缩略）——
                    // 一个通用的「图片」图标看不出引的是哪一张
                    frosted != null -> {
                        Image(
                            painter = frosted,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    fileName != null -> {
                        FileTypeIcon(fileName, size = 16.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    kindGlyph != null -> {
                        Image(
                            kindGlyph, null, Modifier.size(13.dp),
                            colorFilter = ColorFilter.tint(c.textSecondary),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }
                Text(
                    text = localized,
                    color = c.textSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 快照是媒体占位时给个小图标（对齐 iOS `IMMediaGlyphForSnippet`）；否则 null。 */
private fun quoteGlyphFor(localized: String): androidx.compose.ui.graphics.vector.ImageVector? = when {
    localized.startsWith("[图片]") -> Lucide.LucideImageIcon
    localized.startsWith("[视频]") -> Lucide.Video
    localized.startsWith("[语音]") -> Lucide.Mic
    localized.startsWith("[聊天记录]") -> Lucide.MessageSquare
    localized.startsWith("[个人名片]") -> Lucide.IdCard
    else -> null
}

/**
 * `[文件] 报表.xlsx` → `报表.xlsx`（对齐 iOS `IMReplySnippetFileName`）。
 * 没带名字（只有 `[文件]`）返回 null，让调用方退回通用图标。
 */
private fun quoteFileNameOf(localized: String): String? {
    if (!localized.startsWith("[文件]")) return null
    return localized.removePrefix("[文件]").trim().takeIf { it.isNotEmpty() }
}

/**
 * 本机为「正在引用的那条」生成的快照文案（输入栏引用条用）。
 *
 * **不能直接用 `msg.content`**：媒体消息的 content 是 `/uploads/req-xxx__原名.jpg`，
 * 直接截 60 个字符显示出来就是一串路径（2026-09-08 撞见）。
 * 口径与服务端冻结的 `reply_snapshot` 一致（PROTOCOL §4.3），
 * 这样"引用时看到的"和"发出去以后气泡里显示的"是同一句话。
 */
internal fun replyPreviewOf(
    contentType: String,
    content: String,
    fileName: String?,
    caption: String?,
): String = when (contentType) {
    ContentType.IMAGE -> "[图片]" + captionSuffix(caption)
    ContentType.VIDEO -> "[视频]" + captionSuffix(caption)
    ContentType.VOICE -> "[语音]"
    ContentType.FILE -> "[文件] " + MediaUrl.displayFileName(content, fileName.orEmpty())
    ContentType.CONTACT -> "[个人名片]"
    ContentType.CHAT_RECORD -> "[聊天记录]"
    else -> content
}

private fun captionSuffix(caption: String?): String =
    if (caption.isNullOrBlank()) "" else " " + caption
