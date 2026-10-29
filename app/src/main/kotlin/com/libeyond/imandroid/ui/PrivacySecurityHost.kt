package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChangePasswordRules
import com.libeyond.imandroid.data.PasswordFeedback
import com.libeyond.imandroid.data.PrivacyPage
import com.libeyond.imandroid.data.PrivacySecurity
import com.libeyond.imandroid.data.SettingsSub
import com.libeyond.imandroid.data.Visibility
import com.libeyond.imandroid.data.VisibilityField
import com.libeyond.imandroid.data.VisibilityKind
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.PushTransition
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.ui.screens.AutoDeleteScreen
import com.libeyond.imandroid.ui.screens.BlockedUsersScreen
import com.libeyond.imandroid.ui.screens.ChangePasswordScreen
import com.libeyond.imandroid.ui.screens.PrivacySecurityScreen
import com.libeyond.imandroid.ui.screens.VisibilityScreen
import kotlinx.coroutines.launch

private data class PasswordForm(val old: String = "", val new: String = "", val confirm: String = "")

/**
 * 隐私与安全（对齐 iOS `IMPrivacySecurityViewController` → `IMBlockedListViewController` /
 * `IMChangePasswordViewController`）。状态与副作用都在这里，三个 Screen 纯展示（CODING_STYLE §7②）。
 */
