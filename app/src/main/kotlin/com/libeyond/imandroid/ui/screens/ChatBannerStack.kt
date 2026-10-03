package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Megaphone
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.UserPlus
import com.composables.icons.lucide.X
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.theme.IMTheme

/** 一张横幅要画什么。三种横幅（入群申请 / 公告 / 置顶）共用同一张卡，只是图标、强调色、两行字不同。 */
data class BannerContent(
    val icon: ImageVector,
    val accent: Color,
    val kicker: String,
    val preview: String,
    /** 置顶多条时左侧竖条画成「上亮下暗」渐变，提示还有更多。 */
    val multiple: Boolean = false,
    /** 右侧「列表」钮：仅置顶且多于一条。 */
    val showList: Boolean = false,
    val closeLabel: String,
)

/**
 * 聊天页顶部的横幅叠放（对齐 iOS `IMChatBannerStack`）：自上而下 入群申请（蓝）→ 公告（橙）→ 置顶（主题色），
 * 各自独立显隐，三条可同时在。全部浮在聊天背景之上，卡片之间/上方 8dp 空隙透出壁纸。
 * 宿主（`ChatScreen`）量这一叠的高度，作为消息列表的顶部内边距，免得首屏消息被盖在下面。
 */
@Composable
fun ChatBannerStack(
    join: BannerContent?,
    announcement: BannerContent?,
    pinned: BannerContent?,
    onJoinTap: () -> Unit,
    onJoinClose: () -> Unit,
    onAnnouncementTap: () -> Unit,
    onAnnouncementClose: () -> Unit,
    onPinnedTap: () -> Unit,
    onPinnedList: () -> Unit,
    onPinnedClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        join?.let { BannerCard(it, onJoinTap, {}, onJoinClose) }
        announcement?.let { BannerCard(it, onAnnouncementTap, {}, onAnnouncementClose) }
        pinned?.let { BannerCard(it, onPinnedTap, onPinnedList, onPinnedClose) }
    }
}

/**
 * 单张横幅：8dp 空隙 + 44dp 卡片，左右各缩 8dp，12dp 圆角、1px 分隔线描边、`surfaceElevated` 底。
 * 左侧 3×26dp 竖条（多条置顶 = 上亮下暗渐变）；文字块 = 11sp 半粗强调色 kicker（10dp 图标 + 文本）+ 13sp 单行预览；
 * 右侧永远有 30dp 的「收起」钮，置顶多条再多一颗「列表」钮；整张卡（不含两颗钮）可点。
 */
@Composable
private fun BannerCard(b: BannerContent, onTap: () -> Unit, onList: () -> Unit, onClose: () -> Unit) {
    val c = IMTheme.colors
    Box(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp).height(44.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            color = c.surfaceElevated,
            border = BorderStroke(0.5.dp, c.separator),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 点击区：从卡左缘到「列表」钮左缘（两颗钮自己处理点击）
                Row(
                    Modifier.weight(1f).height(44.dp).clickable(onClick = onTap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.width(3.dp).height(26.dp).clip1_5()
                            .background(
                                if (b.multiple) {
                                    Brush.verticalGradient(
                                        0f to b.accent, 0.46f to b.accent, 0.52f to b.accent.copy(alpha = 0.28f), 1f to b.accent.copy(alpha = 0.28f),
                                    )
                                } else Brush.verticalGradient(listOf(b.accent, b.accent)),
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(b.icon, null, tint = b.accent, modifier = Modifier.size(10.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                b.kicker, color = b.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(b.preview, color = c.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // 列表钮：没有时也占位（文字右缘不随它出现/消失而跳）
                Box(Modifier.size(30.dp).then(if (b.showList) Modifier.clickable(onClick = onList) else Modifier), Alignment.Center) {
                    if (b.showList) Icon(Lucide.List, stringResource(R.string.chat_banner_all_pinned), tint = c.textSecondary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(2.dp))
                Box(Modifier.size(30.dp).clickable(onClick = onClose), Alignment.Center) {
                    Icon(Lucide.X, b.closeLabel, tint = c.textSecondary, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

private fun Modifier.clip1_5(): Modifier = this.then(Modifier.clip(RoundedCornerShape(1.5.dp)))

/** 图标对齐 iOS 的 SF Symbol（person.badge.plus / megaphone / pin.fill）。 */
object BannerIcons {
    val Join = Lucide.UserPlus
    val Announcement = Lucide.Megaphone
    val Pinned = Lucide.Pin
}
