package com.libeyond.imandroid.data

/**
 * 「某会话里某些消息被操作了」的轻量信号（撤回 / 删除 / 编辑 / 置顶 / 仅删自己），由 `MessageService` 在**落库之后**发出。
 * 聊天页的置顶横幅据此对齐服务端（对应 iOS 的 `IMSocketDidApplyMsgOp` 通知）：数据本身已经由
 * `MessageRepository` 收敛进消息行，这里只是告诉 UI「该重拉置顶集合了」。
 *
 * @param op `MsgOp.*`；「仅为我删除」没有 op，用 [HIDE]。
 * @param seqs 受影响的 conv_seq（批量删除一次多条）。
 */
data class MsgOpSignal(val convId: String, val op: String, val seqs: List<Long>) {
    companion object {
        const val HIDE = "hide"
    }
}

/** 会话备注变更（conv_update settings 帧）。[remark] 为全值，`""` = 已清除。 */
data class ConvRemarkSignal(val convId: String, val remark: String) {
    companion object {
        /**
         * 只有 settings 帧的 `remark` 才是真值。**delete 帧也带 `"remark":""`**（服务端无 omitempty），
         * 若不挡，别的设备删除会话会让正开着的聊天页标题误回退成群名。
         */
        fun of(u: com.libeyond.imandroid.sdk.protocol.ConvUpdateData): ConvRemarkSignal? =
            if (u.action == "settings") ConvRemarkSignal(u.convId, u.remark) else null
    }
}