@Composable
fun PrivacySecurityHost(
    client: IMClient,
    onBack: () -> Unit,
    /** 「导出我的数据」二级页由「我」页路由（`MePage.ExportData`）承载。 */
    onOpenExportData: () -> Unit,
    /** 「清除所有对话」二级页由「我」页路由（`MePage.ClearAllConversations`）承载。 */
    onOpenClearAll: () -> Unit,
    initialSub: SettingsSub = SettingsSub.None,
) {
    val scope = rememberCoroutineScope()

    var page by remember { mutableStateOf((initialSub as? SettingsSub.Privacy)?.page ?: PrivacyPage.Main) }
    /** 黑名单。容器页的计数与列表页**共用这一份**：进列表页时先显上次的，拉回来再换。 */
    var blocked by remember { mutableStateOf<List<FriendEntry>?>(null) }
    var blockedError by remember { mutableStateOf("") }
    /** 正在取消屏蔽的 user_id。**按人去重**：只挡同一行连点，别人的行照常可滑（iOS 同）。不参与渲染，故不是状态。 */
    val unblocking = remember { mutableSetOf<String>() }
    var form by remember { mutableStateOf(PasswordForm()) }
    var pwdError by remember { mutableStateOf<PasswordFeedback.Inline?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    /** 账号默认的自动删除档位（`GET /users/me/privacy`）；还没拉到为 null。`autoShown` 是选择页上打勾的那个（乐观，失败回滚）。 */
    var autoDefault by remember { mutableStateOf<Int?>(null) }
    var autoShown by remember { mutableStateOf<Int?>(null) }
    var autoSaving by remember { mutableStateOf(false) }
    /** 「谁能看到」四项（同一份 `GET /users/me/privacy`）；`visRev` 只用来在字段变化后触发重组。 */
    val lastSeenField = remember { VisibilityField() }
    val avatarField = remember { VisibilityField() }
    val bioField = remember { VisibilityField() }
    val phoneField = remember { VisibilityField() }
    var visRev by remember { mutableStateOf(0) }
    fun fieldOf(k: VisibilityKind) = when (k) {
        VisibilityKind.LastSeen -> lastSeenField
        VisibilityKind.Avatar -> avatarField
        VisibilityKind.Bio -> bioField
        VisibilityKind.Phone -> phoneField
    }

    suspend fun reloadBlocked() {
        runCatchingCancellable { client.contacts.friends(FriendEntry.BLOCKED) }
            .onSuccess { blocked = it; blockedError = "" }
            // 失败**保留当前内容**（iOS 同）：把已经看到的列表 / 计数清掉换一行报错，只会让人以为黑名单被清空了
            .onFailure { if (blocked == null) blockedError = it.userMessage(Str.s(R.string.blocked_load_failed)) }
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
                    .onFailure { toast = it.userMessage(Str.s(R.string.net_fallback_unblock_failed)) }
            } finally {
                unblocking.remove(f.userId)
            }
        }
    }

    /** 选中即保存（账号默认不弹确认，它只影响之后新建的会话）；成功回上一页刷新右值，失败 toast 并回滚勾选。 */
    fun saveAutoDefault(sec: Int) {
        if (autoSaving) return
        autoSaving = true
        autoShown = sec
        scope.launch {
            runCatchingCancellable { client.privacy.setAutoDeleteDefault(sec) }
                .onSuccess { autoDefault = sec; page = PrivacyPage.Main }
                .onFailure { autoShown = autoDefault; toast = Str.s(R.string.autodel_save_failed) }
            autoSaving = false
        }
    }

    /** 选中即保存（不弹确认），**PUT 只带被改的那一项**；成功回上一页，失败 toast 并回滚行值 / 勾选。 */
    fun saveVisibility(kind: VisibilityKind, v: Visibility) {
        if (fieldOf(kind).isSaving) return // 防连点：保存途中的重复点击静默忽略，不当作失败
        scope.launch {
            val ok = fieldOf(kind).choose(v) { level ->
                when (kind) {
                    VisibilityKind.LastSeen -> client.privacy.setLastSeenVisibility(level.wire)
                    VisibilityKind.Avatar -> client.privacy.setAvatarVisibility(level.wire)
                    VisibilityKind.Bio -> client.privacy.setBioVisibility(level.wire)
                    VisibilityKind.Phone -> client.privacy.setPhoneVisibility(level.wire)
                }
            }
            visRev++
            if (ok) page = PrivacyPage.Main else toast = Str.s(R.string.ps_visibility_save_failed)
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
    LaunchedEffect(page) { if (page == PrivacyPage.Main || page == PrivacyPage.Blocked) reloadBlocked() }
    // 进隐私页 / 选择页都取最新的账号默认档位（不推帧，别的端改了靠重新进页刷新）；失败保留已有值
    LaunchedEffect(page) {
        if (page == PrivacyPage.Blocked || page == PrivacyPage.ChangePassword) return@LaunchedEffect
        runCatchingCancellable { client.privacy.get() }.onSuccess {
            autoDefault = it.autoDeleteDefaultSec; if (!autoSaving) autoShown = it.autoDeleteDefaultSec
            lastSeenField.load(Visibility.parse(it.lastSeenVisibility))
            avatarField.load(Visibility.parse(it.avatarVisibility))
            bioField.load(Visibility.parse(it.bioVisibility))
            phoneField.load(Visibility.parse(it.phoneVisibility))
            visRev++
        }
    }

    PushTransition(targetState = page, depthOf = { if (it == PrivacyPage.Main) 0 else 1 }) { p ->
        when (p) {
            PrivacyPage.Main -> {
                BackHandler(onBack = onBack)
                PrivacySecurityScreen(
                    blockedCount = blocked?.size,
                    onBack = onBack,
                    onOpenBlocked = { page = PrivacyPage.Blocked },
                    onOpenChangePassword = { page = PrivacyPage.ChangePassword },
                    autoDeleteLabel = PrivacySecurity.autoDeleteLabel(autoDefault),
                    onOpenAutoDelete = { autoShown = autoDefault; page = PrivacyPage.AutoDelete },
                    lastSeenLabel = visRev.let { lastSeenField.committed?.label.orEmpty() },
                    avatarLabel = visRev.let { avatarField.committed?.label.orEmpty() },
                    bioLabel = visRev.let { bioField.committed?.label.orEmpty() },
                    phoneLabel = visRev.let { phoneField.committed?.label.orEmpty() },
                    onOpenLastSeen = { lastSeenField.resetShown(); page = PrivacyPage.LastSeen },
                    onOpenAvatar = { avatarField.resetShown(); page = PrivacyPage.Avatar },
                    onOpenBio = { bioField.resetShown(); page = PrivacyPage.Bio },
                    onOpenPhone = { phoneField.resetShown(); page = PrivacyPage.Phone },
                    onOpenExportData = onOpenExportData,
                    onOpenClearAll = onOpenClearAll,
                    onComingSoon = { toast = Str.s(R.string.common_coming_soon, it) },
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

            PrivacyPage.AutoDelete -> {
                BackHandler { page = PrivacyPage.Main }
                AutoDeleteScreen(
                    current = autoShown ?: 0,
                    footer = stringResource(R.string.autodel_default_footer),
                    onSelect = { saveAutoDefault(it) },
                    onBack = { page = PrivacyPage.Main },
                )
            }

            PrivacyPage.LastSeen, PrivacyPage.Avatar, PrivacyPage.Bio, PrivacyPage.Phone -> {
                val kind = when (p) {
                    PrivacyPage.LastSeen -> VisibilityKind.LastSeen
                    PrivacyPage.Avatar -> VisibilityKind.Avatar
                    PrivacyPage.Bio -> VisibilityKind.Bio
                    else -> VisibilityKind.Phone
                }
                BackHandler { page = PrivacyPage.Main }
                VisibilityScreen(
                    title = stringResource(when (kind) {
                        VisibilityKind.LastSeen -> R.string.ps_row_last_seen
                        VisibilityKind.Avatar -> R.string.ps_row_avatar
                        VisibilityKind.Bio -> R.string.ps_row_bio
                        VisibilityKind.Phone -> R.string.ps_row_phone_number
                    }),
                    footer = stringResource(when (kind) {
                        VisibilityKind.LastSeen -> R.string.ps_visibility_last_seen_footer
                        VisibilityKind.Avatar -> R.string.ps_visibility_avatar_footer
                        VisibilityKind.Bio -> R.string.ps_visibility_bio_footer
                        VisibilityKind.Phone -> R.string.ps_visibility_phone_footer
                    }),
                    current = visRev.let { fieldOf(kind).shown },
                    onSelect = { saveVisibility(kind, it) },
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
