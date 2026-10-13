package com.libeyond.imandroid.sdk.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `group` 下行帧（PROTOCOL §6.6）。**收到任意 group 帧即重拉群资料/会话列表**，`event` 只作语义。
 *
 * 其中 `join_request` 只推群主/管理员，`join_result` 只推申请人本人（`result` = approved|rejected），
 * 其余事件全体 fan-out。离线成员不补发。对齐 iOS `handleGroupEvent:`。
 */
@Serializable
data class GroupEventData(
    @SerialName("conv_id") val convId: String = "",
    val event: String = "",
    val from: String = "",
    val target: String = "",
    val result: String = "",
) {
    /** 自己被移出（remove 且 target=我）。 */
    fun removedMe(myUid: String?): Boolean = event == REMOVE && myUid != null && target == myUid

    /** 自己退群（`leave` 且 target=我）：服务端连退群者本人也推，用来让**其它设备**移除该群。 */
    fun leftMe(myUid: String?): Boolean = event == LEAVE && myUid != null && target == myUid

    /** 群被解散（管理端处置，对全体生效）。 */
    val dissolved: Boolean get() = event == DISSOLVE

    /** 本群对我已不可用：被移出、自己退群或解散——页面该退出、会话该消失。 */
    fun goneForMe(myUid: String?): Boolean = dissolved || removedMe(myUid) || leftMe(myUid)

    /**
     * 这帧会不会改变**我**的可见下界（入群位点 × 群设置）：会 → 该会话缓存的下界必须丢。
     * 不会的（改群资料 / 公告 / 角色 / 转让 / 单人禁言、以及别人被邀请·退群·移出）不动它，白丢只是多问一次服务端。
     * 反向写：只列「确定不影响」的，未知事件一律按「会影响」处理，宁可多丢。
     */
    fun changesMyHistoryFloor(myUid: String?): Boolean = when (event) {
        PROFILE, ANNOUNCEMENT, ROLE, TRANSFER, MEMBER_MUTE -> false
        INVITE, LEAVE, REMOVE -> myUid != null && target == myUid || target.isEmpty()
        else -> true
    }

    companion object {
        const val PROFILE = "profile"
        const val ANNOUNCEMENT = "announcement"
        const val ROLE = "role"
        const val TRANSFER = "transfer"
        const val MEMBER_MUTE = "member_mute"
        const val INVITE = "invite"
        const val LEAVE = "leave"
        const val REMOVE = "remove"
        const val DISSOLVE = "dissolve"
        const val JOIN_REQUEST = "join_request"
        const val JOIN_RESULT = "join_result"
        const val APPROVED = "approved"
    }
}
