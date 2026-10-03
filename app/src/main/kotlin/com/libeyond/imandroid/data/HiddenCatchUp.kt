package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.HiddenItem

/**
 * 「仅为我删除」的**补课**（对齐 iOS `fetchHiddenCatchUpWithToken:`）：别的设备上删过的消息，服务端 sync 增量会滤掉，
 * 但**本机早先已落库**的那几行还在——拉 `GET /messages/hidden` 把它们从本地移除。
 * 每次会话列表刷新成功后跑一遍（含重连触发的）；尽力而为，失败/为空静默。
 *
 * ⚠️ 移除**不能再触发会话列表刷新**：iOS 就栽过——删除通知 → 列表重载 → 补课再删 → 又发通知，
 * 一个 0.47 秒一圈的自我刷新环。Android 靠 Room Flow 自己更新界面，这里只做删除，不发任何信号。
 */
object HiddenCatchUp {
    /** conv_seq <= 0 / conv_id 空的脏项丢掉；按会话归并，每个会话一条 `DELETE … IN`。 */
    fun groupByConv(items: List<HiddenItem>): Map<String, List<Long>> =
        items.filter { it.convId.isNotEmpty() && it.convSeq > 0 }
            .groupBy({ it.convId }, { it.convSeq })
            .mapValues { (_, seqs) -> seqs.distinct() }
}

/** 会话列表刷新成功后补一遍「仅为我删除」（静默、尽力而为）。 */
internal suspend fun MessageService.catchUpHidden(owner: String) {
    try {
        HiddenCatchUp.groupByConv(conversationsApi.hiddenMessages()).forEach { (convId, seqs) ->
            repo.removeMessages(owner, convId, seqs, retract = false)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w("hidden_catchup_failed", "err" to e.javaClass.simpleName)
    }
}
