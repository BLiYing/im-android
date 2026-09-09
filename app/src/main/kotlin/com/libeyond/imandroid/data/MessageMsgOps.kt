package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.MsgOpData
import com.libeyond.imandroid.sdk.protocol.ProtocolJson

/**
 * [MessageRepository] 的**遗留 `msg_op` 事件行收敛**。
 *
 * 从 MessageRepository 平移出来（2026-09-10，合并两条线的改动后那个文件触了 600 行红线），
 * **行为未改**。写成扩展函数而不是搬进另一个类：调用形态一处不用改
 * （`repo.convergeLegacyMsgOpRows(...)` 照旧），同 `MessageWindowQueries` 的取舍。
 *
 * 这是一次性的历史数据修复，不是收发链路的一部分——单独一个文件也更好找。
 */

/**
 * 收敛历史遗留的 `msg_op` 事件行。
 *
 * 本端在 2026-09-09 之前没有 [IncomingRule] 那道口径，把事件行当普通消息落了库——
 * 症状是聊天页里冒出裸 JSON 气泡，**病根是那几次撤回/编辑/置顶/删除从来没被应用**。
 * 改了入库口径只能管住以后的，已经躺在库里的那些得在这里补课：
 * **先应用效果、再删行**，顺序反了就等于把那几次操作永久丢掉。
 *
 * 每次连上都跑一次：第一次之后库里就没有这种行了，之后是一次走索引的空查询。
 * 回本次清掉的行数（0 = 干净）。
 *
 * ⚠️ **删的必须是"刚刚应用过的那几条"，不能是"这个账号下所有 msg_op 行"**：
 * 取数带 [limit]，一次 `DELETE ... WHERE contentType='msg_op'` 会把这一批之外
 * 还没应用的行一起抹掉——那正是上面那句"顺序反了就等于把那几次操作永久丢掉"的
 * 另一种走法（11 万条的大群里 500 这个上限一次就够不着）。所以按批走，逐条删。
 */
suspend fun MessageRepository.convergeLegacyMsgOpRows(owner: String, limit: Int = 500): Int {
    var applied = 0
    var deleted = 0
    while (true) {
        val rows = messages.legacyMsgOpRows(owner, limit)
        if (rows.isEmpty()) break
        val before = deleted
        for (r in rows) {
            runCatching { ProtocolJson.decodeFromString(MsgOpData.serializer(), r.content) }
                .onSuccess { applyMsgOp(owner, it) }
                .onFailure { log.w("legacy_msg_op_undecodable", "convId" to r.convId, "seq" to r.convSeq) }
            // 应用完立刻删这一条：中途被取消/崩溃时，剩下的行下次还会被重新取到并应用。
            messages.delete(owner, r.convId, r.convSeq)
            deleted++
            applied++
        }
        // 一批下来一条都没删掉说明删不动（理论上不该发生），别在这里空转
        if (deleted == before) break
    }
    // 也记一笔：不然"跑没跑过"无从判断（这一步一辈子只跑一次，没日志就查不了）
    log.i("legacy_msg_op_converged", "applied" to applied, "deleted" to deleted)
    return deleted
}
