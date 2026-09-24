package com.libeyond.imandroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * 哈希用 `h = h * 31 + char`，与 iOS 逐字对齐——**换个哈希就换个颜色**，
 * 三端会各显各的。种子一律用 uid（稳定），不要用显示名（改昵称就变色）。
 */
fun avatarColorForSeed(seed: String): Color {
    if (seed.isEmpty()) return AVATAR_PALETTE[0]
    var h = 0L
    for (ch in seed) h = h * 31 + ch.code
    // Kotlin 的 % 对负数返回负值，先取绝对值再取模（iOS 用的是 NSUInteger，天然非负）
    val idx = ((h % AVATAR_PALETTE.size) + AVATAR_PALETTE.size) % AVATAR_PALETTE.size
    return AVATAR_PALETTE[idx.toInt()]
}

/**
 * 头像。有 `avatarUrl` 时显示图片（P7 接图片加载），否则回退**首字母圈**。
 *
 * 首字母取显示名**末两位**——三端同口径（`docs/UI.md`）。
 *
 * 系统通知会话（`seed == DetailActions.SYSTEM_UID`）：一律显示应用 logo，不发网络请求、
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
        Image(
            painter = painterResource(R.drawable.im_system_logo),
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
