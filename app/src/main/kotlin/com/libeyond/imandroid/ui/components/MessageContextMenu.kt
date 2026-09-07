package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 消息长按菜单——**浮在被长按的气泡旁边**，对齐 iOS 的 `UIContextMenuInteraction`。
 *
 * **不是底部弹窗。** 第一版做成了 ActionSheet（从底部升起），用户一眼指出交互不对：
 * 底部弹窗把「操作哪一条」这个信息丢了——手指在屏幕上半部长按，眼睛却要跑到屏幕底部去找菜单，
 * 而且菜单弹出后原来那条消息毫无标记。iOS/微信/Telegram 一律是**原位抬起 + 菜单贴着它**。
 *
 * 复刻的三件事（iOS `IMChatViewController+Menu.m`）：
 * ① **背景压暗**，点任意处关闭；
 * ② **被长按的气泡留在原位并高亮**——iOS 是把气泡光栅化成位图钉回原位（`UITargetedPreview`），
 *    本端直接**重绘一份气泡**（[preview]）：效果等价，且不用处理位图缓存与 flash 遮罩那些坑；
 * ③ **菜单贴着气泡**：默认在气泡下方，下方放不下就翻到上方；左右跟随消息方向
 *    （自己的靠右、对方的靠左），与气泡边缘对齐。
 *
 * @param anchor 气泡在**窗口坐标系**里的矩形（`boundsInWindow()`）。
 * @param mine 是不是自己发的——决定菜单左右对齐。
 * @param preview 原位重绘的气泡。传 null 则只压暗背景（媒体气泡重绘代价高时可用）。
 */
@Composable
fun MessageContextMenu(
    anchor: Rect,
    mine: Boolean,
    items: List<SheetItem>,
    onDismiss: () -> Unit,
    preview: (@Composable () -> Unit)? = null,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val density = LocalDensity.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val screenW = LocalConfiguration.current.screenWidthDp.dp

    val anchorTop: Dp = with(density) { anchor.top.toDp() }
    val anchorBottom: Dp = with(density) { anchor.bottom.toDp() }

    // 菜单高度按项数估算（每项 48 + 上下 8）——只用来判断"下方放不放得下"，
    // 估偏一点不影响正确性：放不下就翻上方，翻上方也放不下就贴顶。
    val estMenuH: Dp = (items.size * 48 + 16).dp
    val gap = 8.dp
    val below = anchorBottom + gap
    val fitsBelow = below + estMenuH < screenH - 24.dp
    val menuTop = if (fitsBelow) below else (anchorTop - gap - estMenuH).coerceAtLeast(24.dp)

    // **定宽**不是 min 宽：用 widthIn(min) 时「为所有人删除」这类长项会把卡片撑过 200dp，
    // 再按 200 算左边缘就会溢出屏幕右侧（实测第一版右边被裁掉）。
    val menuW = 220.dp
    // 左右跟随消息方向：自己的靠右、对方的靠左，与聊天页的横向内边距同一条边。
    // anchor 现在是**整行**（全宽），所以这里对齐的是屏幕边距而不是 anchor.right。
    val menuLeft = if (mine) {
        (screenW - menuW - d.chatAvatarLeading).coerceAtLeast(d.chatAvatarLeading)
    } else {
        d.chatAvatarLeading
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 压暗背景 + 点任意处关闭（iOS 是高斯模糊，本端用纯色压暗近似）。
            // **用 overlayStrong 不用 overlay**：40% 黑压在本就是深色的聊天页上几乎看不出来，
            // 实测第一版深色模式下"像没压暗"。这个令牌就是为浮层压暗准备的。
            .background(c.overlayStrong)
            .clickable(onClick = onDismiss),
    ) {
        // ① 气泡原位重绘：位置与真气泡逐像素对齐，抬起时没有跳变
        if (preview != null) {
            Box(
                modifier = Modifier
                    // anchor 是**整行**的矩形（全宽），所以只按 top 定位、宽度铺满，
                    // 行内的左右对齐由 Bubble 自己做——与真气泡逐像素同一套算法。
                    .padding(top = anchorTop)
                    .fillMaxWidth()
                    .padding(horizontal = d.chatAvatarLeading)
                    // 吞掉点击：点气泡本身不该关菜单（与 iOS 一致）
                    .clickable(enabled = false) {},
            ) { preview() }
        }

        // ② 菜单卡片
        Column(
            modifier = Modifier
                .padding(start = menuLeft, top = menuTop)
                .width(menuW)
                .clip(RoundedCornerShape(d.radiusCard))
                .background(c.surfaceElevated)
                .clickable(enabled = false) {},
        ) {
            items.forEachIndexed { i, item ->
                if (i > 0) {
                    Box(
                        Modifier
                            .padding(start = d.space4)
                            .height(0.5.dp)
                            .fillMaxWidth()
                            .background(c.separator),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            item.onClick()
                            onDismiss()
                        }
                        .padding(horizontal = d.space4, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.label,
                        color = if (item.destructive) c.danger else c.textPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.width(d.space2))
                }
            }
        }
    }
}
