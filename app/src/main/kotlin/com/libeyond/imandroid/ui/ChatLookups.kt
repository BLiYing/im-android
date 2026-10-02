package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.ChatWindow
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.data.distinctSenders
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.screens.SearchSenderCandidate

/**
 * 聊天页「定位 / 会话内搜索 / 日历 / 来自候选」这一组的接线，从 `ChatHost` 平移出来（2026-10-02，那个文件贴着 600 行硬闸，
 * C4b 要在里面加尾窗同步）。**行为未改**。
 *
 * 顺序有讲究：locator 先建（search 与 calendar 要拿它当跳转出口）。
 */
internal class ChatLookups(
    val locator: ChatLocator,
    val search: ChatSearchController,
    val calendar: ChatCalendarController,
    /** 「来自」候选：**本机已下载消息里实际发过言的人**（产品既定口径，设计 §4.9 第 2 项 2026-10-02 订正）。 */
    val fromCandidates: List<SearchSenderCandidate>,
)

@Composable
internal fun rememberChatLookups(
    client: IMClient,
    convId: String,
    owner: String,
    online: Boolean,
    onOpenWindow: (ChatWindow.Anchored) -> Unit,
    onToast: (String) -> Unit,
    friendsByUid: Map<String, FriendEntry>,
    memberNames: Map<String, String>,
    memberAvatars: Map<String, String>,
): ChatLookups {
    val locator = rememberChatLocator(client = client, convId = convId, onOpenWindow = onOpenWindow, onToast = onToast)
    val search = rememberChatSearch(
        client = client,
        convId = convId,
        online = online,
        // 拒绝原因由搜索那侧接管（写进搜索条上方那一行）——搜索态下键盘占着下半屏，
        // 吐司恰好落在键盘背后，等于没提示。
        onLocate = { seq, refuse -> locator.locate(seq, refuse) },
    )
    // 📅 日历跳转：独立状态机（不影响搜索命中集），复用同一个 locator 出口
    val calendar = rememberChatCalendar(
        client = client,
        convId = convId,
        online = online,
        onLocate = { seq, refuse -> locator.locate(seq, refuse) },
        onLocateEarliest = { refuse -> locator.locateEarliest(refuse) },
        onToast = onToast,
    )
    // 👤「来自」候选：面板一开才查 uid 去重集（不是每次进搜索态都查一遍库）；
    // 名字/头像**不进这个 effect**——单独 remember 派生，friendsByUid/memberNames 稍后才拉到时
    // （群资料是异步的）面板还开着的话也能跟着刷新，不必再开一次面板重新查一遍库。
    var fromUids by remember(convId) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(search.fromPickerOpen, convId, owner) {
        if (!search.fromPickerOpen || owner.isEmpty()) return@LaunchedEffect
        fromUids = client.repo.distinctSenders(owner, convId)
    }
    val candidates = remember(fromUids, friendsByUid, memberNames, memberAvatars) {
        fromUids.map { uid ->
            val name = friendsByUid[uid]?.let { DisplayName.ofFriend(it) }
                ?: memberNames[uid]
                ?: uid
            SearchSenderCandidate(uid, name, friendsByUid[uid]?.avatarUrl ?: memberAvatars[uid].orEmpty())
        }
    }
    return ChatLookups(locator, search, calendar, candidates)
}
