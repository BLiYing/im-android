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
import androidx.compose.ui.unit.Dp
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
internal fun ContactCardContent(
    content: String,
    /** 内容宽。聊天记录详情页把卡片装进自带内边距的框里，要比气泡里窄。 */
    width: Dp = CARD_WIDTH,
    /** 脚注行右端的内容（气泡里传时间 + 勾）。null = 不画（聊天记录详情页里的嵌套卡）。 */
    footerTrailing: (@Composable () -> Unit)? = null,
) {
    val c = IMTheme.colors
    val appearance = IMTheme.appearance
    val card = CardContent.parseContact(content)

    if (card == null) {
        // 降级：老版本/被截断的名片。给一句人话，不给 JSON。
        CardFallback("[个人名片] 无法显示", footerTrailing)
        return
    }

    Column(modifier = Modifier.width(width)) {
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
        CardFooter(Lucide.Contact, "个人名片", footerTrailing)
    }
}

@Composable
internal fun ChatRecordCardContent(
    content: String,
    width: Dp = CARD_WIDTH,
    /** 预览最多几行：气泡里 3（iOS `IMChatRecordCell`），详情页里的嵌套卡 2（`IMChatRecordViewController`）。 */
    previewLines: Int = 3,
    /** 同 [ContactCardContent] 的 footerTrailing。 */
    footerTrailing: (@Composable () -> Unit)? = null,
) {
    val c = IMTheme.colors
    val appearance = IMTheme.appearance
    val rec = CardContent.parseRecord(content, previewLines)

    if (rec == null) {
        CardFallback("[聊天记录] 无法显示", footerTrailing)
        return
    }

    // 间距照 iOS `IMChatRecordCell`：顶 4、标题、6、预览、8、分割线、6、脚注。
    // 点卡片进详情页由气泡本体处理（Bubble 的 onClick），这里只管画。
    Column(modifier = Modifier.width(width).padding(top = 4.dp)) {
        Text2(
            rec.title, c.textPrimary,
            maxOf(14f, appearance.chatFontSize.value - 2).sp,
            weight = FontWeight.SemiBold, maxLines = 1,
        )
        if (rec.lines.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            // **一个 label、按换行连起来、总共最多 3 行**（iOS `_preview.numberOfLines = 3`）：
            // 前面一条长到折两行，后面的就少显，而不是每条各占一行各自截断。
            Text2(
                rec.lines.joinToString("\n"), c.textSecondary,
                maxOf(12f, appearance.chatFontSize.value - 5).sp,
                maxLines = previewLines,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        Spacer(Modifier.height(6.dp))
        // 脚注只写「聊天记录」，**不写「共 N 条」**：iOS/Web 都没有这一段
        CardFooter(Lucide.MessageSquare, "聊天记录", footerTrailing)
    }
}

/**
 * 卡片脚注：小图标 + 类型名，右端可挂时间。**时间与「个人名片 / 聊天记录」在同一行**（iOS/Web 同）——
 * 此前时间在卡片下面另起一行，气泡平白高出一截（2026-09-15 用户报）。
 */
@Composable
private fun CardFooter(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    trailing: (@Composable () -> Unit)?,
) {
    val c = IMTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(icon, null, Modifier.size(11.dp), colorFilter = ColorFilter.tint(c.textSecondary))
        Spacer(Modifier.width(4.dp))
        Text2(label, c.textSecondary, 11.sp)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** 解析失败的降级一句话。时间照样要画——没有脚注行可挂，就挂在这句话下面靠右。 */
@Composable
private fun CardFallback(text: String, trailing: (@Composable () -> Unit)?) {
    Column {
        Text2(text, IMTheme.colors.textTertiary, IMTheme.appearance.chatFontSize)
        if (trailing != null) {
            Spacer(Modifier.height(2.dp))
            Box(Modifier.align(Alignment.End)) { trailing() }
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
