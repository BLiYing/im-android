package com.libeyond.imandroid.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.WebLinks
import com.libeyond.imandroid.data.qrGroupActionFor
import com.libeyond.imandroid.data.qrRelationToProfileRelation
import com.libeyond.imandroid.data.QrGroupAction
import com.libeyond.imandroid.data.qrUnknownDomain
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.QrGroupCard
import com.libeyond.imandroid.sdk.api.QrLoginScanInfo
import com.libeyond.imandroid.sdk.api.QrResolved
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.LocalOpenLink
import com.libeyond.imandroid.ui.components.LocalOpenQrScan
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.launch

/** 待展示的资料页种子（扫码/点链接解析出 kind=user 之后）。 */
private data class QrProfileSeed(val userId: String, val relation: String, val seed: UserCard)

/**
 * 扫码/点链接加群的路由宿主（QRCODE P0 接收方半，对齐 iOS `IMQRResultRouter`）。
 *
 * 挂在 [MainScreen] 内部（不是更外层的 `AppRoot`）：路由到「进群聊/单聊」要改 `openConv`，
 * 那份状态是 `MainScreen` 的私有变量，宿主离它太远够不着。
 *
 * 同时**覆盖**外层 `WebLinkHost` 提供的 [LocalOpenLink]：点一条链接先判是不是本站名片/群邀请链接
 * （[WebLinks.isOwnInviteLink]）——是就直接 resolve + 路由（原生加群/资料流程，不出 App）；
 * 不是则退回原来那份（真正的浏览器打开），顺序对齐 iOS `openLink:` 先调 `routeInviteLinkIfOwn:`。
 *
 * 另提供 [LocalOpenQrScan]，供会话列表 ＋ 菜单的「扫一扫」触发取景页。
 */
