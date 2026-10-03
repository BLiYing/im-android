package com.libeyond.imandroid.data

import androidx.annotation.StringRes
import com.libeyond.imandroid.R

/**
 * 输入栏锁（对齐 iOS `refreshComposerMuteState`）：被禁言 / 全员禁言 / 系统通知会话时，
 * 输入框、＋、🎙 一并禁用，占位文案改成原因——**提前告知、不给试错**。
 * 服务端仍是权威（发上来照样拒 300208/300206，那条走 [SendRejection] 的气泡下说明行）。
 *
 * 优先级：成员级禁言 > 全员禁言。**全员禁言只锁普通成员**（群主/管理员不受限）；
 * 成员级禁言**不分角色**——管理员被单独禁言同样发不出去（旧 `GroupPermissions.amMuted` 在这里判错）。
 */
object ComposerLock {
    @StringRes
    fun reasonRes(
        isGroup: Boolean,
        peerUid: String,
        myRole: String?,
        myMuteUntil: Long,
        muteUntil: Long,
        now: Long = System.currentTimeMillis(),
    ): Int? {
        if (!isGroup) return if (peerUid == DetailActions.SYSTEM_UID) R.string.chat_input_disabled_system else null
        if (GroupPermissions.isMuteActive(myMuteUntil, now)) return R.string.chat_input_disabled_muted
        val isMember = myRole == null || myRole == "member"
        // myRole 还没拉回来（null）时按普通成员判：宁可先锁——拉回后管理员自动解锁，反向则会让被禁言者白发一次
        return if (isMember && GroupPermissions.isMuteActive(muteUntil, now)) R.string.chat_input_disabled_mute_all else null
    }
}
