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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
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
    cancelText: String = stringResource(R.string.common_cancel),
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
    /** 确认钮文案。举报这类「提交出去就收不回」的动作写清动词，别只写「确定」。 */
    confirmText: String = stringResource(R.string.common_confirm),
    /**
     * 撤下类二级动作按钮文案（对齐 iOS 群公告页独立的红色「撤下公告」按钮）。
     * 非空且当前已有内容时才显示，点击直接以空值确认——不必先手动清空文本框再点确定。
     */
    clearActionText: String? = null,
    /**
     * 输入框标签/占位；**默认不画**——标题就在正上方，再来一个同名浮标签只是把「群备注」写了两遍。
     * 标题是「添加好友」这类动作名时，或字段有天然占位（群备注的占位是群名）时才传（如「说一句，让对方知道你是谁」）。
     */
    label: String = "",
    /** true = [maxLen] 与计数按 Unicode 码点（与服务端 rune 口径一致，emoji 算 1）；默认按 UTF-16 长度，其它场景不变。 */
    countByCodePoint: Boolean = false,
) {
    val c = IMTheme.colors
    fun len(s: String) = if (countByCodePoint) s.codePointCount(0, s.length) else s.length
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
                    onValueChange = { if (len(it) <= maxLen) value = it },
                    label = label,
                    singleLine = !multiline,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${len(value)}/$maxLen",
                    color = c.textTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (clearActionText != null && initial.isNotBlank()) {
                    TextButton(onClick = { onConfirm("") }) {
                        Text(clearActionText, color = c.danger)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }) { Text(confirmText, color = c.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) }
        },
        containerColor = c.surfaceElevated,
    )
}
