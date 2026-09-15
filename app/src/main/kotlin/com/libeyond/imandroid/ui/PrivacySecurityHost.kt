package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.ChangePasswordRules
import com.libeyond.imandroid.data.PasswordFeedback
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.screens.BlockedUsersScreen
import com.libeyond.imandroid.ui.screens.ChangePasswordScreen
import com.libeyond.imandroid.ui.screens.PrivacySecurityScreen
import kotlinx.coroutines.launch

/** 「隐私与安全」里的页面。 */
private enum class PrivacyPage { Main, Blocked, ChangePassword }

private data class PasswordForm(val old: String = "", val new: String = "", val confirm: String = "")

/**
 * 隐私与安全（对齐 iOS `IMPrivacySecurityViewController` → `IMBlockedListViewController` /
 * `IMChangePasswordViewController`）。状态与副作用都在这里，三个 Screen 纯展示（CODING_STYLE §7②）。
 */
@Composable
fun PrivacySecurityHost(client: IMClient, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var page by remember { mutableStateOf(PrivacyPage.Main) }
    /** 黑名单。容器页的计数与列表页**共用这一份**：进列表页时先显上次的，拉回来再换。 */
    var blocked by remember { mutableStateOf<List<FriendEntry>?>(null) }
    var blockedError by remember { mutableStateOf("") }
    /** 正在取消屏蔽的 user_id。**按人去重**：只挡同一行连点，别人的行照常可滑（iOS 同）。不参与渲染，故不是状态。 */
    val unblocking = remember { mutableSetOf<String>() }
    var form by remember { mutableStateOf(PasswordForm()) }
    var pwdError by remember { mutableStateOf<PasswordFeedback.Inline?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    suspend fun reloadBlocked() {
        runCatchingCancellable { client.contacts.friends(FriendEntry.BLOCKED) }
            .onSuccess { blocked = it; blockedError = "" }
            // 失败**保留当前内容**（iOS 同）：把已经看到的列表 / 计数清掉换一行报错，只会让人以为黑名单被清空了
            .onFailure { if (blocked == null) blockedError = it.userMessage("加载已屏蔽的用户失败") }
    }

    fun unblock(f: FriendEntry) {
        if (!unblocking.add(f.userId)) return
        scope.launch {
            try {
                runCatchingCancellable { client.contacts.unblock(f.userId) }
                    .onSuccess {
                        // 先本地摘掉那一行（不等重拉，行立刻消失、计数立刻 -1），再拿服务端权威数据
                        blocked = blocked?.filterNot { it.userId == f.userId }
                        reloadBlocked()
                    }
                    .onFailure { toast = it.userMessage("取消屏蔽失败") }
            } finally {
                unblocking.remove(f.userId)
            }
        }
    }

    fun submitPassword() {
        if (submitting) return
        ChangePasswordRules.validate(form.old, form.new, form.confirm)?.let { pwdError = it; return }
        val (old, new) = form.old to form.new
        pwdError = null
        submitting = true
        // 挂 client.scope 而不是页面作用域：成功应答里带着轮换出的新续期凭据，
        // 请求途中返回上一页若把协程取消了，服务端照样改密并作废旧凭据，本机却没接住新的（见 IMClient.changePassword）
        client.scope.launch {
            runCatchingCancellable { client.changePassword(old, new) }
                .onSuccess {
                    form = PasswordForm()
                    if (page == PrivacyPage.ChangePassword) page = PrivacyPage.Main
                    toast = ChangePasswordRules.SUCCESS_TOAST
                }
                .onFailure { e ->
                    val code = (e as? ApiException)?.code
                    val fb = code?.let(ChangePasswordRules::feedbackFor)
                        ?: PasswordFeedback.Toast(e.userMessage(ChangePasswordRules.FALLBACK))
                    when (fb) {
                        is PasswordFeedback.Inline -> pwdError = fb
                        is PasswordFeedback.Toast -> toast = fb.message
                    }
                }
            submitting = false
        }
    }

    fun leavePassword() {
        // 密码不在内存里多留；提交中则保留，结果回来时还要用
        if (!submitting) { form = PasswordForm(); pwdError = null }
        page = PrivacyPage.Main
    }

    // 每回到容器页 / 进列表页都重拉（iOS 两页的 viewWillAppear 同）
    LaunchedEffect(page) { if (page != PrivacyPage.ChangePassword) reloadBlocked() }

    PushTransition(targetState = page, depthOf = { if (it == PrivacyPage.Main) 0 else 1 }) { p ->
        when (p) {
            PrivacyPage.Main -> {
                BackHandler(onBack = onBack)
                PrivacySecurityScreen(
                    blockedCount = blocked?.size,
                    onBack = onBack,
                    onOpenBlocked = { page = PrivacyPage.Blocked },
                    onOpenChangePassword = { page = PrivacyPage.ChangePassword },
                    onComingSoon = { toast = "「$it」还没做" },
                )
            }

            PrivacyPage.Blocked -> {
                BackHandler { page = PrivacyPage.Main }
                BlockedUsersScreen(
                    blocked = blocked,
                    error = blockedError,
                    onUnblock = { unblock(it) },
                    onBack = { page = PrivacyPage.Main },
                )
            }

            PrivacyPage.ChangePassword -> {
                BackHandler { leavePassword() }
                ChangePasswordScreen(
                    oldPassword = form.old,
                    newPassword = form.new,
                    confirmPassword = form.confirm,
                    // 一改动就清红字（iOS `fieldChanged:`）：给一次重来的机会，但不清输入
                    onOldChange = { form = form.copy(old = it); pwdError = null },
                    onNewChange = { form = form.copy(new = it); pwdError = null },
                    onConfirmChange = { form = form.copy(confirm = it); pwdError = null },
                    error = pwdError,
                    submitting = submitting,
                    onSubmit = { submitPassword() },
                    onBack = { leavePassword() },
                )
            }
        }
    }

    toast?.let { IMToast(it) { toast = null } }
}
