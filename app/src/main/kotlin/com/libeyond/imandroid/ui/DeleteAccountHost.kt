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

/**
 * 注销成功后的外壳动作（走退出登录同一路径 + 清本机缓存回登录页），由 [AppRoot] 提供。
 * 用 CompositionLocal 是因为「看自己的资料页」有两个入口（「我」页 / 聊天·群·通讯录里点自己头像 → `UserProfileHost`），
 * 都要带注销组，不想为此给六处入口逐层透传回调。
 */
val LocalAccountDeleted = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * 注销页的确认身份弹层，由 [AppRoot] 在主界面这一层持有（随登录态清理：退出 / 被踢 / 注销回登录页即丢）。
 * 不放在注销页里：「还有群没转让」不会用掉验证码（PROTOCOL §7.3），用户去转让群再回来时注销页会重建，
 * 弹层状态挂在页面上的话保留的验证码就没了，「转让完回来直接重交」的承诺不成立。null = 没提供（预览），页面自建一份。
 */
val LocalDeleteAccountProof = androidx.compose.runtime.staticCompositionLocalOf<ProofController?> { null }

/** 阻断卡点群名 → 外壳开该群聊天页并叠上群资料页（[MainScreen] 提供，群主在那里转让/解散）。 */
val LocalOpenGroupInfo = androidx.compose.runtime.staticCompositionLocalOf<(com.libeyond.imandroid.data.db.ConversationEntity) -> Unit> { {} }

/**
 * 注销账号：二次确认 → 确认身份弹层（密码或短信验证码，PHONE_AUTH_DESIGN §7.2）→ `POST /account/delete` → 成功交给外壳 [onDeleted]
 * （走与退出登录同一路径并清本机聊天缓存，回登录页提示冷静期）。
 *
 * 提交挂 `client.scope`（同改密）：请求途中返回上一页不能把协程取消，否则服务端已注销、本机还留着登录态。
 */
@Composable
fun DeleteAccountHost(client: IMClient, onDeleted: () -> Unit, onBack: () -> Unit) {
    val hoisted = LocalDeleteAccountProof.current
    val proof = hoisted ?: remember(client) { ProofController.of(client, SecurityState.of(client)) }
    var error by remember { mutableStateOf("") }
    var ownedGroups by remember { mutableStateOf<List<OwnedGroup>>(emptyList()) }
    var confirming by remember { mutableStateOf(false) }

    fun fail(e: Throwable) {
        val api = e as? ApiException
        when {
            api?.code == ErrCode.NO_PERMISSION -> {
                ownedGroups = AccountApi.ownedGroups(api.data)
                error = if (ownedGroups.isEmpty()) api.userMessage(Str.s(R.string.err_request_failed, ErrCode.NO_PERMISSION.toString())) else ""
            }
            else -> error = e.userMessage(Str.s(R.string.err_request_failed, "account_delete"))
        }
    }

    // 证明本身错了留在弹层里重输；群主阻断等页面问题收起弹层（验证码保留，转让后可直接重交）
    fun submit() {
        error = ""
        proof.submit(call = { client.account.delete(it) }, onOk = { onDeleted() }, onPageError = ::fail)
    }

    val openGroup = LocalOpenGroupInfo.current
    BackHandler(onBack = onBack)
    DeleteAccountScreen(
        error = error,
        ownedGroups = ownedGroups,
        onOpenGroup = { g -> openGroup(client.groupConversationStubFor(g.convId, g.name, "")) },
        submitting = proof.busy,
        onSubmit = { confirming = true },
        onBack = onBack,
    )
    ProofSheetHost(proof)
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
