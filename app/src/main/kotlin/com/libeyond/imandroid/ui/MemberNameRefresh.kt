package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.SenderNames
import com.libeyond.imandroid.data.db.MessageEntity

/**
 * 群成员表过期检测（2026-09-15）：返回一个版本号，挂到拉群资料那个 `LaunchedEffect` 的 key 上，+1 即重拉一次。
 *
 * 气泡发送者名是**成员表优先**（[SenderNames]），成员表是进会话时拉的。会话开着期间有人改了名再发消息，
 * 旧成员表会把这条新消息也压回旧名——所以末条换了、且它带的昵称与成员表对不上时重拉（5s 节流）。
 * 进会话读到的第一窗只记基线：那时的末条是本地老快照，拿它比只会白拉一次（进会话时已经拉过）。
 *
 * 对端：iOS `IMChatViewController+Group.m` 的 `refreshGroupInfoIfSenderRenamed:`、im-web `src/useGroupInfoRefresh.ts`。
 */
@Composable
internal fun rememberMembersRefreshRev(
    convId: String,
    isGroup: Boolean,
    rowsReady: Boolean,
    tail: MessageEntity?,
    memberNames: Map<String, String>,
): Int {
    var rev by remember(convId) { mutableStateOf(0) }
    var lastTailSeq by remember(convId) { mutableStateOf<Long?>(null) }
    var refreshedAt by remember(convId) { mutableStateOf(0L) }
    LaunchedEffect(convId, rowsReady, tail?.convSeq) {
        if (!rowsReady || !isGroup) return@LaunchedEffect
        val prev = lastTailSeq
        lastTailSeq = tail?.convSeq ?: 0L
        if (prev == null || tail == null || prev == tail.convSeq) return@LaunchedEffect
        if (!SenderNames.memberNameStale(memberNames[tail.sender], tail.fromNickname)) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - refreshedAt < 5_000) return@LaunchedEffect
        refreshedAt = now
        rev++
    }
    return rev
}
