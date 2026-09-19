package com.libeyond.imandroid.rtc

import com.libeyond.imandroid.data.DisplayName
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 通话界面显示名 / 头像的**数据源：IM 自己已有的数据**（纯逻辑，可单测）。
 *
 * 原则：通话界面与 IM 界面显示同一份，IM 换了、通话跟着换（不固定、不自己刷新）。
 * - 单聊：读会话行（标题 = 备注 > 昵称，头像），与会话列表 / 信息页头部同一份；
 * - 群通话：读**当前这个群**的成员表，顺序与 IM 群聊一致：备注 > 群昵称 > 昵称 > @句柄 > 会话行昵称；
 * - 都没有名字才返回 null，由调用方走兜底取数。
 */
class RtcProfileSources {

    /** 单聊会话行里对端的资料。`title` 已是 IM 的显示名（备注 > 昵称）。 */
    data class PeerRow(val uid: String, val title: String, val avatarUrl: String, val remark: String)

    /** 群成员表里的一个人。 */
    data class MemberRow(
        val uid: String,
        val groupNickname: String,
        val nickname: String,
        val username: String,
        val avatarUrl: String,
    )

    private val peers = AtomicReference<Map<String, PeerRow>>(emptyMap())
    private val members = ConcurrentHashMap<String, ConcurrentHashMap<String, MemberRow>>()

    /** 整体替换单聊会话快照（会话表变化时由数据流喂进来）。 */
    fun setPeers(rows: List<PeerRow>) {
        peers.set(rows.filter { it.uid.isNotEmpty() }.associateBy { it.uid })
    }

    /** 并入某个群的成员（群资料页加载成员时调用；分页加载会多次调用，按 uid 覆盖）。 */
    fun putMembers(groupId: String, rows: List<MemberRow>) {
        if (groupId.isEmpty()) return
        val table = members.getOrPut(groupId) { ConcurrentHashMap() }
        rows.forEach { if (it.uid.isNotEmpty()) table[it.uid] = it }
    }

    /** 该显示的名字；IM 里一个名字都没有返回 null。`groupId` 非空 = 当前是群通话。 */
    fun name(uid: String, groupId: String): String? {
        val peer = peers.get()[uid]
        val title = peer?.title.usable()
        if (groupId.isEmpty()) return title
        val member = members[groupId]?.get(uid)
        val memberName = member?.let {
            it.groupNickname.usable() ?: it.nickname.usable() ?: it.username.usable()?.let { h -> "@$h" }
        }
        return peer?.remark.usable() ?: memberName ?: title
    }

    /** 头像地址（IM 给的原样，可能是相对路径）；没有返回 null。 */
    fun avatarUrl(uid: String, groupId: String): String? {
        val peer = peers.get()[uid]?.avatarUrl.usable()
        if (groupId.isEmpty()) return peer
        return members[groupId]?.get(uid)?.avatarUrl.usable() ?: peer
    }

    private fun String?.usable(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != DisplayName.UNNAMED }
}
