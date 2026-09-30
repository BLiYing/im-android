package com.libeyond.imandroid.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.FcmPreference
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.NotificationExceptions
import com.libeyond.imandroid.data.NotificationNav
import com.libeyond.imandroid.data.NotificationPage
import com.libeyond.imandroid.data.NotificationPermission
import com.libeyond.imandroid.data.NotificationSettings
import com.libeyond.imandroid.data.NotificationSettingsStore
import com.libeyond.imandroid.data.MuteDuration
import com.libeyond.imandroid.data.accountFields
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.withType
import com.libeyond.imandroid.sdk.AlertPlayer
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.MuteDurationSheet
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.components.rememberMuteTick
import com.libeyond.imandroid.ui.screens.ForwardPickerScreen
import com.libeyond.imandroid.ui.screens.NotificationSettingsScreen
import com.libeyond.imandroid.ui.screens.NotificationSoundScreen
import com.libeyond.imandroid.ui.screens.NotificationTypeScreen
import kotlinx.coroutines.launch

private val log = IMLog.tag("IM.NotifUi")

/**
 * 「通知与提示音」接线层（NOTIFICATIONS_DESIGN 全篇）：主页 + 私聊/群聊子页 + 提示音选择页，
 * 内部用 [NotificationPage] 自成一条 push 链（同 `ChatDetailHost`/`GroupInfoHost` 的做法），
 * 不占用外层 `MePage` 的深度——外层只需要知道"打开/关闭整个通知设置"这一件事。
 */
