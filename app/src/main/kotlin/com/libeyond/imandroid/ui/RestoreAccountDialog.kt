package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** 登录遇到「账号冷静期」时暂存的凭据与到期时间（用于「恢复账号」后继续登录）。 */
data class PendingRestore(val username: String, val password: String, val deleteAtMillis: Long?)

/** 冷静期内重新登录的恢复弹窗（DELETE_ACCOUNT_DESIGN §3.5）。恢复为非破坏性操作，按钮不画红。 */
@Composable
fun RestoreAccountDialog(pending: PendingRestore, onRestore: () -> Unit, onDismiss: () -> Unit) {
    val date = pending.deleteAtMillis?.let { DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(Date(it)) }.orEmpty()
    IMConfirmDialog(
        title = stringResource(R.string.delete_account_restore_title),
        // 服务端没给 delete_at 时不渲染空日期（im-web useRestoreOffer 同）
        message = if (date.isEmpty()) stringResource(R.string.delete_account_restore_title) else stringResource(R.string.delete_account_restore_message, date),
        confirmText = stringResource(R.string.delete_account_restore_action),
        onConfirm = onRestore,
        onDismiss = onDismiss,
        destructive = false,
    )
}
