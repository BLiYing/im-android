package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.LanguageStore
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.data.MePage
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.screens.MeScreen
import com.libeyond.imandroid.ui.screens.languageCurrentLabel

/**
 * 「我」页（对齐 iOS `IMSettingsViewController` 及其 push 出去的几页）。
 *
 * 这里只做**路由 + 本人资料的取用**，各页自己的状态在各自的 Host 里
 * ——把设备列表、二维码、编辑表单的状态都堆进这一个 Composable，就是
 * `App.tsx` 长到四千行的第一步（CODING_STYLE §7）。
 *
 * @param bottomBar 底部 Tab 栏，**只在根页画**；二级页整屏铺满（判据 `PushNav.showsTabBar`，外壳 [TabRoot]）。
 */
@Composable
fun MeHost(
    client: IMClient,
    onLogout: () -> Unit,
    bottomBar: @Composable () -> Unit,
    /** 收藏里的名片 → 资料页 →「发消息」：交给外壳进聊天页（与通讯录那条同一个出口）。 */
    onOpenChat: (com.libeyond.imandroid.data.db.ConversationEntity) -> Unit = {},
) {
    var page by remember { mutableStateOf(MePage.List) }
    var me by remember { mutableStateOf<UserCard?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 每次回到列表页都重拉：从编辑页保存后返回，头部要立刻是新昵称/新头像。
    // key 写 page 而不是 Unit——`LaunchedEffect(Unit)` 只在进入组合时跑一次，
    // 编辑完回来不会重跑（CODING_STYLE §4 的那条坑）。
    // **挂在转场外面**：转场期间新旧两页同时在组合里，挂在页面里会随进场再跑一遍
    LaunchedEffect(page) {
        if (page == MePage.List) {
            runCatchingCancellable { client.contacts.me() }
                .onSuccess { me = it }
                .onFailure { /* 静默：头部回退本地句柄 + 首字母圈，不为一次拉取失败挡住整页 */ }
        }
    }

    PushTransition(targetState = page, depthOf = { it.depth }) { p ->
        when (p) {
            MePage.Favorites -> FavoritesHost(
                client = client,
                onOpenChat = onOpenChat,
                onBack = { page = MePage.List },
            )
            MePage.CallHistory -> CallHistoryHost(
                client = client,
                onOpenChat = onOpenChat,
                onBack = { page = MePage.List },
            )
            MePage.Notifications -> NotificationSettingsHost(
                client = client,
                onOpenChat = onOpenChat,
                onBack = { page = MePage.List },
            )
            MePage.Devices -> DevicesHost(client = client, onBack = { page = MePage.List })
            MePage.DataStorage -> DataStorageHost(client = client, onBack = { page = MePage.List })
            MePage.Privacy -> PrivacySecurityHost(client = client, onBack = { page = MePage.List })
            MePage.Qr -> QrCardHost(client = client, me = me, onBack = { page = MePage.List })
            MePage.Language -> LanguageHost(onBack = { page = MePage.List })
            MePage.Profile -> MyProfileHost(
                client = client,
                card = me,
                onChanged = { me = it },
                onBack = { page = MePage.List },
            )
            MePage.List -> {
                val languagePref by LanguageStore.pref.collectAsState()
                TabRoot(bottomBar) {
                    MeScreen(
                        me = me,
                        fallbackName = client.myPublicName(),
                        seed = client.uid.orEmpty(),
                        languageLabel = languageCurrentLabel(languagePref, LanguageStore.resolved),
                        onOpenProfile = { page = MePage.Profile },
                        onOpenQr = { page = MePage.Qr },
                        onOpenDevices = { page = MePage.Devices },
                        onOpenDataStorage = { page = MePage.DataStorage },
                        onOpenPrivacy = { page = MePage.Privacy },
                        onOpenFavorites = { page = MePage.Favorites },
                        onOpenCallHistory = { page = MePage.CallHistory },
                        onOpenNotifications = { page = MePage.Notifications },
                        onOpenLanguage = { page = MePage.Language },
                        onComingSoon = { toast = Str.s(R.string.common_coming_soon, it) },
                        onLogout = { confirmLogout = true },
                    )
                }
            }
        }
    }

    if (confirmLogout) {
        IMConfirmDialog(
            title = stringResource(R.string.settings_logout),
            message = stringResource(R.string.settings_logout_confirm_message),
            confirmText = stringResource(R.string.settings_logout),
            onConfirm = onLogout,
            onDismiss = { confirmLogout = false },
        )
    }

    if (page == MePage.List) toast?.let { IMToast(it) { toast = null } }
}
