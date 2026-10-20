package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.AccountApi
import com.libeyond.imandroid.sdk.api.OwnedGroup
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.screens.DeleteAccountScreen
import kotlinx.coroutines.launch

/**
 * 注销成功后的外壳动作（走退出登录同一路径 + 清本机缓存回登录页），由 [AppRoot] 提供。
 * 用 CompositionLocal 是因为「看自己的资料页」有两个入口（「我」页 / 聊天·群·通讯录里点自己头像 → `UserProfileHost`），
 * 都要带注销组，不想为此给六处入口逐层透传回调。
 */
val LocalAccountDeleted = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** 阻断卡点群名 → 外壳开该群聊天页并叠上群资料页（[MainScreen] 提供，群主在那里转让/解散）。 */
val LocalOpenGroupInfo = androidx.compose.runtime.staticCompositionLocalOf<(com.libeyond.imandroid.data.db.ConversationEntity) -> Unit> { {} }

/**
 * 注销账号：输入密码 → 二次确认 → `POST /account/delete` → 成功交给外壳 [onDeleted]
 * （走与退出登录同一路径并清本机聊天缓存，回登录页提示冷静期）。
 *
 * 提交挂 `client.scope`（同改密）：请求途中返回上一页不能把协程取消，否则服务端已注销、本机还留着登录态。
 */
@Composable
fun DeleteAccountHost(client: IMClient, onDeleted: () -> Unit, onBack: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var ownedGroups by remember { mutableStateOf<List<OwnedGroup>>(emptyList()) }
    var submitting by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    fun fail(e: Throwable) {
        val api = e as? ApiException
        when {
            api?.code == ErrCode.NO_PERMISSION -> {
                ownedGroups = AccountApi.ownedGroups(api.data)
                error = if (ownedGroups.isEmpty()) api.userMessage(Str.s(R.string.err_request_failed, ErrCode.NO_PERMISSION.toString())) else ""
            }
            api?.code == ErrCode.WRONG_PASSWORD -> error = Str.s(R.string.err_200002)
            else -> error = e.userMessage(Str.s(R.string.err_request_failed, "account_delete"))
        }
    }

    fun submit() {
        if (submitting) return
        submitting = true
        error = ""
        client.scope.launch {
            runCatchingCancellable { client.account.delete(password) }
                .onSuccess { onDeleted() }
                .onFailure { fail(it) }
            submitting = false
        }
    }

    val openGroup = LocalOpenGroupInfo.current
    BackHandler(onBack = onBack)
    DeleteAccountScreen(
        password = password,
        onPasswordChange = { password = it; error = "" },
        error = error,
        ownedGroups = ownedGroups,
        onOpenGroup = { g -> openGroup(client.groupConversationStubFor(g.convId, g.name, "")) },
        submitting = submitting,
        onSubmit = { confirming = true },
        onBack = onBack,
    )
    if (confirming) {
        IMConfirmDialog(
            title = stringResource(R.string.delete_account_confirm_title),
            message = stringResource(R.string.delete_account_confirm_message),
            confirmText = stringResource(R.string.delete_account_confirm_action),
            onConfirm = { confirming = false; submit() },
            onDismiss = { confirming = false },
        )
    }
}
