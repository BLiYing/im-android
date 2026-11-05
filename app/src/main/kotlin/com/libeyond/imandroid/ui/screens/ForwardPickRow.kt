package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.theme.IMTheme

// 行的尺寸照 iOS `IMForwardPickerCell`：34pt 头像、行高至少 50、勾选圈 22、间距 12
private val ROW_AVATAR = 34.dp
private val ROW_MIN_HEIGHT = 50.dp
private val CHECK_SIZE = 22.dp
private val ROW_GAP = 12.dp

/**
 * 转发 / 分享选择页的一行（最近聊天、我的好友、我的群聊三处同一种行）。
 * 多选态行首出勾选圈；单选态行尾 ›。
 */
@Composable
internal fun ForwardRow(conv: ConversationEntity, multi: Boolean, checked: Boolean, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val title = Forward.titleOf(conv)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .background(c.surface)
                .clickable(onClick = onClick)
                .padding(horizontal = d.space4, vertical = d.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (multi) {
                Box(
                    modifier = Modifier
                        .size(CHECK_SIZE)
                        .clip(CircleShape)
                        .background(if (checked) c.accent else c.neutralControl),
                    contentAlignment = Alignment.Center,
                ) {
                    // 勾用图标不用「✓」字形：部分机型会拿彩色 emoji 字体画它（VideoPlayer 里记过同一个坑）
                    if (checked) Image(Lucide.Check, null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(c.onAccent))
                }
                Spacer(Modifier.width(ROW_GAP))
            }
            IMAvatar(
                displayName = title,
                seed = if (conv.isGroup) conv.convId else conv.peerUid.ifBlank { conv.convId },
                avatarUrl = conv.avatarUrl,
                size = ROW_AVATAR,
            )
            Spacer(Modifier.width(ROW_GAP))
            Text(
                text = title,
                color = c.textPrimary,
                fontSize = 15.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!multi) {
                // 单选态行尾 ›（iOS disclosure indicator）；多选态由行首的圈承载选中，不再挂
                Image(Lucide.ChevronRight, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(c.textTertiary))
            }
        }
        // 分割线从名字起头（iOS 平铺表格的分割线缩进）
        val inset = d.space4 + ROW_AVATAR + ROW_GAP + if (multi) CHECK_SIZE + ROW_GAP else 0.dp
        Box(Modifier.fillMaxWidth().padding(start = inset).height(0.5.dp).background(c.separator))
    }
}

/** 「我的好友」「我的群聊」入口行：图标 + 文字 + ›，单选多选都显示，**不带勾选圈**（点了进子页）。 */
@Composable
internal fun SharePickEntryRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .background(c.surface)
                .clickable(onClick = onClick)
                .padding(horizontal = d.space4, vertical = d.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(ROW_AVATAR).clip(CircleShape).background(c.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Image(icon, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.accent))
            }
            Spacer(Modifier.width(ROW_GAP))
            Text(label, color = c.textPrimary, fontSize = 15.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Image(Lucide.ChevronRight, null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(c.textTertiary))
        }
        Box(Modifier.fillMaxWidth().padding(start = d.space4 + ROW_AVATAR + ROW_GAP).height(0.5.dp).background(c.separator))
    }
}

/** 分组小标题（「最近聊天」「好友」「群聊」、字母组）。 */
@Composable
internal fun SharePickHeader(text: String) {
    val d = IMTheme.dimens
    Text(
        text,
        color = IMTheme.colors.textTertiary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = d.space4, top = d.space4, bottom = 6.dp),
    )
}
