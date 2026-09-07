package com.libeyond.imandroid.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

/**
 * 单行/多行文本输入对话框（改群名、群简介、群公告…）。
 *
 * **带字数上限并当场挡住**：服务端对这几个字段各有上限（群名 30 / 简介 200 / 公告 500），
 * 超了会回业务码。让用户打完一大段再被拒，不如根本打不进去。
 */
@Composable
fun IMTextPrompt(
    title: String,
    initial: String,
    maxLen: Int,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    multiline: Boolean = false,
    hint: String = "",
) {
    val c = IMTheme.colors
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = c.textPrimary, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                if (hint.isNotBlank()) {
                    Text(hint, color = c.textTertiary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                IMTextField(
                    value = value,
                    onValueChange = { if (it.length <= maxLen) value = it },
                    label = title,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${value.length}/$maxLen",
                    color = c.textTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }) { Text("确定", color = c.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
        containerColor = c.surfaceElevated,
    )
}
