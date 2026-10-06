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
import com.libeyond.imandroid.data.SettingsRoute
import com.libeyond.imandroid.data.SettingsSub
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
    /** 来自首页全局搜索「设置」分组的落点：直接开到那一页（返回键沿各页原链退回本页）。 */
    initialRoute: SettingsRoute? = null,
    /** 落点已读走：外壳据此清掉它，否则之后手动切回「我」tab 会被旧落点再带进二级页。 */
    onRouteConsumed: () -> Unit = {},
) {
    // 深链落点的三处状态与约束（别随手合并，实测过：搜 Wi-Fi → 点自动下载视频 → 返回逐级；切回我 tab 不被旧落点带入）：
    //  1. [initialRoute]：MainScreen 持有，点搜索命中时置值。MeHost 每次进组合只在 remember 初值里读一次，
    //     **之后不再看它**（所以它后来变成 null 不会影响已在显示的页）。
    //  2. [onRouteConsumed]：进组合后立刻通知外壳清掉 1；不清则下次手动切回「我」tab（MeHost 重新进组合）
    //     又会读到旧落点而被带进二级页。MeHost 是随 tab 切换进出组合的，故必须由外壳清，不能靠 MeHost 自己记。
    //  3. [sub]：只给各 Host 当「初始页」用（其内部再自己 push/pop）。回到列表就复位为 None，
    //     否则同一个 MeHost 里再手点同一行，会被残留的 sub 直接带进更深层。
    // 想简化为「外壳一次性交值」需把 1/2 改为事件流，且要覆盖「tab 切换重进组合」，风险高于收益，故保持。
    LaunchedEffect(Unit) { if (initialRoute != null) onRouteConsumed() }
    // rememberSaveable：重建 Activity（切语言等）后停在原二级页；`sub` 含不可序列化的对象，不存，重建后回该页首层
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialRoute?.page ?: MePage.List) }
    var sub by remember { mutableStateOf(initialRoute?.sub ?: SettingsSub.None) }
    LaunchedEffect(page) { if (page == MePage.List) sub = SettingsSub.None }
    // 先用本机副本顶上：断网拉不到 /users/me 时头部仍是真名字，不掉成「未命名用户」
    // 「我」列表的滚动位置：放在 Host（不随二级页进出而销毁），返回时停在进去前的位置
    val listScroll = androidx.compose.foundation.rememberScrollState()
    var me by remember { mutableStateOf<UserCard?>(client.cachedMyProfile()) }
    var confirmLogout by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 每次回到列表页都重拉：从编辑页保存后返回，头部要立刻是新昵称/新头像。
    // key 写 page 而不是 Unit——`LaunchedEffect(Unit)` 只在进入组合时跑一次，
    // 编辑完回来不会重跑（CODING_STYLE §4 的那条坑）。
    // **挂在转场外面**：转场期间新旧两页同时在组合里，挂在页面里会随进场再跑一遍
    LaunchedEffect(page) {
        if (page == MePage.List) {
            runCatchingCancellable { client.contacts.me() }
                .onSuccess { me = it; client.cacheMyProfile(it) }
                .onFailure { /* 静默：头部沿用本机副本（没有才回退句柄 + 首字母圈），不为一次拉取失败挡住整页 */ }
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
                initialSub = sub,
                onOpenChat = onOpenChat,
                onBack = { page = MePage.List },
            )
            MePage.Devices -> DevicesHost(client = client, onBack = { page = MePage.List })
            MePage.DataStorage -> DataStorageHost(client = client, initialSub = sub, onBack = { page = MePage.List })
            MePage.Privacy -> PrivacySecurityHost(client = client, initialSub = sub, onBack = { page = MePage.List })
            MePage.Qr -> QrCardHost(client = client, me = me, onBack = { page = MePage.List })
            MePage.ShareCard -> ShareMyCardHost(client = client, me = me, onBack = { page = MePage.List })
            MePage.Language -> LanguageHost(onBack = { page = MePage.List })
            MePage.Appearance -> AppearanceHost(onBack = { page = MePage.List })
            MePage.PowerSaving -> PowerSavingHost(client = client, onBack = { page = MePage.List })
            MePage.Profile -> MyProfileHost(
                client = client,
                card = me,
                onChanged = { me = it; client.cacheMyProfile(it) },
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
                        scrollState = listScroll,
                        onOpenProfile = { page = MePage.Profile },
                        onOpenQr = { page = MePage.Qr },
                        onOpenDevices = { page = MePage.Devices },
                        onOpenDataStorage = { page = MePage.DataStorage },
                        onOpenPrivacy = { page = MePage.Privacy },
                        onOpenFavorites = { page = MePage.Favorites },
                        onOpenCallHistory = { page = MePage.CallHistory },
                        onOpenNotifications = { page = MePage.Notifications },
                        onOpenLanguage = { page = MePage.Language },
                        onOpenAppearance = { page = MePage.Appearance },
                        onOpenPowerSaving = { page = MePage.PowerSaving },
                        powerSavingLabel = powerSavingEntryLabel(),
                        onShareCard = { page = MePage.ShareCard },
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
