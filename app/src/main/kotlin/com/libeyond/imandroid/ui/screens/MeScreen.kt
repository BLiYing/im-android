package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Contrast
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.IdCard
import com.composables.icons.lucide.Laptop
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Phone
import com.composables.icons.lucide.QrCode
import com.composables.icons.lucide.Zap
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSettingsRow
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/** 一行设置的模型（数据驱动，对齐 iOS `IMSettingsRow`）：加一项 = 数组加一项，渲染层不改。 */
private data class MeRow(
    val title: String,
    val icon: ImageVector?,
    val iconBg: Color,
    val rightValue: String = "",
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 「我」页（对齐 iOS `IMSettingsViewController`）。
 *
 * 分组与顺序**逐行照抄 iOS**（它自己是照 Telegram「我」页排的，不是微信）：
 * 组一 收藏/通话/设备/文件夹/名片、组二 通知/隐私/存储/外观/省电/语言、组三 退出登录。
 * **未实现的项也照样列出**并落到「敬请期待」——把没做的行删掉会让三端的「我」页
 * 长得不一样，用户换个端就找不到东西了；列出来至少位置是对的。
 *
 * 与 iOS 的已知差异（正当，不修）：iOS 头部是灵动岛水滴遮罩 + 滚动形变的液态标题栏，
 * 那是 iOS 26 的原生观感；本端用普通的居中头部 + 固定标题栏。
 */
@Composable
fun MeScreen(
    /** 服务端权威资料；还没拉到时为 null，用 [fallbackName] 兜底渲染，不显骨架屏。 */
    me: UserCard?,
    /** 拉到资料前的显示名（本地会话里的 @句柄）。 */
    fallbackName: String,
    /** 头像取色种子，一律用 uid（稳定，改昵称不变色）。 */
    seed: String,
    onOpenProfile: () -> Unit,
    onOpenQr: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenDataStorage: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenCallHistory: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenLanguage: () -> Unit,
    /** 语言行右值，如「跟随系统（简体中文）」——由调用方算好传入（[com.libeyond.imandroid.data.LanguageStore]）。 */
    languageLabel: String,
    onComingSoon: (String) -> Unit,
    onLogout: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val ic = IMTheme.settingsIcons

    val savedMessages = stringResource(R.string.common_saved_messages)
    val recentCalls = stringResource(R.string.ios_settings_row_recent_calls)
    val devices = stringResource(R.string.settings_row_devices)
    val folders = stringResource(R.string.settings_row_folders)
    val shareCard = stringResource(R.string.settings_info_share_card)
    val notifications = stringResource(R.string.ios_settings_row_notifications)
    val privacy = stringResource(R.string.settings_row_privacy)
    val dataStorage = stringResource(R.string.ios_settings_row_data_storage)
    val appearance = stringResource(R.string.ios_settings_row_appearance)
    val powerSaving = stringResource(R.string.ios_settings_row_power_saving)
    val off = stringResource(R.string.common_off)
    val logout = stringResource(R.string.settings_logout)

    val groups = listOf(
        listOf(
            MeRow(savedMessages, Lucide.Bookmark, ic.blue, onClick = onOpenFavorites),
            MeRow(recentCalls, Lucide.Phone, ic.green, onClick = onOpenCallHistory),
            MeRow(devices, Lucide.Laptop, ic.orange, onClick = onOpenDevices),
            MeRow(folders, Lucide.Folder, ic.blue) { onComingSoon(folders) },
            // 与左上角「我的二维码」并列：二维码给**面对面**，名片消息给**线上**。
            MeRow(shareCard, Lucide.IdCard, ic.teal) { onComingSoon(shareCard) },
        ),
        listOf(
            MeRow(notifications, Lucide.Bell, ic.red, onClick = onOpenNotifications),
            MeRow(privacy, Lucide.Lock, ic.gray, onClick = onOpenPrivacy),
            MeRow(dataStorage, Lucide.HardDrive, ic.green, onClick = onOpenDataStorage),
            MeRow(appearance, Lucide.Contrast, ic.blue) { onComingSoon(appearance) },
            MeRow(powerSaving, Lucide.Zap, ic.yellow, rightValue = off) { onComingSoon(powerSaving) },
            MeRow(stringResource(R.string.settings_language_title), Lucide.Globe, ic.purple, rightValue = languageLabel, onClick = onOpenLanguage),
        ),
        listOf(
            MeRow(logout, null, Color.Unspecified, destructive = true, onClick = onLogout),
        ),
    )

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(
            title = stringResource(R.string.ios_tab_me),
            leftIcon = Lucide.QrCode,
            leftDescription = stringResource(R.string.settings_info_my_qr),
            onLeft = onOpenQr,
            actionText = stringResource(R.string.common_edit),
            onAction = onOpenProfile,
        )

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ProfileHeader(me, fallbackName, seed, onOpenProfile)

            groups.forEachIndexed { gi, group ->
                Spacer(Modifier.height(if (gi == 0) d.sectionGap else d.cardGap))
                IMSettingsGroup {
                    group.forEachIndexed { ri, row ->
                        if (ri > 0) IMRowDivider(insetStart = if (row.icon == null) d.space4 else d.settingsSeparatorInset)
                        IMSettingsRow(
                            title = row.title,
                            onClick = row.onClick,
                            icon = row.icon,
                            iconBackground = row.iconBg,
                            rightValue = row.rightValue,
                            destructive = row.destructive,
                        )
                    }
                }
            }
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

/**
 * 头部资料卡：大头像 + 显示名 + 「手机号 · @句柄」。整块可点 → 我的资料页。
 *
 * 三条口径与 iOS `refreshProfileHeader` 一致：
 *  ① 显示名回退链 **昵称 → @句柄 → 「未命名用户」**，**末级绝不是 uid**
 *    （10 位随机内部 ID，露在界面上对用户毫无意义）；
 *  ② 副标题只放公开句柄，手机号有才补在前面；
 *  ③ 两者都为空时整行不渲染，不显示「未设置」。
 */
@Composable
private fun ProfileHeader(me: UserCard?, fallbackName: String, seed: String, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val unnamedUser = stringResource(R.string.common_unnamed_user)
    val display = me?.displayName?.takeIf { it.isNotBlank() && it != unnamedUser }
        ?: fallbackName.ifBlank { unnamedUser }
    val handle = me?.handle.orEmpty()
    val phone = me?.phone.orEmpty()
    val meta = when {
        phone.isNotEmpty() && handle.isNotEmpty() -> "$phone · $handle"
        phone.isNotEmpty() -> phone
        else -> handle
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = d.sectionGap, bottom = d.space2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IMAvatar(
            displayName = display,
            seed = seed,
            avatarUrl = me?.avatarUrl.orEmpty(),
            size = d.profileAvatar,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = display,
            color = c.textPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = d.space4),
        )
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = meta,
                color = c.textSecondary,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = d.space4),
            )
        }
    }
}
