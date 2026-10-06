package com.libeyond.imandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**

 * 没有它就是整页空白再「啪」地出内容。**不画任何依赖角色 / 开关 / 人数的入口**，见 `GroupInfoHost.seed`。
 */
@Composable
internal fun GroupInfoPlaceholder(seed: GroupInfo?, failed: Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    val c = IMTheme.colors
    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = stringResource(R.string.group_info_title), onLeft = onBack)
        if (seed != null) {
            Column(
                Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IMAvatar(seed.name, seed = seed.convId, avatarUrl = seed.avatarUrl, size = 72.dp)
                Text(seed.name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            }
        }
        // 拉不到（断网 / 登录失效）又没有快照：别永远停在占位上，给个能点的重试
        if (failed) {
            Text(
                stringResource(R.string.call_history_load_failed),
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onRetry).padding(top = 32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
