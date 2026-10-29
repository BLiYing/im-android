package com.libeyond.imandroid.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R

/**
 * 清除所有对话二级页（设计 `CLEAR_ALL_CONVERSATIONS_DESIGN.md` §1，草图 CLEAR_ALL_CONVERSATIONS_UX_SKETCH.html）。
 * 版式全在共用的 [DangerConfirmPage]；无密码框，红按钮永远可点（busy 时转圈禁用）。纯展示，请求与确认弹窗在 Host。
 */
@Composable
fun ClearAllConversationsScreen(
    error: String,
    submitting: Boolean,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    DangerConfirmPage(
        title = stringResource(R.string.privacy_clear_all_title),
        header = stringResource(R.string.privacy_clear_all_warn_title),
        consequences = listOf(
            R.string.privacy_clear_all_b1, R.string.privacy_clear_all_b2,
            R.string.privacy_clear_all_b3, R.string.privacy_clear_all_b4,
        ).map { stringResource(it) },
        hint = stringResource(R.string.privacy_clear_all_tip),
        error = error,
        buttonText = stringResource(R.string.privacy_clear_all_button),
        buttonEnabled = !submitting,
        loading = submitting,
        onSubmit = onSubmit,
        onBack = onBack,
    )
}
