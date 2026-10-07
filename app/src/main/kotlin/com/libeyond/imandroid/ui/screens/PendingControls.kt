package com.libeyond.imandroid.ui.screens

import com.libeyond.imandroid.ui.components.unclippedBoundsInWindow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.X
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.components.MessageContextMenu
import com.libeyond.imandroid.ui.components.SheetItem
import com.libeyond.imandroid.ui.components.pressShrink
import com.libeyond.imandroid.data.PendingAction
import com.libeyond.imandroid.data.PendingMenu
import com.libeyond.imandroid.data.UploadState
import com.libeyond.imandroid.data.UploadStateText
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 待发媒体上的**控制钮盘**（对齐 iOS `IMMediaBubble` 的上传控制）：
 * 排队 = ✕（点了二次确认取消）；传输中 = ⏸；已暂停 = ▶；失败 = ↻（点了续传）；整包上传 / 其它 = 只显示环、不响应。
 * 环的进度来自 [state]；[failed] 时环归零、换成 ↻。
 */
@Composable
internal fun UploadControlDisc(
    state: UploadState?,
    failed: Boolean,
    /** 点钮：失败→重试；可暂停→切换；排队→（本函数里先确认）取消。 */
    onRetry: () -> Unit,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
) {
    val c = IMTheme.colors
    var confirm by remember { mutableStateOf(false) }
    val control = if (failed) null else state?.control
    val glyph = when {
        failed -> Lucide.RotateCw
        control == UploadState.Control.Cancel -> Lucide.X
        control == UploadState.Control.Pause -> Lucide.Pause
        control == UploadState.Control.Resume -> Lucide.Play
        else -> Lucide.ArrowUp
    }
    val tap: (() -> Unit)? = when {
        failed -> onRetry
        control == UploadState.Control.Cancel -> ({ confirm = true })
        control == UploadState.Control.Pause || control == UploadState.Control.Resume -> onToggle
        else -> null // 整包上传：不可中断，点了不处理
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(c.overlay)
            .let { m -> if (tap != null) m.clickable(onClick = tap) else m },
        contentAlignment = Alignment.Center,
    ) {
        if (!failed && state != null) {
            CircularProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.size(size),
                color = c.onMedia,
                trackColor = c.overlay,
                strokeWidth = 2.dp,
            )
        }
        Image(glyph, null, Modifier.size(size / 2.4f), colorFilter = ColorFilter.tint(c.onMedia))
    }
    if (confirm) {
        IMConfirmDialog(
            title = stringResource(R.string.chat_media_cancel_send_confirm),
            message = "",
            confirmText = stringResource(R.string.chat_msg_menu_cancel_send),
            onConfirm = onCancel,
            onDismiss = { confirm = false },
        )
    }
}

/** 媒体气泡左上角的「已传 / 总大小」小标。 */
@Composable
internal fun UploadBadge(state: UploadState, modifier: Modifier = Modifier) {
    val c = IMTheme.colors
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.overlay),
    ) {
        Text(
            UploadStateText.mediaBadge(state),
            color = c.onMedia, fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 待发气泡的长按菜单（iOS 同口径，判据见 [PendingMenu]）：复制（有文字才有）/ 取消发送（本地媒体件）/ 删除（失败）。
 * **没有「重发」项**（重发走红❗/ ↻）；菜单路径**不二次确认**——长按再点一下已是明确意图，
 * 二次确认只给「点排队气泡上的 ✕」那条易误触的路径。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PendingActions(
    contentType: String,
    failed: Boolean,
    copyText: String?,
    onCancel: () -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var rect by remember { mutableStateOf(Rect.Zero) }
    val acts = PendingMenu.actions(contentType, failed, !copyText.isNullOrEmpty())
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        Modifier.onGloballyPositioned { rect = it.unclippedBoundsInWindow() }
            // 与已发出的气泡同一套按压 → 浮起（2026-10-07 用户报「长按发送端与接收端效果不一样」：
            // 待发气泡此前没有预览、原位也不隐藏，只压暗背景）。这一层是整行宽，待发恒在右侧，缩放中心贴右缘
            .pressShrink(press, enabled = acts.isNotEmpty(), origin = PENDING_ORIGIN)
            .then(if (acts.isEmpty()) Modifier else Modifier.combinedClickable(
                onClick = {},
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    open = true
                },
                indication = null, interactionSource = press,
            )),
    ) {
        // 菜单开着时原位隐形（保留占位），由菜单里那份预览接管——同已发出消息的 ChatListItem.hidden
        Box(Modifier.alpha(if (open) 0f else 1f)) { content() }
        PendingMenuPopup(open, rect, acts, copyText, onCancel, preview = content) { open = false }
    }
}

