package com.libeyond.imandroid.rtc

import com.imrtc.uikit.IMInviteCandidate
import com.imrtc.uikit.IMInviteCandidatesCallback
import com.imrtc.uikit.IMInviteContext
import com.imrtc.uikit.IMInviteMemberProvider
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.api.GroupMembersPage
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 群通话里「添加成员」的候选人：**读 IM 自己的群成员接口**（[com.libeyond.imandroid.sdk.api.GroupApi.members]，
 * 服务端游标分页 + 服务端搜索，超级群同样适用），不另造名单、不另建网络层。
 *
 * 名字与通话界面同一条链（[RtcProfileSources.name]：备注 > 群昵称 > 昵称 > @句柄）；
 * 取回的成员顺手并入 [sources]，通话界面随后画这些人也是同一份。
 * **每次调用必回调且只回调一次**：没有群号 / 取失败都回调（空名单 / onError），Kit 才不会转圈到超时。
 */
class RtcInviteProvider(
    private val scope: CoroutineScope,
    private val sources: RtcProfileSources,
    private val myUid: () -> String,
    private val absolute: (String) -> String,
    private val fetch: suspend (groupId: String, cursor: String, query: String) -> GroupMembersPage,
) : IMInviteMemberProvider {

    private val log = IMLog.tag("IM.Rtc")

    override fun loadCandidates(
        ctx: IMInviteContext,
        query: String,
        cursor: String?,
        callback: IMInviteCandidatesCallback,
    ) {
        if (ctx.chatGroupId.isEmpty()) {
            // 不属于某个群的临时多人通话：宿主没有可列的名单。
            callback.onResult(emptyList(), null)
            return
        }
        scope.launch {
            val page = try {
                fetch(ctx.chatGroupId, cursor.orEmpty(), query.trim())
            } catch (e: Exception) {
                log.w("rtc_invite_candidates_failed", "err" to e.javaClass.simpleName)
                callback.onError("加载失败")
                return@launch
            }
            sources.putMembers(ctx.chatGroupId, page.items.map(::memberRow))
            val items = candidates(ctx, page.items, myUid(), sources) { absolute(it) }
            callback.onResult(items, page.nextCursor.takeIf { page.hasMore && it.isNotEmpty() })
        }
    }

    companion object {
        private fun memberRow(m: GroupMember) =
            RtcProfileSources.MemberRow(m.userId, m.groupNickname, m.nickname, m.username, m.avatarUrl)

        /** 一页成员 → 候选人（纯逻辑）：剔掉自己；已在通话 / 正在振铃的人置灰。 */
        fun candidates(
            ctx: IMInviteContext,
            members: List<GroupMember>,
            myUid: String,
            sources: RtcProfileSources,
            absolute: (String) -> String,
        ): List<IMInviteCandidate> {
            val inCall = ctx.participantUids.toSet()
            return members.filter { it.userId.isNotEmpty() && it.userId != myUid }.map { m ->
                val busy = m.userId in inCall
                IMInviteCandidate(
                    uid = m.userId,
                    name = sources.name(m.userId, ctx.chatGroupId) ?: m.displayName,
                    avatarUrl = m.avatarUrl.takeIf { it.isNotBlank() }?.let(absolute)?.takeIf { it.isNotBlank() },
                    subtitle = m.handle.takeIf { it.isNotEmpty() },
                    selectable = !busy,
                    unselectableReason = if (busy) "已在通话中" else null,
                )
            }
        }

        fun forClient(client: IMClient, sources: RtcProfileSources, absolute: (String) -> String) = RtcInviteProvider(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            sources = sources,
            myUid = { client.uid.orEmpty() },
            absolute = absolute,
            fetch = { group, cursor, q -> client.groups.members(group, cursor = cursor, q = q) },
        )
    }
}
