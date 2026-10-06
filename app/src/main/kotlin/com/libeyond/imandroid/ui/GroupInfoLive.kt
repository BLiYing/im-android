package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupInfo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * 群资料页的取数：进页拉一次，之后本群每来一帧 `group`（别的管理员/成员改了群，PROTOCOL §6.6）就重拉。
 * 从 `GroupInfoHost` 拆出（那份文件贴着 600 行硬闸）。
 *
 * - 被移出/解散由 `GroupEventsEffect` 关页，**不在此重拉**（拉了只会 300203）。
 * - **订阅先于首次加载**（SharedFlow 不回放，首载期间的帧否则丢）；`conflate`：重拉期间连来的帧并成一次。
 */
@Composable
fun GroupInfoLiveLoad(
    client: IMClient,
    convId: String,
    onInfo: (GroupInfo) -> Unit,
    refreshMembers: suspend () -> Unit,
    loadSettings: suspend () -> Unit,
    /** 改一下就重新取一次（占位页的「重试」）。 */
    reloadKey: Int = 0,
    onLoadFailed: () -> Unit = {},
) {
    LaunchedEffect(convId, reloadKey) {
        suspend fun load() {
            runCatching { onInfo(client.groups.info(convId)) }
                .onFailure { IMLog.tag("IM.Group").w("group_info_failed"); onLoadFailed() }
            refreshMembers()
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            client.groupEvents.filter { it.convId == convId && !it.goneForMe(client.uid) }.conflate().collect { load() }
        }
        load()
        loadSettings()
    }
}
