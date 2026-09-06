package com.libeyond.imandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.UserCard
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMSecondaryButton
import com.libeyond.imandroid.ui.theme.IMTheme

/** 「我」页。当前只有资料卡 + 退出登录；设置逐项待接（CLIENT_PARITY「设置」行）。 */
@Composable
fun MeHost(client: IMClient, onLogout: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var me by remember { mutableStateOf<UserCard?>(null) }

    LaunchedEffect(Unit) { runCatching { me = client.contacts.me() } }

    Column(
        modifier = Modifier.fillMaxSize().background(c.groupedBackground)
            .statusBarsPadding().padding(d.space4),
        verticalArrangement = Arrangement.spacedBy(d.cardGap),
    ) {
        Text("我", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)

        Row(
            modifier = Modifier.fillMaxWidth()
                .background(c.cardBackground, androidx.compose.foundation.shape.RoundedCornerShape(d.radiusCard))
                .padding(d.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val card = me
            IMAvatar(
                displayName = card?.nickname ?: client.username.orEmpty(),
                seed = client.uid.orEmpty(),
                avatarUrl = card?.avatarUrl.orEmpty(),
                size = 56.dp,
            )
            Spacer(Modifier.width(d.space3))
            Column {
                Text(
                    text = card?.nickname?.ifBlank { null } ?: client.username.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary,
                )
                // 标识行为空时整行隐藏——不显示「用户名：未设置」，更不回退内部 ID
                val handle = card?.handle.orEmpty().ifBlank {
                    client.username?.let { "@$it" }.orEmpty()
                }
                if (handle.isNotEmpty()) {
                    Text(handle, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        IMSecondaryButton(text = "退出登录", onClick = onLogout)
    }
}
