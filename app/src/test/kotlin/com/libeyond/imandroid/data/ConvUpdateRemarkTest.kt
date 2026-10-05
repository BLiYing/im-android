package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ConvUpdateData
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** conv_update settings 帧带会话备注全值（G1）；缺键 = 无备注，聊天页标题据此回退群名。 */
class ConvUpdateRemarkTest {
    private fun decode(json: String) = ProtocolJson.decodeFromString(ConvUpdateData.serializer(), json)

    @Test fun `settings 帧带备注全值`() =
        assertEquals("项目组", decode("""{"conv_id":"g1","action":"settings","remark":"项目组"}""").remark)

    @Test fun `备注被清除时是空串`() =
        assertEquals("", decode("""{"conv_id":"g1","action":"settings","remark":""}""").remark)

    @Test fun `缺 remark 键按无备注处理`() =
        assertEquals("", decode("""{"conv_id":"g1","action":"settings","muted":true}""").remark)
}
