package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.clearAllConversations
import com.libeyond.imandroid.sdk.visibleConversationCount
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.screens.ClearAllConversationsScreen
import kotlinx.coroutines.launch

/**
 * 清除所有对话（`MePage.ClearAllConversations`）：红按钮 → 二次确认（正文带 N = 页面打开时本机可见会话数）
 * → `POST /users/me/conversations/clear` → 成功回隐私页并弹 toast（N 以服务端 `cleared` 为准，0 时「没有可清除的对话」）。
 *
 * 请求挂 `client.scope`（同注销账号）：途中返回上一页不取消请求，服务端已清则本机也必须跟上；返回后不再 toast。
 * 失败：按钮恢复、红字原因，**本机零变更**（本机清理只在 HTTP 成功后发生，见 [clearAllConversations]）。
 */
@Composable
fun ClearAllConversationsHost(client: IMClient, onDone: (String) -> Unit, onBack: () -> Unit) {
    var visibleCount by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var alive by remember { mutableStateOf(true) }
    val done by rememberUpdatedState(onDone)

    LaunchedEffect(Unit) { visibleCount = client.visibleConversationCount() }
    DisposableEffect(Unit) { onDispose { alive = false } }

    fun submit() {
        if (submitting) return
        submitting = true
        error = ""
        client.scope.launch {
            runCatchingCancellable { client.clearAllConversations() }
                .onSuccess { n ->
                    if (alive) done(
                        if (n > 0) Str.s(R.string.privacy_clear_all_done, n) else Str.s(R.string.privacy_clear_all_none),
                    )
                }
                .onFailure { error = it.userMessage(Str.s(R.string.err_request_failed, "clear_all")) }
            submitting = false
        }
    }

    BackHandler(onBack = onBack)
    ClearAllConversationsScreen(
        error = error,
        submitting = submitting,
        onSubmit = { confirming = true },
        onBack = onBack,
    )
    if (confirming) {
        IMConfirmDialog(
            title = stringResource(R.string.privacy_clear_all_confirm_title),
            message = stringResource(R.string.privacy_clear_all_confirm_body, visibleCount),
            confirmText = stringResource(R.string.privacy_clear_all_confirm_ok),
            onConfirm = { confirming = false; submit() },
            onDismiss = { confirming = false },
        )
    }
}
