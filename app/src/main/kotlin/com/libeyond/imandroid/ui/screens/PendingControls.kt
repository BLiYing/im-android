package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.X
import com.libeyond.imandroid.R
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
    val acts = PendingMenu.actions(contentType, failed, !copyText.isNullOrEmpty())
    Box(
        if (acts.isEmpty()) Modifier else Modifier.combinedClickable(
            onClick = {}, onLongClick = { open = true },
            indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        ),
    ) {
        content()
        PendingMenuPopup(open, acts, copyText, onCancel) { open = false }
    }
}

/** 待发菜单的弹层本体：单条待发气泡与宫格里的待发格共用（iOS 同一份 `messageActionsForMessage:`）。 */
@Composable
internal fun PendingMenuPopup(
    open: Boolean,
    acts: List<PendingAction>,
    copyText: String?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    DropdownMenu(expanded = open && acts.isNotEmpty(), onDismissRequest = onDismiss) {
        acts.forEach { a ->
            val danger = a != PendingAction.Copy
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            when (a) {
                                PendingAction.Copy -> R.string.common_copy
                                PendingAction.CancelSend -> R.string.chat_msg_menu_cancel_send
                                PendingAction.Delete -> R.string.common_delete
                            },
                        ),
                        color = if (danger) IMTheme.colors.danger else IMTheme.colors.textPrimary,
                    )
                },
                onClick = {
                    onDismiss()
                    if (a == PendingAction.Copy) clipboard.setText(AnnotatedString(copyText.orEmpty())) else onCancel()
                },
            )
        }
    }
}
