package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 联系人行（高 60：上下 10 + 头像 40，分隔线从 68 起）。
 * 通讯录首页「最近联系」与搜索结果、「我的好友」页共用这一份几何。
 *
 * @param subtitle 名字下的标识行；为空时**整行隐藏**——不显示「用户名：未设置」，更不回退内部 ID
 * @param trailing 行尾的小字状态（如「已拉黑」），空 = 不画
 */
@Composable
internal fun FriendRow(
    name: String,
    seed: String,
    avatarUrl: String,
    onClick: () -> Unit,
    subtitle: String = "",
    trailing: String = "",
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier.fillMaxWidth().background(c.pageBackground).clickable { onClick() }
            .padding(horizontal = d.space4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IMAvatar(displayName = name, seed = seed, avatarUrl = avatarUrl, size = 40.dp)
        Spacer(Modifier.width(d.space3))
        Column(Modifier.weight(1f)) {
            Text(
                name, color = c.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (trailing.isNotEmpty()) Text(trailing, color = c.textTertiary, fontSize = 11.sp)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).padding(start = 68.dp).background(c.separator))
}

/** 好友行：副标题 = 用户名标识，已拉黑的行尾带「已拉黑」。 */
@Composable
internal fun FriendRow(f: FriendEntry, onClick: () -> Unit) {
    FriendRow(
        name = f.displayName, seed = f.userId, avatarUrl = f.avatarUrl, onClick = onClick,
        subtitle = f.handle,
        trailing = if (f.blocked) stringResource(R.string.common_blocked) else "",
    )
}

/** 通讯录各页的分段标题（13 textTertiary，左 16 上 16 下 6）。 */
@Composable
internal fun ContactSectionLabel(text: String) {
    Text(
        text = text,
        color = IMTheme.colors.textTertiary,
        style = MaterialTheme.typography.bodyMedium,
        // 分组标题左边缘与卡片左边缘对齐（UI_COLOR §4）
        modifier = Modifier.padding(start = IMTheme.dimens.space4, top = 16.dp, bottom = 6.dp),
    )
}
