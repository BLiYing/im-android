package com.libeyond.imandroid.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
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
    // 展开中的子菜单（对齐 iOS UIMenu 的 inline submenu）。null = 显示顶层。
    var submenu by remember { mutableStateOf<SheetItem?>(null) }
    val shown = submenu?.submenu ?: items
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val density = LocalDensity.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val screenW = LocalConfiguration.current.screenWidthDp.dp

    val anchorTop: Dp = with(density) { anchor.top.toDp() }
    val anchorBottom: Dp = with(density) { anchor.bottom.toDp() }
    val anchorLeft: Dp = with(density) { anchor.left.toDp() }
    val anchorWidth: Dp = with(density) { anchor.width.toDp() }

    // **抬起动画**：iOS 的 UIContextMenu 会把预览弹起来一点点，那一下正是"浮起"的观感来源。
    // 本端此前只是把原位那份换成一份一模一样的重绘 —— 对**自己发的消息**（无头像列、
    // 预览与原位逐像素重合）看上去就是"什么都没发生"，用户报的「发送端长按没有浮起效果」
    // 就是这个。对方消息当时反而"看着浮起来了"——那其实是预览漏了头像列、画偏了 36dp。
    // 两个毛病一个成因：预览没有自己的抬起表达。
    // 外观 ▸「动画」关掉时直接落到终态（iOS `IMAnimator.springPopIn` 同口径）
    val animate = IMTheme.appearance.animationsEnabled
    val lift = remember { Animatable(if (animate) 0.94f else 1.02f) }
    LaunchedEffect(Unit) {
        if (animate) lift.animateTo(1.02f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
    }

    // —— 竖向布局：**菜单永远贴在气泡下方，气泡自己让位**（iOS UIContextMenu 同口径：预览上移、菜单在下）。——
    // 旧版「下方放不下就翻到上方、再放不下贴顶」在矮屏（Pixel 2 XL 仅 640dp）上，顶部只有一条消息时
    // 也会翻上去、并按 24dp 贴顶压在气泡身上。现在：下方放不下 → 预览上移 [shift]（有 preview 才能移，
    // 原行已被隐藏，不会出现重影），仍不够 → 菜单限高并可滚动，**绝不盖住被按的气泡**。
    // 没有 preview 的调用方（待发气泡，原行就在原位、移不动）退回「放不下翻上方」。
    val rowH = 53.dp
    // 子菜单里多一行「返回」
    val estMenuH: Dp = rowH * (shown.size + if (submenu != null) 1 else 0)
    val gap = 8.dp
    val edge = 24.dp
    val limit = screenH - edge
    val canShift = preview != null
    val deficit = (anchorBottom + gap + estMenuH - limit).coerceAtLeast(0.dp)
    val shiftTarget: Dp = if (canShift) minOf(deficit, (anchorTop - edge).coerceAtLeast(0.dp)) else 0.dp
    val shift by animateDpAsState(
        shiftTarget,
        if (animate) spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow) else snap(),
        label = "menuShift",
    )
    val belowTop = anchorBottom - shiftTarget + gap
    val roomBelow = limit - belowTop
    val flipAbove = !canShift && roomBelow < estMenuH
    val menuH: Dp = if (flipAbove) estMenuH else minOf(estMenuH, roomBelow.coerceAtLeast(rowH * 2))
    val menuTop = if (flipAbove) (anchorTop - gap - estMenuH).coerceAtLeast(edge) else anchorBottom - shift + gap

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
                    // **按 anchor 的左上角与宽度定位**，不再假定"anchor 一定是整行"：
                    // 长按九宫格里一格时 anchor 就是那一格，浮起的也只该是那一格。
                    .padding(start = anchorLeft, top = anchorTop - shift)
                    .width(anchorWidth)
                    .graphicsLayer {
                        scaleX = lift.value
                        scaleY = lift.value
                    },
                // **不吞点击**：这一层是整行全宽的，吞了就等于「气泡旁边的空白区点了没反应」——
                // 2026-09-08 用户报的正是这个。iOS 点预览本身也是关菜单，所以让点击穿到背景最省事。
            ) {
                // 阴影交给预览里的气泡自己画（Bubble）：这一层是整行全宽的，在它身上投影就是整行高亮
                androidx.compose.runtime.CompositionLocalProvider(LocalMenuPreviewLift provides (lift.value > 1f)) { preview() }
            }
        }

        // ② 菜单卡片
        Column(
            modifier = Modifier
                .padding(start = menuLeft, top = menuTop)
                .width(menuW)
                .heightIn(max = menuH)
                .clip(RoundedCornerShape(d.radiusCard))
                .background(c.surfaceElevated)
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState()),
        ) {
            // 子菜单里给一行返回上一级——否则进了子菜单只能关掉重来
            if (submenu != null) {
                MenuRow(
                    SheetItem("‹ " + stringResource(R.string.common_back), icon = null) { },
                    onClick = { submenu = null },
                )
                MenuDivider()
            }
            shown.forEachIndexed { i, item ->
                if (i > 0) MenuDivider()
                MenuRow(item) {
                    if (item.submenu.isNotEmpty()) {
                        submenu = item
                    } else {
                        item.onClick()
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuDivider() {
    Box(
        Modifier.padding(start = IMTheme.dimens.space4).height(0.5.dp)
            .fillMaxWidth().background(IMTheme.colors.separator),
    )
}

/** 长按预览是否已浮起（气泡据此自画阴影）。 */
val LocalMenuPreviewLift = androidx.compose.runtime.compositionLocalOf { false }

/** 一行：图标 + 文案（+ 有子菜单时右侧 `›`）。图标列即便某项没图标也占位，文字才对得齐。 */
@Composable
private fun MenuRow(item: SheetItem, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val fg = if (item.destructive) c.danger else c.textPrimary
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = d.space4, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp)) {
            item.icon?.let {
                androidx.compose.foundation.Image(
                    it, null, Modifier.width(18.dp).height(18.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(fg),
                )
            }
        }
        Text(item.label, color = fg, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f))
        if (item.submenu.isNotEmpty()) Text("›", color = c.textTertiary)
    }
}
