package com.libeyond.imandroid.sdk.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 多选批量删除两档的线上形状（PROTOCOL §6.7.1 / §6.7.2）：`POST /messages/hide` 与 `POST /messages/delete`
 * 都收 `{conv_id, conv_seqs}`（≤100），服务端逐条回 `results`。
 *
 * 与 im-web `selectDelete.ts` 的 `summarizeBatch`、iOS `IMBatchOKSeqs` 同口径。
 */
@Serializable
data class BatchItemResult(
    @SerialName("conv_seq") val convSeq: Long = 0,
    val ok: Boolean = false,
    /** 失败时的业务码：300005 目标不存在 / 300006 无权。 */
    val code: Int = 0,
)

@Serializable
internal data class BatchResults(val results: List<BatchItemResult> = emptyList())

object BatchDelete {
    /** 服务端单次上限，与多选上限同值。 */
    const val MAX = 100

    /**
     * 把服务端 results 对回请求的 seqs，返回**成功的**那些（保持请求顺序）。
     * 缺项一律算失败——宁可多报一条失败，也不把没删掉的当删掉了。失败条数 = seqs.size - 返回值.size。
     */
    fun okSeqs(seqs: List<Long>, results: List<BatchItemResult>): List<Long> {
        val ok = results.filter { it.ok }.map { it.convSeq }.toSet()
        return seqs.filter { it in ok }
    }
}
