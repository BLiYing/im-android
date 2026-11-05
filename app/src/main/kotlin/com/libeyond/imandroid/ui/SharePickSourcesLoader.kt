package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.PickEntry
import com.libeyond.imandroid.data.SharePick
import com.libeyond.imandroid.data.SharePickSources
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.logging.IMLog

/**
 * 分享选择页「我的好友 / 我的群聊」的数据（[com.libeyond.imandroid.ui.screens.ForwardPickerScreen] 的 `sources`）。
 *
 * 先拿离线快照（[com.libeyond.imandroid.data.RosterCache]，同通讯录）当首屏，再拉网络覆盖：
 * 好友 `GET /friends?status=accepted`（拉黑 / 注销由 [SharePick.friendEntries] 剔），群 `GET /groups`。
 * 网络失败保留快照；**快照也没有就标失败**（子页显示「加载失败，点击重试」，不吐司），并留痕。
 * 重试 = 把对应那一路的 attempt 加一，`LaunchedEffect` 按它重跑。
 */
@Composable
internal fun rememberSharePickSources(client: IMClient): SharePickSources {
    val owner = client.uid.orEmpty()
    var friends by remember(owner) { mutableStateOf<List<PickEntry>?>(null) }
    var groups by remember(owner) { mutableStateOf<List<PickEntry>?>(null) }
    var friendsFailed by remember(owner) { mutableStateOf(false) }
    var groupsFailed by remember(owner) { mutableStateOf(false) }
    var friendsAttempt by remember(owner) { mutableIntStateOf(0) }
    var groupsAttempt by remember(owner) { mutableIntStateOf(0) }

    LaunchedEffect(owner, friendsAttempt) {
        friendsFailed = false
        if (friends == null) {
            runCatchingCancellable { client.roster.cachedFriends(owner) }
                .onSuccess { if (friends == null && it.isNotEmpty()) friends = SharePick.friendEntries(owner, it) }
        }
        runCatchingCancellable { client.contacts.friends(FriendEntry.ACCEPTED) }
            .onSuccess { friends = SharePick.friendEntries(owner, it) }
            .onFailure {
                LOG.w("share_pick_friends_failed", "err" to it.javaClass.simpleName, "attempt" to friendsAttempt)
                if (friends == null) friendsFailed = true
            }
    }
    LaunchedEffect(owner, groupsAttempt) {
        groupsFailed = false
        if (groups == null) {
            runCatchingCancellable { client.roster.cachedGroups(owner) }
                .onSuccess { if (groups == null && it.isNotEmpty()) groups = SharePick.groupEntries(owner, it) }
        }
        runCatchingCancellable { client.groups.myGroups() }
            .onSuccess { groups = SharePick.groupEntries(owner, it) }
            .onFailure {
                LOG.w("share_pick_groups_failed", "err" to it.javaClass.simpleName, "attempt" to groupsAttempt)
                if (groups == null) groupsFailed = true
            }
    }
    return remember(friends, groups, friendsFailed, groupsFailed) {
        SharePickSources(
            friends = friends,
            groups = groups,
            friendsFailed = friendsFailed,
            groupsFailed = groupsFailed,
            retryFriends = { friendsAttempt++ },
            retryGroups = { groupsAttempt++ },
        )
    }
}

private val LOG = IMLog.tag("IM.Share")
