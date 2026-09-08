package com.libeyond.imandroid.sdk.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上行 `send_msg.data` 的**线格式**。
 *
 * **这组测试的由来**：引用回复在本端一直没生效——上行协议要的是嵌套对象
 * `reply_to: {conv_seq}`，而本端发的是扁平的 `reply_to_conv_seq`（那是**下行**字段名）。
 * 服务端读 `data.ReplyTo.ConvSeq` 读不到，**静默忽略**：菜单能点、引用条能显示、
 * 消息也发得出去，就是不带引用。一直到 2026-09-08 对着 iOS 核样式时，
 * 才发现服务端库里 `reply_to_conv_seq` 恒为 0。
 *
 * 上下行同名不同形是这类 bug 的温床，所以这里直接钉 JSON。
 */
class SendMsgWireTest {

    private fun json(d: SendMsgData): String = ProtocolJson.encodeToString(SendMsgData.serializer(), d)

    private fun base(replyTo: ReplyToData? = null) = SendMsgData(
        clientMsgId = "cid-1",
        convId = "u_a_b",
        to = "b",
        contentType = ContentType.TEXT,
        content = "hi",
        replyTo = replyTo,
    )

    @Test
    fun `引用走嵌套的 reply_to 对象`() {
        val s = json(base(ReplyToData(1234)))
        assertTrue("必须是 reply_to.conv_seq 嵌套形", s.contains("\"reply_to\""))
        assertTrue(s.contains("\"conv_seq\":1234"))
    }

    /** **绝不能发扁平的 `reply_to_conv_seq`**：那是下行字段名，服务端上行不认。 */
    @Test
    fun `不发下行那个扁平字段名`() {
        assertFalse(json(base(ReplyToData(1234))).contains("reply_to_conv_seq"))
    }

    /** 不是引用时整个字段都不出现（服务端判 `data.ReplyTo != nil`）。 */
    @Test
    fun `非引用时不带这个字段`() {
        assertFalse(json(base(null)).contains("reply_to"))
    }
}
