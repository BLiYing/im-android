package com.libeyond.imandroid.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.launch

/**
 * 消息长按菜单——**浮在被长按的气泡旁边**，对齐 iOS 的 `UIContextMenuInteraction`。
 *
 * **不是底部弹窗。** 第一版做成了 ActionSheet（从底部升起），用户一眼指出交互不对：
 * 底部弹窗把「操作哪一条」这个信息丢了——手指在屏幕上半部长按，眼睛却要跑到屏幕底部去找菜单，
 * 而且菜单弹出后原来那条消息毫无标记。iOS/微信/Telegram 一律是**原位抬起 + 菜单贴着它**。
 *
 * 复刻 iOS `IMChatViewController+Menu.m` 的观感（2026-10-07 收口，设计见
 * `IMServer/docs/design/ANDROID_CONTEXT_MENU_DESIGN.md`）：
 * ① **背景**：被盖住的页面高斯模糊（[LocalMenuBackdrop]，Android 12+）+ 一层随明暗切换的材质色；
 *    Android 11 及以下没有实时模糊，换更不透明的纯色兜住。点任意处关闭。
 * ② **被长按的那一项原位浮起**：调用方隐藏原位、本层重绘一份（[preview]）。从按压时缩到的
 *    [MENU_PRESS_SCALE] 弹起，**以 [focus]（气泡本体）为中心**放大——不是整行的中心，否则贴边的气泡会横移。
 * ③ **菜单贴着它**：从靠近气泡的那个角缩放淡入；下方放不下时预览上移让位。
 * ④ **收起有动画**：点项 / 点背景 / 返回键都先回落、淡出，结束后才 [onDismiss]（调用方据此把原位显示回来）。
 *
 * @param anchor 预览所在区域在**窗口坐标系**里的矩形（`boundsInWindow()`），通常是整行。
 * @param focus 真正「浮起」的那一块（气泡本体 / 宫格那一格），缩放中心取它的中心；null = 同 [anchor]。
 * @param mine 是不是自己发的——决定菜单左右对齐。
 * @param preview 原位重绘的那一项。传 null 则只铺背景。预览**不可交互**：点它等于点背景（关菜单）。
 * @param popover 从按钮弹出的小菜单（右上角 ＋）：不模糊、几乎不压暗（iOS 那里是 `IMPopoverCard`，不是 UIContextMenu）。
 */
