package com.libeyond.imandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupInfo

/**
 * 聊天页持有的「本群资料」一份快照（从 `ChatHost` 拆出，那份文件贴着 600 行硬闸）：
 * 角色 / 禁言位（输入栏锁）/ 成员表（名字、角色、头像、@ 兜底）/ 整份 [info]（公告、待审数、置顶权限——横幅用）。
 * 单聊恒为初值。字段全是 Compose 状态，读它即订阅。
 */
class ChatGroupState {
    /** 群里我是不是管理员——只影响菜单显不显，服务端仍会独立校验（越权回 300006），拉不到就按 false 走。 */
    var iAmManager by mutableStateOf(false)
    /** 我在本群的角色。**@所有人 只对群主/管理员出入口**（越权服务端回 300204）。 */
    var myRole by mutableStateOf<String?>(null)
    /** 输入栏锁的两个禁言位（成员级 / 全员），见 `ComposerLock`。 */
    var myMuteUntil by mutableStateOf(0L)
    var groupMuteUntil by mutableStateOf(0L)
    /** 本群成员表（显示名→uid）——只给没有 mention_spans 的老消息兜底，有 uid 才可点（对齐 iOS）。 */
    var mentionNames by mutableStateOf<Map<String, String>>(emptyMap())
    /** 成员角色 / 显示名 / 头像（uid 为键）：发送者徽标、名字、引用块与回复条用。超级群只有我自己。 */
    var memberRoles by mutableStateOf<Map<String, String>>(emptyMap())
    var memberNames by mutableStateOf<Map<String, String>>(emptyMap())
    var memberAvatars by mutableStateOf<Map<String, String>>(emptyMap())
    /** 整份群资料（公告 / 待审数 / 置顶权限…）；null = 还没拉回或单聊。 */
    var info by mutableStateOf<GroupInfo?>(null)
}

/**
 * 进会话拉一次群资料，之后 [membersRev]（成员表过期）或本群的 `group` 帧（禁言/改设置/待审…，PROTOCOL §6.6）
 * 到来就重拉——输入栏锁、公告横幅、待审红条随之上下，不必等下一条消息。
 */
@Composable
fun ChatGroupLoad(client: IMClient, conv: ConversationEntity, gs: ChatGroupState, membersRev: Int) {
    var groupEventRev by remember(conv.convId) { mutableStateOf(0) }
    LaunchedEffect(conv.convId) {
        client.groupEvents.collect { if (it.convId == conv.convId && !it.goneForMe(client.uid)) groupEventRev++ }
    }
    LaunchedEffect(conv.convId, membersRev, groupEventRev) {
        if (!conv.isGroup) return@LaunchedEffect
        runCatchingCancellable { client.groups.info(conv.convId) }.onSuccess {
            gs.info = it
            // 全局昵称/头像喂进解析器（**不喂群昵称**：A 群的昵称不能漏到 B 群）
            client.profiles.ingest(it.members.map { m -> com.libeyond.imandroid.sdk.api.UserCard(userId = m.userId, username = m.username, nickname = m.nickname, avatarUrl = m.avatarUrl) })
            gs.iAmManager = it.iAmManager
            gs.myRole = it.myRole
            gs.myMuteUntil = it.myMuteUntil
            gs.groupMuteUntil = it.muteUntil
            // 超级群这里只回我自己（服务端刻意不下发 2 万人的成员表），
            // 于是老消息的 @ 在超级群里不高亮——协议里写明的降级，别在这补救
            gs.mentionNames = it.members.associate { m -> m.displayName to m.userId }
            gs.memberRoles = it.members.associate { m -> m.userId to m.role }
            gs.memberNames = it.members.associate { m -> m.userId to m.displayName }
            gs.memberAvatars = it.members.associate { m -> m.userId to m.avatarUrl }
        }
    }
}
