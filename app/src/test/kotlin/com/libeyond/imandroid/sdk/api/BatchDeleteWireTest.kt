package com.libeyond.imandroid.sdk.api

import com.libeyond.imandroid.sdk.protocol.MsgHiddenData
import com.libeyond.imandroid.sdk.protocol.MsgOpData
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 多选批量删除两档（PROTOCOL §6.7.1 / §6.7.2）：服务端逐条结果怎么对回请求、msg_hidden 批量帧怎么读。
 * 与 im-web `selectDelete.test.ts`、iOS `IMBatchDeleteTests` 同一组用例。
 */
class BatchDeleteWireTest {

    @Test
    fun `成功的保持请求顺序，被拒与缺项都算失败`() {
        val results = ProtocolJson.decodeFromString(
            BatchResults.serializer(),
            """{"results":[{"conv_seq":1,"ok":true},{"conv_seq":2,"ok":false,"code":300006},{"conv_seq":4,"ok":true}]}""",
        ).results
        val ok = BatchDelete.okSeqs(listOf(1L, 2L, 3L, 4L), results)
        assertEquals(listOf(1L, 4L), ok)
        assertEquals(2, 4 - ok.size)
    }

    @Test
    fun `响应里没有 results：整批按失败算`() {
        val results = ProtocolJson.decodeFromString(BatchResults.serializer(), """{"ok":true}""").results
        assertEquals(emptyList<Long>(), BatchDelete.okSeqs(listOf(1L, 2L), results))
    }

    @Test
    fun `msg_hidden 批量帧读 conv_seqs，单条帧退回 conv_seq`() {
        fun seqs(json: String) = ProtocolJson.decodeFromString(MsgHiddenData.serializer(), json).seqs()
        assertEquals(listOf(3L, 5L, 9L), seqs("""{"conv_id":"g","conv_seq":3,"conv_seqs":[3,5,9]}"""))
        assertEquals(listOf(7L), seqs("""{"conv_id":"g","conv_seq":7}"""))
        assertEquals(emptyList<Long>(), seqs("""{"conv_id":"g"}"""))
    }

    @Test
    fun `批量删除广播帧不带 target_conv_seq 也能解出，一帧取出全批`() {
        val d = ProtocolJson.decodeFromString(
            MsgOpData.serializer(),
            """{"op":"delete","conv_id":"g","by":"1001","targets":[{"target_conv_seq":3,"op_conv_seq":10},{"target_conv_seq":5,"op_conv_seq":11}]}""",
        )
        assertEquals(listOf(3L, 5L), d.batchDeleteSeqs())
    }

    @Test
    fun `单条 msg_op 或非删除：不是批量帧，走单条路径`() {
        fun seqs(json: String) = ProtocolJson.decodeFromString(MsgOpData.serializer(), json).batchDeleteSeqs()
        assertNull(seqs("""{"op":"delete","conv_id":"g","target_conv_seq":3,"client_msg_id":"c"}"""))
        assertNull(seqs("""{"op":"pin","conv_id":"g","target_conv_seq":3,"client_msg_id":"c","targets":[]}"""))
    }
}
