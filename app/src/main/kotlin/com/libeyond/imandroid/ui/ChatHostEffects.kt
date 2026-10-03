package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.IMClient

// 从 ChatHost 拆出（那边贴 600 行硬闸）：两块与聊天页其余状态互不相干的小副作用。

/**
 * 发送者头像：成员表 > 全局解析器（`POST /users/batch`，合批/负缓存/退避都在缓存里）。
 * 读 `revision` = 解析完重组；没解析出来先回空串（首字母色块），同时排队去补。
 */
@Composable
internal fun rememberSenderAvatarOf(client: IMClient, memberAvatars: Map<String, String>): (String) -> String {
    val profilesRev by client.profiles.revision.collectAsState()
    return remember(profilesRev, memberAvatars) {
        { uid ->
            memberAvatars[uid]?.takeIf { u -> u.isNotBlank() } ?: client.profiles.peek(uid)?.avatarUrl
                ?: run { client.profiles.request(uid); "" }
        }
    }
}

/**
 * 老消息补种缩略：原图已在本地（门控判定 Ready）时自己算一张存起来，
 * **下次进这个会话就有磨砂占位了**。挂在「消息列表 + 下载状态」上——刚下完的那一张正好在这一轮被补上。
 */
@Composable
internal fun ThumbBackfillEffect(client: IMClient, owner: String, convId: String, messages: List<MessageEntity>) {
    val downloadStates by client.downloads.states.collectAsState()
    LaunchedEffect(messages.size, downloadStates.size, owner) {
        if (owner.isNotEmpty()) {
            runCatchingCancellable { client.thumbBackfill.run(owner, convId, messages) }
        }
    }
}
