package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.data.CardContent
import com.libeyond.imandroid.ui.components.IMAvatar
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import com.composables.icons.lucide.Contact
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Lucide
import androidx.compose.foundation.layout.size
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 卡片类气泡内容：个人名片 / 合并转发聊天记录。
 *
 * 尺寸**严格照 iOS**（`IMContactCardView.m` / `IMChatRecordCell.m`）：卡宽 240、
 * 名片头像 44、分割线 0.5 且左右各内缩 12、脚注 11 号次要色。
 * 三端卡片宽度不一致时，同一条消息在三端换行位置都不同，看起来像三个产品。
 *
 * **解析失败一律降级成一句话，绝不把裸 JSON 铺给用户** —— 本端在这之前正是那样，
 * 聊天页里直接显示 `{"u":"7741990777",…}`（2026-09-07 实体机实测发现）。
 */

/** iOS `IMContactCardViewWidth` / `_card.widthAnchor` 同为 240。 */
private val CARD_WIDTH = 240.dp

@Composable
internal fun ContactCardContent(content: String) {
    val c = IMTheme.colors
    val appearance = IMTheme.appearance
    val card = CardContent.parseContact(content)

    if (card == null) {
        // 降级：老版本/被截断的名片。给一句人话，不给 JSON。
        Text2("[个人名片] 无法显示", c.textTertiary, appearance.chatFontSize)
        return
    }

    Column(modifier = Modifier.width(CARD_WIDTH)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            IMAvatar(
                displayName = card.displayName,
                seed = card.uid,          // 取色种子用 uid：改昵称不该换颜色
                avatarUrl = card.avatarUrl,
                size = 44.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                Text2(
                    card.displayName, c.textPrimary,
                    maxOf(14f, appearance.chatFontSize.value - 2).sp,
                    weight = FontWeight.SemiBold,
                )
                if (card.handle.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    // 副标题恒为 @句柄。**绝不显示 uid**——那是 10 位内部 ID。
                    Text2(card.handle, c.textSecondary, maxOf(12f, appearance.chatFontSize.value - 5).sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        Spacer(Modifier.height(6.dp))
        // 脚注前那枚小图标：iOS 用 `person.crop.square`（11pt，textSecondary）。
        // 只有文字没有图标时，名片卡和聊天记录卡的底部长得一模一样，一眼分不出是哪种卡。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                Lucide.Contact, null, Modifier.size(11.dp),
                colorFilter = ColorFilter.tint(c.textSecondary),
            )
            Spacer(Modifier.width(4.dp))
            Text2("个人名片", c.textSecondary, 11.sp)
        }
    }
}

@Composable
internal fun ChatRecordCardContent(content: String) {
    val c = IMTheme.colors
    val appearance = IMTheme.appearance
    val rec = CardContent.parseRecord(content)

    if (rec == null) {
        Text2("[聊天记录] 无法显示", c.textTertiary, appearance.chatFontSize)
        return
    }

    Column(modifier = Modifier.width(CARD_WIDTH)) {
        Text2(
            rec.title, c.textPrimary,
            maxOf(14f, appearance.chatFontSize.value - 2).sp,
            weight = FontWeight.SemiBold, maxLines = 1,
        )
        if (rec.lines.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            // 最多 3 行预览（iOS `_preview.numberOfLines = 3`）；每行「发送者: 摘要」。
            rec.lines.forEach { line ->
                Text2(
                    line, c.textSecondary,
                    maxOf(12f, appearance.chatFontSize.value - 5).sp,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                Lucide.MessageSquare, null, Modifier.size(11.dp),
                colorFilter = ColorFilter.tint(c.textSecondary),
            )
            Spacer(Modifier.width(4.dp))
            Text2("聊天记录", c.textSecondary, 11.sp)
            if (rec.total > rec.lines.size) {
                Spacer(Modifier.width(6.dp))
                Text2("共 ${rec.total} 条", c.textTertiary, 11.sp)
            }
        }
    }
}

/** 卡片里重复度最高的一行文字。抽出来只为让上面两个卡片读起来是「结构」而不是「样式堆」。 */
@Composable
private fun Text2(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    androidx.compose.material3.Text(
        text = text,
        color = color,
        fontSize = size,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
