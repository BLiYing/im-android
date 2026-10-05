package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ConvUpdateData
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * conv_update settings 帧带会话备注全值（G1）。**缺键（null）≠ 清除（空串）**：缺键不触发标题刷新，
 * 否则旧服务端 / 别种帧会把已设的备注冲回群名；只有显式的 `""` 才表示清除。
 */
class ConvUpdateRemarkTest {
    private fun decode(json: String) = ProtocolJson.decodeFromString(ConvUpdateData.serializer(), json)

    @Test fun `settings 帧带备注全值`() =
        assertEquals("项目组", decode("""{"conv_id":"g1","action":"settings","remark":"项目组"}""").remark)

    @Test fun `备注被清除时是空串`() =
        assertEquals("", decode("""{"conv_id":"g1","action":"settings","remark":""}""").remark)

    @Test fun `缺 remark 键是 null 而不是空串`() =
        assertNull(decode("""{"conv_id":"g1","action":"settings","muted":true}""").remark)

    @Test fun `delete 帧不带 remark`() =
        assertNull(decode("""{"conv_id":"g1","action":"delete","cleared_at":5}""").remark)
}
