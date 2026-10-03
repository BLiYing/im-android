package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.protocol.GroupEventData
import com.libeyond.imandroid.ui.components.IMToast

/**
 * 全局的 `group` 帧反应（对齐 iOS `onGroupEvent:` / `onGroupEventForJoinResult:`），宿主 `MainScreen` 挂一次：
 *
 * - **被移出 / 群解散**（会话行的本机移除已在 `MessageService` 做掉）：当前正打开的正是这个群 → 提示并 [onGone] 关页；
 * - **入群审批结果**（只推申请人，此刻他多半不在那个群里）：弹通过/未通过。
 *
 * 群资料页自己的「别人改了群就重拉」在 `GroupInfoHost`，不在这里。
 */
@Composable
fun GroupEventsEffect(client: IMClient, openConvId: String?, onGone: () -> Unit) {
    var toast by remember { mutableStateOf<String?>(null) }
    val open by rememberUpdatedState(openConvId)
    val gone by rememberUpdatedState(onGone)
    LaunchedEffect(client) {
        client.groupEvents.collect { e ->
            when {
                e.event == GroupEventData.JOIN_RESULT -> toast = Str.s(
                    if (e.result == GroupEventData.APPROVED) R.string.conv_qr_join_approved else R.string.conv_qr_join_rejected,
                )
                e.goneForMe(client.uid) && e.convId == open -> {
                    toast = Str.s(if (e.dissolved) R.string.group_event_dissolved else R.string.group_event_removed)
                    gone()
                }
            }
        }
    }
    toast?.let { IMToast(it) { toast = null } }
}
