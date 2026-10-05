package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ConvUpdateData
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * conv_update 带会话备注全值（G1）。服务端 `Remark` **无 omitempty**：settings 与 delete 帧都带 `"remark":""`，
 * 所以必须靠 `action` 分辨哪一帧的备注才是真值——否则别的设备删除会话，开着的聊天页标题会误回退成群名。
 */
class ConvUpdateRemarkTest {
    private fun decode(json: String) = ProtocolJson.decodeFromString(ConvUpdateData.serializer(), json)

    @Test fun `settings 帧带备注全值就发信号`() =
        assertEquals(
            ConvRemarkSignal("g1", "项目组"),
            ConvRemarkSignal.of(decode("""{"conv_id":"g1","action":"settings","remark":"项目组"}""")),
        )

    @Test fun `settings 帧备注为空串表示清除也要发信号`() =
        assertEquals(
            ConvRemarkSignal("g1", ""),
            ConvRemarkSignal.of(decode("""{"conv_id":"g1","action":"settings","remark":""}""")),
        )

    @Test fun `delete 帧虽然带空备注也不能发信号`() =
        assertNull(ConvRemarkSignal.of(decode("""{"conv_id":"g1","action":"delete","remark":"","cleared_at":5}""")))

    @Test fun `未知 action 不发信号`() =
        assertNull(ConvRemarkSignal.of(decode("""{"conv_id":"g1","action":"","remark":"x"}""")))

    @Test fun `缺 remark 键解码为空串`() =
        assertEquals("", decode("""{"conv_id":"g1","action":"settings","muted":true}""").remark)
}
