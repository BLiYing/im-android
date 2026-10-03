package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.InviteResult

/** 邀请结果该提示什么（纯判据，便于单测）。 */
sealed interface InviteOutcome {
    /** 有人转成了待审申请（进群确认对普通成员邀请生效）。 */
    data object Pending : InviteOutcome

    /** added 与 pending 都空：所选的人都已在群里。 */
    data object AllIn : InviteOutcome

    data class Partial(val invited: Int, val skipped: Int) : InviteOutcome

    /** 全部直接入群，或老服务端无字段：走通用成功提示。 */
    data object Plain : InviteOutcome

    companion object {
        fun of(selected: Int, r: InviteResult): InviteOutcome = when {
            !r.known -> Plain
            r.pending.isNotEmpty() -> Pending
            r.added.isEmpty() -> AllIn
            r.added.size < selected -> Partial(r.added.size, selected - r.added.size)
            else -> Plain
        }
    }
}
