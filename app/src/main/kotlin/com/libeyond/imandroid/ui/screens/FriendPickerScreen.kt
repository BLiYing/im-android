package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.topBarChrome
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 选一个好友（发个人名片用）。**单选、点了即回**——不做确认按钮，名片这个场景多一步没意义。
 *
 * 刻意**不复用** `CreateGroupScreen`：那一页绑着群名输入框、人数上限与多选语义，
 * 硬塞一个「单选模式」开关会让它变成两个页面挤在一个函数里。
 *
 * 与建群选人页一样**没有搜索框**：本端会话/联系人列表搜索尚未收敛
 * （iOS `IMListSearch` / Web `listSearch.ts` 那一套），单独在这里塞一个会变成第三份实现。
 */
@Composable
internal fun FriendPickerScreen(
    friends: List<FriendEntry>,
    onPick: (FriendEntry) -> Unit,
    onCancel: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(
        Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier.topBarChrome(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.common_cancel), color = c.accent,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(d.topBarSide).clickable(onClick = onCancel),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.contact_card_picker_title),
                    color = c.textPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Box(Modifier.width(d.topBarSide))
        }
        if (friends.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.contact_card_picker_empty), color = c.textSecondary)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().background(c.cardBackground)) {
            items(friends, key = { it.userId }) { f ->
                // 列表里显示**备注优先**（本机私有）；写进名片的是公开名，由发送方另取，见 ChatHost
                val shown = DisplayName.ofFriend(f)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onPick(f) }
                            .padding(horizontal = d.space4, vertical = d.space3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IMAvatar(
                            displayName = shown,
                            seed = f.userId,
                            avatarUrl = f.avatarUrl,
                            size = 40.dp,
                        )
                        Spacer(Modifier.width(d.space3))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                shown, color = c.textPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().height(0.5.dp)
                            .padding(start = d.space4 + 40.dp + d.space3)
                            .background(c.separator),
                    )
                }
            }
        }
    }
}
