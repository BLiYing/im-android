package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.GroupInfo

/**
 * 群治理开关组（G2，PROTOCOL §11）。
 *
 * **`PUT /groups/{id}/settings` 是整体替换**：五个字段一次全传，少传一个就是把它设成 `false`。
 * 所以「改一个开关」在协议上其实是「把当前五个值原样回传、只翻转其中一个」——
 * 这一步必须有唯一实现，散着写迟早出现「用户点了 A，B 被顺手关掉了」，
 * 而且现场看不出来（界面上 B 只是变灰了，没人会怀疑是刚才那一下点的）。
 *
 * ⚠️ 五个开关**名字都是「仅管理员可…」/「需要确认」，`true` = 收紧**，不是「允许」。
 * 读反了的后果见 [GroupPermissions.canEditInfo] 的注释。
 */
object GroupSettings {

    enum class Key { JoinApproval, PermInvite, PermEditInfo, PermPin, HistoryVisible }

    /** 一次 PUT 的完整载荷。 */
    data class Values(
        val joinApproval: Boolean,
        val permInvite: Boolean,
        val permEditInfo: Boolean,
        val permPin: Boolean,
        val historyVisible: Boolean,
    )

    /** 服务端当前值。**必须从 [GroupInfo] 取全部五个**，别用默认值兜底。 */
    fun of(info: GroupInfo): Values = Values(
        joinApproval = info.joinApproval,
        permInvite = info.permInvite,
        permEditInfo = info.permEditInfo,
        permPin = info.permPin,
        historyVisible = info.historyVisible,
    )

    fun isOn(info: GroupInfo, key: Key): Boolean = read(of(info), key)

    /** 翻转一个，其余四个**原样带回**。 */
    fun toggled(info: GroupInfo, key: Key): Values {
        val v = of(info)
        val next = !read(v, key)
        return when (key) {
            Key.JoinApproval -> v.copy(joinApproval = next)
            Key.PermInvite -> v.copy(permInvite = next)
            Key.PermEditInfo -> v.copy(permEditInfo = next)
            Key.PermPin -> v.copy(permPin = next)
            Key.HistoryVisible -> v.copy(historyVisible = next)
        }
    }

    /** 界面文案。**与 im-web `GroupManagePanel.tsx` 逐字一致**——同一个开关两端叫法不同，
     *  用户在两个端上看到的就是两套规则。 */
    fun label(key: Key): String = when (key) {
        Key.JoinApproval -> "进群确认"
        Key.PermInvite -> "仅管理员可邀请"
        Key.PermEditInfo -> "仅管理员可改群资料"
        Key.PermPin -> "仅管理员可置顶消息"
        Key.HistoryVisible -> "新成员仅可见入群后历史"
    }

    /** 「加入与发言」那一组；其余归「成员权限」。分组同 im-web。 */
    val JOIN_GROUP: List<Key> = listOf(Key.JoinApproval)
    val PERM_GROUP: List<Key> = listOf(Key.PermInvite, Key.PermEditInfo, Key.PermPin, Key.HistoryVisible)

    private fun read(v: Values, key: Key): Boolean = when (key) {
        Key.JoinApproval -> v.joinApproval
        Key.PermInvite -> v.permInvite
        Key.PermEditInfo -> v.permEditInfo
        Key.PermPin -> v.permPin
        Key.HistoryVisible -> v.historyVisible
    }
}
