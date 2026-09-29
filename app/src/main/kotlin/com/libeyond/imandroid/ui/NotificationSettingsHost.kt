package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.NotificationExceptions
import com.libeyond.imandroid.data.NotificationNav
import com.libeyond.imandroid.data.NotificationPage
import com.libeyond.imandroid.data.NotificationSettingsStore
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.data.withType
import com.libeyond.imandroid.sdk.AlertPlayer
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.PushTransition
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

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by NotificationSettingsStore.settings.collectAsState()
    val owner = client.uid.orEmpty()
    // 初值 null（不是 emptyList()）= 本地库还没回第一份，同 `MainScreen.kt` 的既有判据——
    // 「添加例外」选择页的空态文案要能分清"还没读到数据"和"读到了、真的没有可选会话"，
    // 用 emptyList() 当初值会在库还没回数据时就抢答"没有可添加的会话"（`/code-review` 抓出）。
    val conversations by remember(owner) { client.repo.observeConversations(owner) }.collectAsState(initial = null)
    val hasVibrator = remember { AlertPlayer.hasVibrator(context) }
    val comingSoonHint = stringResource(R.string.ps_coming_soon_hint)

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
                onToggleBadge = { v -> NotificationSettingsStore.update(settings.copy(badge = settings.badge.copy(includeMuted = v))) },
                onComingSoon = { toast = comingSoonHint },
                onReset = { confirmReset = true },
                onBack = onBack,
            )

            NotificationPage.Type -> NotificationTypeScreen(
                group = kind,
                settings = settings.let { if (kind) it.group else it.private },
                exceptions = NotificationExceptions.of(conversations.orEmpty(), kind),
                onToggleEnabled = { v -> NotificationSettingsStore.update(settings.withType(kind) { it.copy(enabled = v) }) },
                onTogglePreview = { v -> NotificationSettingsStore.update(settings.withType(kind) { it.copy(preview = v) }) },
                onOpenSound = { page = NotificationPage.Sound },
                onAddException = { addExceptionOpen = true },
                onUnmute = { conv -> scope.launch { unmute(client, conv) } },
                onOpenChat = onOpenChat,
                onBack = { page = NotificationPage.Main },
            )

            NotificationPage.Sound -> NotificationSoundScreen(
                current = settings.let { if (kind) it.group.sound else it.private.sound },
                onSelect = { sound ->
                    NotificationSettingsStore.update(settings.withType(kind) { it.copy(sound = sound) })
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
            onConfirm = { NotificationSettingsStore.reset() },
            onDismiss = { confirmReset = false },
        )
    }

    // 「添加例外」会话选择页（NOTIFICATIONS_P1_DESIGN §2）：复用 ForwardPickerScreen，单选、
    // 点了立即免打扰（第一批 = 永久，不弹时长菜单——那是第二批的事）。
    if (addExceptionOpen) {
        ForwardPickerScreen(
            conversations = conversations.orEmpty(),
            filter = { convs, q -> Forward.exceptionPickable(convs, kind, q) },
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
                targets.firstOrNull()?.let { conv -> scope.launch { muteAsException(client, conv) } }
            },
        )
    }

    if (page == NotificationPage.Main) toast?.let { IMToast(it) { toast = null } }
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
 * 「添加例外」选中一个会话：立即设免打扰（第一批 = 永久）。同 [unmute] 的口径——
 * `pinned_at`/`marked_unread` 原样带回，接口是整体替换。
 */
private suspend fun muteAsException(client: IMClient, conv: ConversationEntity) {
    runCatching {
        client.conversationsApi.updateSettings(conv.convId, conv.pinnedAt, muted = true, conv.markedUnread)
    }.onFailure {
        log.w("mute_exception_failed", "convId" to conv.convId, "err" to it.javaClass.simpleName)
    }
    client.messages.refreshConversations()
}
