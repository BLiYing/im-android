package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Quote
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.CardContent
import com.libeyond.imandroid.sdk.api.Favorite
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 收藏页各分类行的尺寸（**逐项取自 iOS**，别凭感觉改）。
 *
 * 出处：`IMFavoritesViewController.m heightForRow`、`IMFavoriteRowViews.m IMFavoriteRowCell`、
 * `IMDetailContactCell.m`、`IMFavoriteVoiceCell.m`。iOS 行高是固定值；本端用「下限」而不是死高，
 * 系统字号放大时内容撑开、不截断。
 */
internal object FavoriteRowMetrics {
    /** 文本 / 聊天记录行：标题 2 行 + 时间 + 来自X。 */
    val TEXT_ROW_HEIGHT = 92.dp

    /** 文件行（复用 [FileRow]）：文件名 + 状态 + 时间 + 来自X 四行。 */
    val FILE_ROW_HEIGHT = 92.dp

    /** 语音行（复用 [VoiceRow]）：迷你播放器 + 时间行 + 来自行。 */
    val VOICE_ROW_HEIGHT = 108.dp

    /** 名片行：`IMDetailContactCellHeightWithSource`（带来源行；不带来源是 64）。 */
    val CONTACT_ROW_HEIGHT = 82.dp

    /** 文本 / 记录行左侧图标块（`_tile`）：52 方块、圆角 10、内部 26 字形。 */
    val TILE = 52.dp
    val TILE_RADIUS = 10.dp
    val TILE_GLYPH = 26.dp

    /** 左右内边距（iOS 各 cell 均为 16）。 */
    val H_PADDING = 16.dp

    /** 图标块 / 头像与文字列的间距：文本行 12，名片行 11。 */
    val TILE_GAP = 12.dp
    val AVATAR_GAP = 11.dp

    /** 文本行三行（标题 / 时间 / 来自）的行距。 */
    val STACK_SPACING = 3.dp

    /** 名片行头像直径（`IMDetailContactCell` 44）。 */
    val AVATAR = 44.dp

    /** 标题行数：文本与聊天记录都封顶 2 行（给时间 + 来自X 留位）。 */
    const val TITLE_MAX_LINES = 2

    /** 字号：标题 15、时间 / 来自 12；名片行名字 16、副行与时间 13、来源 12。 */
    const val TITLE_SP = 15
    const val META_SP = 12
    const val CONTACT_NAME_SP = 16
    const val CONTACT_SUB_SP = 13
}

/**
 * 文本 / 聊天记录收藏一行（iOS `IMFavoriteRowCell`）：左 52dp 淡主色块 + 字形，右三行
 * 标题（15sp，最多 2 行）/ 时间（12sp，tertiary）/ 来自X（12sp，accent）。整行 ≥92dp。
 * 点开：文本是全文阅读页（FAVORITES_DESIGN §5.6），记录是聊天记录详情页；复制走长按。
 */
@Composable
internal fun GlyphFavoriteRow(
    glyph: ImageVector,
    glyphDescription: String,
    title: String,
    f: Favorite,
    source: String,
    onClick: () -> Unit,
    onLongPress: ((Rect) -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    val c = IMTheme.colors
    val m = FavoriteRowMetrics
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.surface)
                .archiveItemGestures(onClick = onClick, onLongPress = onLongPress)
                .heightIn(min = m.TEXT_ROW_HEIGHT)
                .padding(horizontal = m.H_PADDING, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(m.TILE).clip(RoundedCornerShape(m.TILE_RADIUS)).background(c.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    glyph, glyphDescription, Modifier.size(m.TILE_GLYPH),
                    colorFilter = ColorFilter.tint(c.accent),
                )
            }
            Spacer(Modifier.width(m.TILE_GAP))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.STACK_SPACING)) {
                Text(
                    title, color = c.textPrimary, fontSize = m.TITLE_SP.sp,
                    maxLines = m.TITLE_MAX_LINES, overflow = TextOverflow.Ellipsis,
                )
                if (f.createdAt > 0) {
                    Text(TimeFormat.fileDateTime(f.createdAt), color = c.textTertiary, fontSize = m.META_SP.sp)
                }
                SourceLine(source)
            }
            trailing?.invoke()
        }
        Box(Modifier.fillMaxWidth().padding(start = ArchiveSeparatorInset).height(0.5.dp).background(c.separator))
    }
}

