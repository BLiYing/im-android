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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onOpenPrivacy: () -> Unit,
    onComingSoon: (String) -> Unit,
    onLogout: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val ic = IMTheme.settingsIcons

    val groups = listOf(
        listOf(
            MeRow("收藏消息", Lucide.Bookmark, ic.blue) { onComingSoon("收藏消息") },
            MeRow("最近通话", Lucide.Phone, ic.green) { onComingSoon("最近通话") },
            MeRow("已登录设备", Lucide.Laptop, ic.orange, onClick = onOpenDevices),
            MeRow("聊天文件夹", Lucide.Folder, ic.blue) { onComingSoon("聊天文件夹") },
            // 与左上角「我的二维码」并列：二维码给**面对面**，名片消息给**线上**。
            MeRow("分享我的名片", Lucide.IdCard, ic.teal) { onComingSoon("分享我的名片") },
        ),
        listOf(
            MeRow("通知与提示音", Lucide.Bell, ic.red) { onComingSoon("通知与提示音") },
            MeRow("隐私与安全", Lucide.Lock, ic.gray, onClick = onOpenPrivacy),
            MeRow("数据和存储", Lucide.HardDrive, ic.green) { onComingSoon("数据和存储") },
            MeRow("外观", Lucide.Contrast, ic.blue) { onComingSoon("外观") },
            MeRow("省电模式", Lucide.Zap, ic.yellow, rightValue = "关闭") { onComingSoon("省电模式") },
            MeRow("语言", Lucide.Globe, ic.purple, rightValue = "简体中文") { onComingSoon("语言") },
        ),
        listOf(
            MeRow("退出登录", null, Color.Unspecified, destructive = true, onClick = onLogout),
        ),
    )

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(
            title = "我",
            leftIcon = Lucide.QrCode,
            leftDescription = "我的二维码",
            onLeft = onOpenQr,
            actionText = "编辑",
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
    val display = me?.displayName?.takeIf { it.isNotBlank() && it != "未命名用户" }
        ?: fallbackName.ifBlank { "未命名用户" }
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