@Composable
internal fun QrRouteHost(
    client: IMClient,
    onOpenChat: (ConversationEntity) -> Unit,
    content: @Composable () -> Unit,
) {
    val fallbackOpenLink = LocalOpenLink.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var scanning by remember { mutableStateOf(false) }
    var groupPreview by remember { mutableStateOf<Pair<QrGroupCard, String>?>(null) }
    var profile by remember { mutableStateOf<QrProfileSeed?>(null) }
    var loginConfirm by remember { mutableStateOf<QrLoginScanInfo?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var expiredAlert by remember { mutableStateOf(false) }
    var unknownText by remember { mutableStateOf<String?>(null) }

    fun route(resolved: QrResolved, raw: String) {
        when (resolved) {
            is QrResolved.User -> {
                val card = resolved.card
                if (card.userId.isEmpty()) {
                    toast = Str.s(R.string.qr_result_bad_content)
                } else if (card.relation == "self") {
                    // 扫自己的码：不给「加好友」，这里只提示——本端「我的二维码」另有独立入口，
                    // 不在扫码结果里重复跳一次（同 iOS 的「大概率是想给别人看」判断，只是不强行带它去那页）。
                    toast = Str.s(R.string.qr_result_own_card)
                } else {
                    profile = QrProfileSeed(
                        userId = card.userId,
                        relation = qrRelationToProfileRelation(card.relation),
                        seed = UserCard(
                            userId = card.userId, username = card.username,
                            nickname = card.nickname, avatarUrl = card.avatarUrl,
                        ),
                    )
                }
            }
            is QrResolved.Group -> {
                if (resolved.card.groupId.isEmpty()) toast = Str.s(R.string.qr_result_bad_content)
                else groupPreview = resolved.card to raw
            }
            is QrResolved.Login -> {
                if (resolved.ticket.isEmpty()) {
                    toast = Str.s(R.string.qr_result_bad_content)
                } else {
                    // 先 /qr/login/scan 拿 Web 端设备/IP/位置，再进确认页——对齐 iOS `routeLogin:`。
                    // resolve 已校验票据可用；scan 若并发过期/被抢会回 200110，走统一失效弹窗。
                    scope.launch {
                        runCatchingCancellable { client.qr.loginScan(resolved.ticket) }
                            .onSuccess { loginConfirm = it }
                            .onFailure { e ->
                                if (e is ApiException && e.code == ErrCode.QR_EXPIRED) expiredAlert = true
                                else toast = e.userMessage(Str.s(R.string.qr_login_confirm_recognize_failed))
                            }
                    }
                }
            }
            is QrResolved.Unknown -> unknownText = resolved.text.ifEmpty { Str.s(R.string.qr_result_unrecognized) }
        }
    }

    fun resolveAndRoute(raw: String) {
        scope.launch {
            runCatchingCancellable { client.qr.resolve(raw) }
                .onSuccess { route(it, raw) }
                .onFailure { e ->
                    if (e is ApiException && e.code == ErrCode.QR_EXPIRED) expiredAlert = true
                    else toast = e.userMessage(Str.s(R.string.qr_result_recognize_failed))
                }
        }
    }

    val open: (String) -> Unit = { raw ->
        if (WebLinks.isOwnInviteLink(raw, client.host)) resolveAndRoute(raw) else fallbackOpenLink?.invoke(raw)
    }

    CompositionLocalProvider(
        LocalOpenLink provides open,
        LocalOpenQrScan provides { scanning = true },
    ) {
        content()

        if (scanning) {
            QrScanHost(
                onResult = { raw -> scanning = false; resolveAndRoute(raw) },
                onClose = { scanning = false },
                myCard = { QrCardHost(client = client, me = client.cachedMyProfile(), embedded = true, onBack = {}) },
            )
        }

        groupPreview?.let { (card, raw) ->
            GroupJoinPreviewHost(
                card = card,
                onSubmit = { hello ->
                    val action = qrGroupActionFor(card)
                    // 先退出预览页、再异步 join——对齐 iOS `submitTapped` 的 pop 在前、回调在后
                    // （见 GroupJoinPreviewHost 的注释：本页的协程作用域会随之被取消，不能拿它发请求）。
                    groupPreview = null
                    when (action) {
                        QrGroupAction.ENTER ->
                            onOpenChat(client.groupConversationStubFor(card.groupId, card.name, card.avatarUrl))
                        QrGroupAction.JOIN, QrGroupAction.APPLY -> scope.launch {
                            runCatchingCancellable { client.groups.join(raw, hello) }
                                .onSuccess { info ->
                                    onOpenChat(client.groupConversationStubFor(info.convId, info.name, info.avatarUrl))
                                }
                                .onFailure { e ->
                                    toast = if (e is ApiException && e.code == ErrCode.GROUP_JOIN_PENDING) {
                                        Str.s(R.string.qr_toast_join_requested)
                                    } else {
                                        e.userMessage(Str.s(R.string.net_fallback_group_join))
                                    }
                                }
                        }
                        QrGroupAction.DISABLED -> Unit
                    }
                },
                onBack = { groupPreview = null },
            )
        }

        profile?.let { p ->
            MemberProfileHost(
                client = client,
                userId = p.userId,
                knownFriends = emptyMap(), // 信息页进页自己重拉好友表定型关系
                name = p.seed.nickname,
                avatarUrl = p.seed.avatarUrl,
                onOpenChat = { chat -> profile = null; onOpenChat(chat) },
                onBack = { profile = null },
            )
        }

        loginConfirm?.let { info ->
            QrLoginConfirmHost(
                client = client,
                ticket = info.ticket,
                device = info.device,
                ip = info.ip,
                location = info.location,
                onDone = { toast = it },
                onBack = { loginConfirm = null },
            )
        }

        unknownText?.let { text ->
            val domain = remember(text) { qrUnknownDomain(text) }
            AlertDialog(
                onDismissRequest = { unknownText = null },
                title = { Text(stringResource(R.string.qr_result_title), color = IMTheme.colors.textPrimary, style = MaterialTheme.typography.titleMedium) },
                text = {
                    Text(
                        if (domain != null) stringResource(R.string.qr_result_phishing_message, text, domain) else text,
                        color = IMTheme.colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    androidx.compose.foundation.layout.Row {
                        TextButton(onClick = {
                            unknownText = null
                            clipboard.setText(AnnotatedString(text))
                            toast = Str.s(R.string.common_copied)
                        }) { Text(stringResource(R.string.qr_result_copy_content), color = IMTheme.colors.accent) }
                        if (domain != null) {
                            TextButton(onClick = { unknownText = null; fallbackOpenLink?.invoke(text) }) {
                                Text(stringResource(R.string.qr_result_open_in_browser), color = IMTheme.colors.accent)
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { unknownText = null }) { Text(stringResource(R.string.common_cancel), color = IMTheme.colors.textSecondary) } },
                containerColor = IMTheme.colors.surfaceElevated,
            )
        }

        if (expiredAlert) {
            AlertDialog(
                onDismissRequest = { expiredAlert = false },
                title = { Text(stringResource(R.string.qr_invalid_title), color = IMTheme.colors.textPrimary, style = MaterialTheme.typography.titleMedium) },
                text = {
                    Text(
                        stringResource(R.string.qr_invalid_note),
                        color = IMTheme.colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { expiredAlert = false }) { Text(stringResource(R.string.common_got_it), color = IMTheme.colors.accent) }
                },
                containerColor = IMTheme.colors.surfaceElevated,
            )
        }

        toast?.let { t -> IMToast(t) { toast = null } }
    }
}
