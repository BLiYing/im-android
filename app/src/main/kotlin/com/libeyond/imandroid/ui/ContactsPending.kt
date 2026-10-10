package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.logging.IMLog

/**
 * 通讯录 Tab 角标的计数（待我确认的好友申请数）。
 *
 * 进主界面拉一次，之后每收到 friend 帧再拉一次（PROTOCOL §6.5：收到任意 friend 帧即重拉）。
 * 失败只留痕、保留旧值——角标不该因为一次弱网抖成 0。调用方可直接写入覆盖（ContactsHost 的即时值）。
 */
@Composable
internal fun rememberContactsPending(client: IMClient, owner: String): MutableState<Int> {
    val state = remember(owner) { mutableStateOf(0) }
    suspend fun refresh() {
        runCatchingCancellable { client.contacts.friends(FriendEntry.PENDING) }
            .onSuccess { state.value = it.size }
            .onFailure { IMLog.tag("IM.Contacts").w("pending_badge_failed", "err" to it.javaClass.simpleName) }
    }
    LaunchedEffect(owner) {
        if (owner.isNotEmpty()) refresh()
    }
    LaunchedEffect(owner) {
        if (owner.isNotEmpty()) client.friendEvents.collect { refresh() }
    }
    return state
}