/** 文本收藏行：引号字形 + 正文。 */
@Composable
internal fun TextFavoriteRow(
    f: Favorite,
    source: String,
    onClick: () -> Unit,
    onLongPress: ((Rect) -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) = GlyphFavoriteRow(
    Lucide.Quote, stringResource(R.string.favorites_category_text), f.content, f, source, onClick, onLongPress, trailing,
)

/** 聊天记录收藏行：气泡字形 + 记录标题（拿不到标题回「聊天记录」，`parseRecord` 已兜）。 */
@Composable
internal fun RecordFavoriteRow(
    f: Favorite,
    source: String,
    onClick: () -> Unit,
    onLongPress: ((Rect) -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    val title = CardContent.parseRecord(f.content, maxLines = 0)?.title
        ?: stringResource(R.string.record_chat_history)
    GlyphFavoriteRow(
        Lucide.MessageSquare, stringResource(R.string.record_chat_history), title, f, source,
        onClick, onLongPress, trailing,
    )
}

/**
 * 名片收藏行（iOS 直接复用详情页 `IMDetailContactCell`）：44dp 头像 + 名字（16sp，右端是收藏时间 13sp）+
 * `@句柄`（13sp）+ 「由 X 分享」（12sp，accent，独占一行）。整行 ≥82dp。
 * 脏名片（解析不出）→ 一行「名片无法显示」，仍可点 / 长按删除。
 */
@Composable
internal fun ContactFavoriteRow(
    f: Favorite,
    source: String,
    onClick: () -> Unit,
    onLongPress: ((Rect) -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    val c = IMTheme.colors
    val m = FavoriteRowMetrics
    val card = CardContent.parseContact(f.content)
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.surface)
                .archiveItemGestures(onClick = onClick, onLongPress = onLongPress)
                .heightIn(min = m.CONTACT_ROW_HEIGHT)
                .padding(horizontal = m.H_PADDING, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (card == null) {
                Text(
                    stringResource(R.string.card_contact_unavailable), color = c.textSecondary,
                    fontSize = m.CONTACT_NAME_SP.sp, modifier = Modifier.weight(1f),
                )
            } else {
                // 名字兜底链：昵称 → 句柄 → uid（与卡片气泡同口径，最末级才落 uid）
                val shown = card.nickname.ifBlank { card.username }.ifBlank { card.displayName }
                IMAvatar(displayName = shown, seed = card.uid, avatarUrl = card.avatarUrl, size = m.AVATAR)
                Spacer(Modifier.width(m.AVATAR_GAP))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            shown, color = c.textPrimary, fontSize = m.CONTACT_NAME_SP.sp,
                            fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            // 名字独占剩余宽度、时间贴右端：别再配一个 weight 的 Spacer——两个权重会把
                            // 空闲宽度对半分，长昵称在一半处就被省略
                            modifier = Modifier.weight(1f),
                        )
                        if (f.createdAt > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                TimeFormat.fileDateTime(f.createdAt), color = c.textSecondary,
                                fontSize = m.CONTACT_SUB_SP.sp, maxLines = 1,
                            )
                        }
                    }
                    if (card.handle.isNotEmpty()) {
                        Text(
                            card.handle, color = c.textSecondary, fontSize = m.CONTACT_SUB_SP.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (source.isNotBlank()) {
                        Text(
                            stringResource(R.string.contact_card_shared_by, source),
                            color = c.accent, fontSize = m.META_SP.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            trailing?.invoke()
        }
        Box(Modifier.fillMaxWidth().padding(start = ArchiveSeparatorInset).height(0.5.dp).background(c.separator))
    }
}