/** 待发气泡（恒为自己发的、贴右侧）浮起 / 按压的中心：右缘居中。 */
private val PENDING_ORIGIN = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)

/**
 * 待发菜单的弹层本体：单条待发气泡与宫格里的待发格共用（iOS 同一份 `messageActionsForMessage:`）。
 * 与已发出消息同一个 [MessageContextMenu]（压暗背景 + 贴着锚点 + 带图标），不再是下拉菜单；
 * 待发件的气泡/格子本身就在原位，所以不重绘预览（`preview = null`，只压暗背景）。
 */
@Composable
internal fun PendingMenuPopup(
    open: Boolean,
    anchor: Rect,
    acts: List<PendingAction>,
    copyText: String?,
    onCancel: () -> Unit,
    /** 原位重绘的那一项（调用方负责把原位隐藏）。null = 只铺背景。 */
    preview: (@Composable () -> Unit)? = null,
    /** 浮起中心所在的那块；null = 整行右缘（待发恒在右侧）。 */
    focus: Rect? = null,
    onDismiss: () -> Unit,
) {
    if (!open || acts.isEmpty()) return
    val clipboard = LocalClipboardManager.current
    val items = acts.map { a ->
        val label = stringResource(
            when (a) {
                PendingAction.Copy -> R.string.common_copy
                PendingAction.CancelSend -> R.string.chat_msg_menu_cancel_send
                PendingAction.Delete -> R.string.common_delete
            },
        )
        val icon = when (a) {
            PendingAction.Copy -> Lucide.Copy
            PendingAction.CancelSend -> Lucide.X
            PendingAction.Delete -> Lucide.Trash2
        }
        SheetItem(label, destructive = a != PendingAction.Copy, icon = icon) {
            if (a == PendingAction.Copy) clipboard.setText(AnnotatedString(copyText.orEmpty())) else onCancel()
        }
    }
    // MessageContextMenu 是**树内**的全屏覆盖层（聊天页根上才铺得开）；待发气泡/格子里没有那么大的地方，
    // 直接放会被裁在这一格里。包一层全屏 Dialog 把它抬到窗口级，锚点仍是窗口坐标。
    // 背景模糊照样生效：LocalMenuBackdrop 会带进 Dialog，菜单在那里登记，模糊的是主窗口里的聊天页。
    // 锚点是**主窗口**坐标，而 Dialog 窗口从状态栏下方开始——不换算的话预览与菜单整体下移一个状态栏高，
    // 贴底的气泡菜单直接掉出屏幕（2026-10-07 OPPO 实测）。主窗口原点在这里取，Dialog 原点在里面量。
    // 同理，Dialog 窗口还会比可见区高出一截：菜单的可用高度按主窗口的可见底边算，不按 Dialog 自己的高度。
    val mainView = androidx.compose.ui.platform.LocalView.current
    val mainOrigin = remember(anchor) {
        IntArray(2).also { mainView.rootView.getLocationOnScreen(it) }.let { androidx.compose.ui.geometry.Offset(it[0].toFloat(), it[1].toFloat()) }
    }
    val mainBottom = mainOrigin.y + mainView.rootView.height
    val density = androidx.compose.ui.platform.LocalDensity.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // 关掉系统给 Dialog 的那层默认压暗：背景由菜单自己的材质色 + 模糊负责，叠两层就是一片死黑
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        androidx.compose.runtime.SideEffect { dialogWindow?.setDimAmount(0f) }
        var dialogOrigin by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
        Box(Modifier.fillMaxSize().onGloballyPositioned { dialogOrigin = it.positionOnScreen() }) {
            // 量到 Dialog 原点之前不画（只差一帧），免得先画在错位处再跳过去
            val o = dialogOrigin ?: return@Box
            val delta = mainOrigin - o
            val visibleH = with(density) { (mainBottom - o.y).toDp() }
            Box(Modifier.fillMaxWidth().height(visibleH)) {
                MessageContextMenu(
                    anchor = anchor.translate(delta), mine = true, items = items, onDismiss = onDismiss,
                    focus = (focus ?: Rect(anchor.right - 1f, anchor.top, anchor.right, anchor.bottom)).translate(delta),
                    preview = preview,
                )
            }
        }
    }
}
