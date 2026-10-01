package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.DetailActions
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.ui.AppIconChoice
import com.libeyond.imandroid.ui.AppIconSwitcher
import com.libeyond.imandroid.ui.theme.LocalMediaHost

/**
 * 头像取色板——**与 iOS `IMTheme.avatarColorForSeed:` 逐值对齐**。
 * 同一个用户在三端必须是同一个颜色，否则「换个端看头像变色」会让人以为不是同一个人。
 */
private val AVATAR_PALETTE = listOf(
    Color(red = 0.20f, green = 0.60f, blue = 0.96f, alpha = 1f), // 蓝
    Color(red = 0.31f, green = 0.78f, blue = 0.47f, alpha = 1f), // 绿
    Color(red = 0.96f, green = 0.62f, blue = 0.20f, alpha = 1f), // 橙
    Color(red = 0.90f, green = 0.36f, blue = 0.42f, alpha = 1f), // 红
    Color(red = 0.58f, green = 0.45f, blue = 0.90f, alpha = 1f), // 紫
    Color(red = 0.18f, green = 0.72f, blue = 0.74f, alpha = 1f), // 青
)

/**
 * 按种子稳定取色。
 *
 * 哈希用 `h = h * 31 + char`，与 iOS `avatarColorForSeed:`／Web `avatarColor` 逐字对齐——**换个哈希就换个
 * 颜色**，三端会各显各的。种子一律用 uid（稳定），不要用显示名（改昵称就变色）。
 *
 * **必须用无符号 64 位取模**（2026-10-02 修）：iOS 用 `NSUInteger`（64 位机天然无符号）、Web 用 `BigInt`
 * 显式 `& ((1n<<64n)-1n)` 掩码，取模时都把累加结果当无符号数算。之前这里用 `Long`（有符号）累加后直接
 * `% size` 再"负数转正"，乘加本身的 64 位回绕是对的，但**有符号取模 ≠ 无符号取模**——`2^64` 不是
 * `AVATAR_PALETTE.size`（6）的倍数，"负数时加 size 转正"这个补救法只在能整除时才等价于无符号取模，
 * 对 6 不成立，于是出现同一个 uid/conv_id 三端算出不同颜色（真机实测：同一个群在 Android 上背景色
 * 和 iOS/Web 不一致）。改用 `ULong` 做乘加与取模，全程无符号，和 iOS/Web 位对位一致。
 */
fun avatarColorForSeed(seed: String): Color {
    if (seed.isEmpty()) return AVATAR_PALETTE[0]
    var h = 0UL
    for (ch in seed) h = h * 31u + ch.code.toUInt()
    val idx = (h % AVATAR_PALETTE.size.toUInt()).toInt()
    return AVATAR_PALETTE[idx]
}

/**
 * 头像。有 `avatarUrl` 时显示图片（P7 接图片加载），否则回退**首字母圈**。
 *
 * 首字母规则见 [DisplayName.initials]——三端同口径（`../IMServer/docs/UI.md`）。
 *
 * 系统通知会话（`seed == DetailActions.SYSTEM_UID`）：一律显示应用 logo（跟随当前桌面图标），不发网络请求、
 * 不落首字母/取色兜底——服务端 `avatar_url` 恒空（`SystemUserAvatarURL=""`），与 iOS
 * `UILabel+IMAvatar.m`（`LaunchLogo`）/ Web `Avatar.tsx`（`/im-logo.png`）同一契约。
 */
@Composable
fun IMAvatar(
    displayName: String,
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    avatarUrl: String = "",
) {
    if (DetailActions.isSystemPeer(seed)) {
        // 跟随外观 ▸ 应用图标的当前选择（同 iOS 读 alternateIconName）；选完即换，不等退后台
        val icon by AppIconSwitcher.selected(LocalContext.current).collectAsState()
        Image(
            painter = painterResource((icon ?: AppIconChoice.DEFAULT).systemLogo),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
        return
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColorForSeed(seed)),
        contentAlignment = Alignment.Center,
    ) {
        // 首字母圈永远在底下：图片没加载出来/加载失败时它就是兜底，
        // 不需要额外的失败回调（Coil 的 error 回退还得再写一份同样的东西）
        Text(
            text = DisplayName.initials(displayName),
            color = Color.White,
            fontSize = (size.value * 0.34f).sp,
            fontWeight = FontWeight.Medium,
        )
        val mediaHost = LocalMediaHost.current
        val resolved = MediaUrl.absolute(avatarUrl, mediaHost.host, mediaHost.useTls)
        if (resolved.isNotBlank()) {
            AsyncImage(
                model = resolved,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}
