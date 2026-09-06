package com.libeyond.imandroid.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单聊 conv_id 推导（与后端 `p2pConvID`、iOS `IMConversationID`、Web `convIdFor` 同构）。
 *
 * 服务端以自己的推导为权威、**不信客户端传入的 conv_id**，所以推错了不会报错，
 * 只是本地那条会话永远对不上服务端下发的那条——表现为「发出去了但会话列表里没有」。
 */
class ConvIdTest {

    /** 复刻 IMClient.conversationStubFor 的推导。 */
    private fun convId(me: String, peer: String): String {
        val (a, b) = if (me <= peer) me to peer else peer to me
        return "u_${a}_u_$b"
    }

    @Test
    fun `字典序排序`() {
        assertEquals("u_1001_u_1002", convId("1001", "1002"))
        assertEquals("u_1001_u_1002", convId("1002", "1001"))
    }

    /**
     * **必须字典序，不能数值序**。uid 是 10 位随机数字，但系统账号 777000 只有 6 位：
     * 数值上 777000 < 5205766476，字典序上 "5205766476" < "777000"。
     * 服务端实际下发的是 `u_5205766476_u_777000`——即字典序。
     * 按数值排会推出 `u_777000_u_5205766476`，与服务端对不上。
     */
    @Test
    fun `位数不同时按字典序而非数值序`() {
        assertEquals("u_5205766476_u_777000", convId("5205766476", "777000"))
        assertEquals("u_5205766476_u_777000", convId("777000", "5205766476"))
    }

    @Test
    fun `自聊`() {
        assertEquals("u_1001_u_1001", convId("1001", "1001"))
    }

    @Test
    fun `与服务端实际下发的会话号一致`() {
        // 这几个都是 2026-09-07 从真实后端拉回来的 conv_id
        assertEquals("u_1000156391_u_5205766476", convId("5205766476", "1000156391"))
        assertEquals("u_3590832938_u_5205766476", convId("5205766476", "3590832938"))
        assertEquals("u_5205766476_u_5638703414", convId("5205766476", "5638703414"))
        assertEquals("u_5205766476_u_9801803917", convId("9801803917", "5205766476"))
    }
}
