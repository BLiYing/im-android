package com.libeyond.imandroid.ui

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.data.GroupPick
import com.libeyond.imandroid.data.GroupSettings
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.sdk.http.ApiException

// 从 GroupInfoHost 拆出（那边贴 600 行硬闸）：治理页里三个「调接口 → 换成一句提示」的动作。
// 只做网络调用与文案，页面状态（deciding / 重拉列表）仍归宿主。

/** 审批一条入群申请，返回要弹的提示。 */
internal suspend fun reviewJoinRequestText(client: IMClient, convId: String, uid: String, approve: Boolean): String {
    val r = runCatching { client.groups.reviewJoinRequest(convId, uid, approve) }
    r.exceptionOrNull()?.let { e ->
        val code = (e as? ApiException)?.code
        return if (code != null) Str.s(R.string.common_action_failed_code, code) else Str.s(R.string.common_action_failed)
    }
    return if (approve) Str.s(R.string.qr_join_req_approved_toast) else Str.s(R.string.qr_join_req_rejected)
}

/** 解除禁言。 */
internal suspend fun unbanText(client: IMClient, convId: String, uid: String): String =
    if (runCatching { client.groups.unban(convId, uid) }.isSuccess) Str.s(R.string.group_ops_unban_done)
    else Str.s(R.string.net_fallback_unmute_failed)

/** 撤销管理员。 */
internal suspend fun revokeAdminText(client: IMClient, convId: String, uid: String): String =
    if (runCatching { client.groups.setRole(convId, uid, GroupMember.ROLE_MEMBER) }.isSuccess) Str.s(R.string.group_ops_revoke_admin_done)
    else Str.s(R.string.group_ops_revoke_admin_failed)

/**
 * 批量设管理员：后端没有批量接口，**串行**逐个 PUT（并发会让系统消息乱序），逐条汇总成败。
 * 返回 (成功数, 要弹的提示)——成功数 > 0 就得刷新（那几位已生效，部分失败也一样）；
 * 全失败时宿主应停在选人页让用户换人（对齐 iOS `runAddAdmins:`）。
 */
internal suspend fun addAdminsResult(client: IMClient, convId: String, uids: List<String>): Pair<Int, String> {
    var ok = 0
    var bad = 0
    var firstError: String? = null
    for (uid in uids.take(GroupPick.MAX_ADMIN_BATCH)) {
        val r = runCatchingCancellable { client.groups.setRole(convId, uid, GroupMember.ROLE_ADMIN) }
        if (r.isSuccess) {
            ok++
        } else {
            bad++
            if (firstError == null) firstError = r.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
        }
    }
    val toast = when {
        bad == 0 -> Str.p(R.plurals.group_admin_list_batch_added, ok, ok)
        ok == 0 -> firstError ?: Str.s(R.string.group_admin_list_add_failed)
        else -> Str.s(R.string.group_admin_list_batch_partial, ok, bad, firstError ?: Str.s(R.string.common_action_failed))
    }
    return ok to toast
}

/** 整体替换群设置（PUT）；失败先 [rollback] 本地乐观值再把异常抛回给 runManage 去提示。 */
internal suspend fun putSettingsOrRollback(client: IMClient, convId: String, v: GroupSettings.Values, rollback: () -> Unit) {
    try {
        client.groups.updateSettings(
            convId,
            joinApproval = v.joinApproval,
            permInvite = v.permInvite,
            permEditInfo = v.permEditInfo,
            permPin = v.permPin,
            historyVisible = v.historyVisible,
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        rollback()
        throw e
    }
}
