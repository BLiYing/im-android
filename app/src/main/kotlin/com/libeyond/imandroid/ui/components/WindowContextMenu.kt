package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * [MessageContextMenu] 抬到**窗口级**的包装：调用处没有一块全屏的树内空间时用（待发气泡/格子、置顶面板里的气泡）。
 * 从 `PendingMenuPopup` 原样抽出（两处共用）。
 *
 * MessageContextMenu 是**树内**的全屏覆盖层（聊天页根上才铺得开）；直接放在一格 / 一张 sheet 里会被裁在那块里。
 * 包一层全屏 Dialog 把它抬到窗口级，锚点仍是**调用处所在窗口**的坐标。
 * 背景模糊照样生效：LocalMenuBackdrop 会带进 Dialog，菜单在那里登记，模糊的是调用处窗口里的页面。
 * 锚点是调用处窗口坐标，而 Dialog 窗口从状态栏下方开始——不换算的话预览与菜单整体下移一个状态栏高，
 * 贴底的气泡菜单直接掉出屏幕（2026-10-07 OPPO 实测）。调用处窗口原点在这里取，Dialog 原点在里面量。
 * 同理，Dialog 窗口还会比可见区高出一截：菜单的可用高度按调用处窗口的可见底边算，不按 Dialog 自己的高度。
 */
@Composable
fun WindowContextMenu(
    anchor: Rect,
    focus: Rect,
    mine: Boolean,
    items: List<SheetItem>,
    onDismiss: () -> Unit,
    preview: (@Composable () -> Unit)? = null,
) {
    val hostView = androidx.compose.ui.platform.LocalView.current
    val hostOrigin = remember(anchor) {
        IntArray(2).also { hostView.rootView.getLocationOnScreen(it) }.let { Offset(it[0].toFloat(), it[1].toFloat()) }
    }
    val hostBottom = hostOrigin.y + hostView.rootView.height
    val density = androidx.compose.ui.platform.LocalDensity.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // 关掉系统给 Dialog 的那层默认压暗：背景由菜单自己的材质色 + 模糊负责，叠两层就是一片死黑
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        androidx.compose.runtime.SideEffect { dialogWindow?.setDimAmount(0f) }
        var dialogOrigin by remember { mutableStateOf<Offset?>(null) }
        Box(Modifier.fillMaxSize().onGloballyPositioned { dialogOrigin = it.positionOnScreen() }) {
            // 量到 Dialog 原点之前不画（只差一帧），免得先画在错位处再跳过去
            val o = dialogOrigin ?: return@Box
            val delta = hostOrigin - o
            val visibleH = with(density) { (hostBottom - o.y).toDp() }
            Box(Modifier.fillMaxWidth().height(visibleH)) {
                MessageContextMenu(
                    anchor = anchor.translate(delta), mine = mine, items = items, onDismiss = onDismiss,
                    focus = focus.translate(delta), preview = preview,
                )
            }
        }
    }
}