@Composable
fun NotificationSettingsHost(
    client: IMClient,
    onOpenChat: (ConversationEntity) -> Unit,
    onBack: () -> Unit,
) {
    var page by remember { mutableStateOf(NotificationPage.Main) }
    /** 当前 Type/Sound 子页是关于私聊还是群聊——与 [page] 是两个独立维度，见 `data/NotificationNav.kt`。 */
    var kind by remember { mutableStateOf(false) }

    BackHandler {
        val prev = NotificationNav.back(page)
        if (prev != null) page = prev else onBack()
    }
    var confirmReset by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    /** 「添加例外」会话选择页是否敞开（NOTIFICATIONS_P1_DESIGN §2）——不是 push 链的一环，
     *  是叠在 Type 页上的卡片弹层，同 `ChatDetailHost` 里 `sharing` 那一路的挂法。 */
    var addExceptionOpen by remember { mutableStateOf(false) }
    /** 「添加例外」选完会话、还没选时长（NOTIFICATIONS_P1_DESIGN §4.1「选完会话先弹菜单」）。 */
    var pendingExceptionTarget by remember { mutableStateOf<ConversationEntity?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by NotificationSettingsStore.settings.collectAsState()
    // 「接收离线推送（本设备）」开关（M5 批次 2）：本地偏好 + FCM 令牌 PUT/DELETE，见 FcmTokenStore 类注释。
    val receivePushEnabled by FcmPreference.enabled.collectAsState()
    val owner = client.uid.orEmpty()
    // 初值 null（不是 emptyList()）= 本地库还没回第一份，同 `MainScreen.kt` 的既有判据——
    // 「添加例外」选择页的空态文案要能分清"还没读到数据"和"读到了、真的没有可选会话"，
    // 用 emptyList() 当初值会在库还没回数据时就抢答"没有可添加的会话"（`/code-review` 抓出）。
    val conversations by remember(owner) { client.repo.observeConversations(owner) }.collectAsState(initial = null)
    // 定时免打扰到期刷新（NOTIFICATIONS_P1_DESIGN §4.4）：例外列表与选择页过滤都要跟着到点重算。
    val muteTick = rememberMuteTick(conversations)
    val hasVibrator = remember { AlertPlayer.hasVibrator(context) }

    // 「通知权限」行（M5 批次 2，判据见 data/NotificationPermission.kt）。系统没有"权限变了"的回调，
    // 每次回到前台重查一遍——用户多半是去系统设置里改完再切回来的（同 iOS viewWillAppear 里重查）。
    var notificationsEnabled by remember { mutableStateOf(systemNotificationsEnabled(context)) }
    var explainPermission by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsEnabled = systemNotificationsEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    var rationaleBeforeRequest by remember { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsEnabled = systemNotificationsEnabled(context)
        if (NotificationPermission.shouldExplainAfterRequest(granted, rationaleBeforeRequest, showsRationale(context))) {
            explainPermission = true
        }
    }
    fun onPermissionRow() {
        when (NotificationPermission.onTap(notificationsEnabled, Build.VERSION.SDK_INT)) {
            NotificationPermission.TapAction.OpenSettings -> openNotificationSettings(context)
            NotificationPermission.TapAction.Explain -> explainPermission = true
            NotificationPermission.TapAction.Request -> {
                rationaleBeforeRequest = showsRationale(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    /**
     * 私聊/群聊/角标三项是**账号级**的（M5，PROTOCOL §6.13）：本地立刻生效 + PUT 同步给服务端，
     * 由 [com.libeyond.imandroid.data.AccountNotifySettingsStore.save] 统一处理（失败不回滚、标记
     * dirty、下次冷启动/重连再补）。应用内声音/振动/横幅（`inApp`）仍走纯本地的 [NotificationSettingsStore]，
     * 不经过这里——同一份逻辑的另外两端见 iOS `IMNotificationSettings`、Web `notifySettings.ts`。
     */
    fun updateAccount(next: NotificationSettings) {
        scope.launch { client.accountNotifySettingsStore.save(next.accountFields()) }
    }

    // 离开提示音选择页（回类型页 / 直接退出整条链）就停掉可能还在响的试听（§2.4「返回时停止试听」）
    LaunchedEffect(page) { if (page != NotificationPage.Sound) AlertPlayer.stopPreview() }

    PushTransition(targetState = page, depthOf = { it.depth }) { p ->
        when (p) {
            NotificationPage.Main -> NotificationSettingsScreen(
                settings = settings,
                hasVibrator = hasVibrator,
                onOpenType = { group -> kind = group; page = NotificationPage.Type },
                onToggleInAppSound = { v -> NotificationSettingsStore.update(settings.copy(inApp = settings.inApp.copy(sound = v))) },
                onToggleInAppVibrate = { v -> NotificationSettingsStore.update(settings.copy(inApp = settings.inApp.copy(vibrate = v))) },
                onToggleInAppPreview = { v -> NotificationSettingsStore.update(settings.copy(inApp = settings.inApp.copy(preview = v))) },
                onToggleBadge = { v -> updateAccount(settings.copy(badge = settings.badge.copy(includeMuted = v))) },
                receivePushEnabled = receivePushEnabled,
                onToggleReceivePush = { v ->
                    scope.launch {
                        client.fcmTokenStore.setEnabled(v) { com.libeyond.imandroid.fcm.FcmToken.current() }
                    }
                },
                notificationsEnabled = notificationsEnabled,
                onPermissionRow = { onPermissionRow() },
                onReset = { confirmReset = true },
                onBack = onBack,
            )

            NotificationPage.Type -> NotificationTypeScreen(
                group = kind,
                settings = settings.let { if (kind) it.group else it.private },
                exceptions = NotificationExceptions.of(conversations.orEmpty(), kind, muteTick),
                nowMs = muteTick,
                onToggleEnabled = { v -> updateAccount(settings.withType(kind) { it.copy(enabled = v) }) },
                onTogglePreview = { v -> updateAccount(settings.withType(kind) { it.copy(preview = v) }) },
                onOpenSound = { page = NotificationPage.Sound },
                onAddException = { addExceptionOpen = true },
                onUnmute = { conv -> scope.launch { unmute(client, conv) } },
                onOpenChat = onOpenChat,
                onBack = { page = NotificationPage.Main },
            )

            NotificationPage.Sound -> NotificationSoundScreen(
                current = settings.let { if (kind) it.group.sound else it.private.sound },
                onSelect = { sound ->
                    updateAccount(settings.withType(kind) { it.copy(sound = sound) })
                    AlertPlayer.preview(sound)
                },
                onBack = { page = NotificationPage.Type },
            )
        }
    }

    if (confirmReset) {
        IMConfirmDialog(
            title = stringResource(R.string.notif_reset_confirm_title),
            message = stringResource(R.string.notif_reset_confirm_message),
            confirmText = stringResource(R.string.notif_reset),
            onConfirm = {
                // inApp/desktop 是纯本地的，reset() 先把整份（含它们）落回默认；
                // 私聊/群聊/角标三项是账号级的，还要把默认值 PUT 上去，否则服务端仍留着改之前的值,
                // 下次这台或别的设备重拉又把刚重置的本地覆盖回去。
                NotificationSettingsStore.reset()
                updateAccount(NotificationSettings.DEFAULT)
            },
            onDismiss = { confirmReset = false },
        )
    }

    if (explainPermission) {
        IMConfirmDialog(
            title = stringResource(R.string.notif_system_permission),
            message = stringResource(R.string.notif_system_permission_denied_hint),
            confirmText = stringResource(R.string.notif_system_open_settings),
            onConfirm = { openNotificationSettings(context) },
            onDismiss = { explainPermission = false },
            destructive = false,
        )
    }

    // 「添加例外」会话选择页（NOTIFICATIONS_P1_DESIGN §2 + §4.1）：复用 ForwardPickerScreen，单选、
    // 选完先弹时长菜单，选中时长才真正免打扰（不再是第一批那样点了立即永久免打扰）。
    if (addExceptionOpen) {
        ForwardPickerScreen(
            conversations = conversations.orEmpty(),
            filter = { convs, q -> Forward.exceptionPickable(convs, kind, q, muteTick) },
            title = stringResource(R.string.notif_exceptions_add),
            footer = stringResource(if (kind) R.string.notif_exceptions_pick_footer_group else R.string.notif_exceptions_pick_footer_private),
            // 本地库还没回第一份（conversations == null）时不抢答"没有可添加的会话"——
            // 那一刻其实"还不知道"，不是"知道了、真的没有"（同 ForwardPickerScreen 自己的
            // query 判据："还没读到库里的数据时不能抢答，闪一下空态"）。
            emptyText = if (conversations == null) "" else stringResource(R.string.notif_exceptions_pick_empty),
            allowMulti = false,
            confirmSingleTap = false,
            onCancel = { addExceptionOpen = false },
            onToast = { toast = it },
            onConfirm = { targets ->
                addExceptionOpen = false
                pendingExceptionTarget = targets.firstOrNull()
            },
        )
    }

    // 选完会话后的时长菜单（§4.1「选完会话先弹菜单」）：没有「取消免打扰」项——选择页已经把
    // 免打扰中的会话过滤掉了，选出来的这一个必然还没免打扰。
    pendingExceptionTarget?.let { conv ->
        MuteDurationSheet(
            convTitle = Forward.titleOf(conv),
            showUnmute = false,
            onUnmute = {},
            onSelect = { d ->
                pendingExceptionTarget = null
                scope.launch { muteAsException(client, conv, d.muteUntil()) }
            },
            onDismiss = { pendingExceptionTarget = null },
        )
    }

    if (page == NotificationPage.Main) toast?.let { IMToast(it) { toast = null } }
}

private fun systemNotificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/** 系统是否还愿意再弹一次授权框的旁证（拒绝过一次后为 true）；Android 12 及以下没有这条权限，恒 false。 */
private fun showsRationale(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val activity = context as? Activity ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
}

/** 跳系统的「本应用通知设置」页（minSdk 26 起就有这个入口，不用退到应用详情页）。 */
private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { log.w("open_notification_settings_failed", "err" to it.javaClass.simpleName) }
}

/**
 * 「例外」列表左滑「取消免打扰」。**必须把现有的 `pinned_at`/`marked_unread` 原样带回**——
 * 接口是整体替换，漏传一项等于把它清零（同 `MainScreen.settings` 的口径，iOS
 * `IMChatDetailViewController+Actions` 踩过这个坑）。
 */
private suspend fun unmute(client: IMClient, conv: ConversationEntity) {
    runCatching {
        client.conversationsApi.updateSettings(conv.convId, conv.pinnedAt, muted = false, conv.markedUnread)
    }.onFailure {
        log.w("unmute_failed", "convId" to conv.convId, "err" to it.javaClass.simpleName)
    }
    client.messages.refreshConversations()
}

/**
 * 「添加例外」选中一个会话、再选完时长菜单：按选的时长设免打扰。同 [unmute] 的口径——
 * `pinned_at`/`marked_unread` 原样带回，接口是整体替换。
 */
private suspend fun muteAsException(client: IMClient, conv: ConversationEntity, muteUntil: Long) {
    runCatching {
        client.conversationsApi.updateSettings(conv.convId, conv.pinnedAt, muted = true, conv.markedUnread, muteUntil = muteUntil)
    }.onFailure {
        log.w("mute_exception_failed", "convId" to conv.convId, "err" to it.javaClass.simpleName)
    }
    client.messages.refreshConversations()
}