@Composable
fun MessageContextMenu(
    anchor: Rect,
    mine: Boolean,
    items: List<SheetItem>,
    onDismiss: () -> Unit,
    focus: Rect? = null,
    preview: (@Composable () -> Unit)? = null,
    popover: Boolean = false,
) {
    // 展开中的子菜单（对齐 iOS UIMenu 的 inline submenu）。null = 显示顶层。
    var submenu by remember { mutableStateOf<SheetItem?>(null) }
    val shown = submenu?.submenu ?: items
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // 可用区域取**本层实际铺开的大小**，不取 LocalConfiguration：待发气泡的菜单开在 Dialog 窗口里，
    // 那个窗口从状态栏下方开始、比整屏矮——按整屏算，下方空间会多算一个状态栏高，菜单被推出屏幕（2026-10-07 OPPO 实测）。
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val screenH = maxHeight
    val screenW = maxWidth

    val anchorTop: Dp = with(density) { anchor.top.toDp() }
    val anchorBottom: Dp = with(density) { anchor.bottom.toDp() }
    val anchorLeft: Dp = with(density) { anchor.left.toDp() }
    val anchorWidth: Dp = with(density) { anchor.width.toDp() }

    // —— 模糊登记：开着时让被盖住的页面糊掉；开始收起就注销（去模糊与收起动画同步）——
    val backdrop = if (popover) null else LocalMenuBackdrop.current
    var registered by remember { mutableStateOf(false) }
    DisposableEffect(backdrop) {
        backdrop?.let { it.openCount++; registered = true }
        onDispose { if (registered) backdrop?.let { it.openCount-- }; registered = false }
    }

    // 外观 ▸「动画」关掉时直接落到终态（iOS `IMAnimator.springPopIn` 同口径）
    val animate = IMTheme.appearance.animationsEnabled
    val fade = remember { Animatable(if (animate) 0f else 1f) }
    // 预览从按压时缩到的比例弹起（衔接 pressShrink，没有跳变），微微过冲后停在 1.02——那一下就是「浮起」
    val lift = remember { Animatable(if (animate) MENU_PRESS_SCALE else LIFTED) }
    val menuIn = remember { Animatable(if (animate) 0f else 1f) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        launch { fade.animateTo(1f, tween(MENU_FADE_MS)) }
        launch { lift.animateTo(LIFTED, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
        menuIn.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium))
    }

    /** 收起：先注销模糊、回落淡出，结束后才交给调用方移除（原位那一项此刻才显示回来，无闪烁）。 */
    fun dismiss() {
        if (leaving) return
        leaving = true
        if (registered) { backdrop?.let { it.openCount-- }; registered = false }
        if (!animate) { onDismiss(); return }
        scope.launch {
            launch { menuIn.animateTo(0f, tween(MENU_EXIT_MS)) }
            launch { lift.animateTo(1f, tween(MENU_EXIT_MS)) }
            fade.animateTo(0f, tween(MENU_EXIT_MS))
            onDismiss()
        }
    }
    // 返回键也走收起动画（比宿主的覆盖层返回链注册得晚，优先吃到）
    BackHandler(enabled = !leaving) { dismiss() }

    // —— 竖向布局：**菜单永远贴在气泡下方，气泡自己让位**（iOS UIContextMenu 同口径：预览上移、菜单在下）。——
    // 旧版「下方放不下就翻到上方、再放不下贴顶」在矮屏（Pixel 2 XL 仅 640dp）上，顶部只有一条消息时
    // 也会翻上去、并按 24dp 贴顶压在气泡身上。现在：下方放不下 → 预览上移 [shift]（有 preview 才能移，
    // 原行已被隐藏，不会出现重影），仍不够 → 菜单限高并可滚动，**绝不盖住被按的气泡**。
    // 没有 preview 的调用方退回「放不下翻上方」。
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
        if (leaving) 0.dp else shiftTarget,
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
    val menuLeft = if (mine) {
        (screenW - menuW - d.chatAvatarLeading).coerceAtLeast(d.chatAvatarLeading)
    } else {
        d.chatAvatarLeading
    }
    // 菜单从贴着气泡的那个角长出来（iOS 同）
    val menuOrigin = TransformOrigin(if (mine) 1f else 0f, if (flipAbove) 1f else 0f)
    // 预览缩放中心 = 气泡本体中心（相对 anchor 的比例）
    val pivot = (focus ?: anchor).let { f ->
        if (anchor.width <= 0f || anchor.height <= 0f) {
            TransformOrigin.Center
        } else {
            TransformOrigin(
                ((f.center.x - anchor.left) / anchor.width).coerceIn(0f, 1f),
                ((f.center.y - anchor.top) / anchor.height).coerceIn(0f, 1f),
            )
        }
    }
    // 挂了模糊的页面（聊天页、资料页、会话列表、收藏）用 iOS 那种材质罩；按钮小菜单几乎不压暗；都没挂的退回纯色压暗
    val scrim = when {
        popover -> c.popoverScrim
        backdrop == null -> c.overlayStrong
        menuBlurSupported -> c.menuScrim
        else -> c.menuScrimFlat
    }

    Box(Modifier.fillMaxSize()) {
        // ⓪ 背景：材质色淡入，点任意处关闭
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fade.value }
                .background(scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = ::dismiss,
                ),
        )
        // ① 预览：位置与原位逐像素对齐，抬起时没有跳变
        if (preview != null) {
            Box(
                modifier = Modifier
                    // **按 anchor 的左上角与宽度定位**，不假定"anchor 一定是整行"：
                    // 长按九宫格里一格时 anchor 就是那一格，浮起的也只该是那一格。
                    .padding(start = anchorLeft, top = anchorTop - shift)
                    .width(anchorWidth)
                    .graphicsLayer {
                        scaleX = lift.value
                        scaleY = lift.value
                        transformOrigin = pivot
                    }
                    // 预览**不可交互**：在 Initial 阶段截走所有指针事件，抬手 = 关菜单。
                    // 此前靠逐个把预览里的点击回调置空（链接、图片、宫格格子……），漏一个就是「菜单开着时又弹出一层」。
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                            do {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.pressed })
                            dismiss()
                        }
                    },
            ) {
                CompositionLocalProvider(
                    LocalMenuPreview provides true,
                    // 阴影交给预览里的气泡自己画（Bubble）：这一层是整行全宽的，在它身上投影就是整行高亮
                    LocalMenuPreviewLift provides !leaving,
                ) { preview() }
            }
        }

        // ② 菜单卡片
        Column(
            modifier = Modifier
                .padding(start = menuLeft, top = menuTop)
                .width(menuW)
                .graphicsLayer {
                    val t = menuIn.value
                    alpha = t.coerceIn(0f, 1f)
                    scaleX = 0.8f + 0.2f * t
                    scaleY = 0.8f + 0.2f * t
                    transformOrigin = menuOrigin
                }
                .heightIn(max = menuH)
                .shadow(16.dp, RoundedCornerShape(d.radiusCard))
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
                    if (leaving) return@MenuRow
                    if (item.submenu.isNotEmpty()) {
                        submenu = item
                    } else {
                        item.onClick()
                        dismiss()
                    }
                }
            }
        }
    }
    } // BoxWithConstraints
}

/** 浮起后的终态比例。 */
private const val LIFTED = 1.02f

/** 收起动画时长：比打开略快（iOS 收起也更干脆）。 */
private const val MENU_EXIT_MS = 160

@Composable
private fun MenuDivider() {
    Box(
        Modifier.padding(start = IMTheme.dimens.space4).height(0.5.dp)
            .fillMaxWidth().background(IMTheme.colors.separator),
    )
}

/** 长按预览是否已浮起（气泡据此自画阴影）。 */
val LocalMenuPreviewLift = compositionLocalOf { false }

/**
 * 正在画的是长按菜单里的那份预览。气泡据此**只留气泡本体**：头像、群昵称、「转发自」、红❗ 照常占位但不画
 * ——iOS 的 `UITargetedPreview` 浮起的只有气泡，旁边那些不跟着飘。
 */
val LocalMenuPreview = compositionLocalOf { false }

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

/**
 * 长按发生的位置（窗口坐标）。[area] 是预览铺开的区域（通常整行，预览按它逐像素对齐原位）；
 * [focus] 是真正浮起的那一块（气泡本体 / 宫格那一格），缩放中心取它的中心。
 */
data class MenuAnchor(val area: Rect, val focus: Rect = area) {
    companion object {
        val Zero = MenuAnchor(Rect.Zero)
    }
}
