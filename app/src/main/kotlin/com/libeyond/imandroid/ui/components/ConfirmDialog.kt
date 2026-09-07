package com.libeyond.imandroid.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 二次确认对话框（对应 iOS 的 `UIAlertControllerStyleAlert`、Web 的 `askConfirm`）。
 *
 * **不可撤销 + 会影响别人看到的东西**的操作一律走它：踢设备、重置二维码、退出登录。
 * 底部动作菜单（[ActionSheet]）不能替代它——那是「选一个做什么」，不是「你确定吗」。
 */
@Composable
fun IMConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    cancelText: String = "取消",
) {
    val c = IMTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = c.textPrimary, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmText, color = if (destructive) c.danger else c.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelText, color = c.textSecondary) }
        },
        containerColor = c.surfaceElevated,
    )
}
