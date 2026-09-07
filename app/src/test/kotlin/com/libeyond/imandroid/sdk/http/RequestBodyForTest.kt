package com.libeyond.imandroid.sdk.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 无体请求的请求体。
 *
 * 这条测的是一个**「点了没反应」**级别的坑：OkHttp 给 POST 传 null body 会当场抛异常，
 * 请求根本没上路，服务端日志空空如也。2026-09-07 在真机上按「退出登录该设备」时实测到。
 */
class RequestBodyForTest {

    @Test
    fun `无体 POST 发空 JSON 对象而不是 null`() {
        assertEquals("{}", HttpClient.requestBodyFor("POST", null))
        assertEquals("{}", HttpClient.requestBodyFor("PUT", null))
        assertEquals("{}", HttpClient.requestBodyFor("PATCH", null))
        // 大小写不敏感：调用方写 "post" 也不该退化成无体
        assertEquals("{}", HttpClient.requestBodyFor("post", null))
    }

    @Test
    fun `GET 与 DELETE 保持无体`() {
        // GET 带体 OkHttp 同样直接抛——不能图省事一律发 {}，那会把所有 GET 打挂
        assertNull(HttpClient.requestBodyFor("GET", null))
        assertNull(HttpClient.requestBodyFor("DELETE", null))
    }

    @Test
    fun `有负载时原样发出`() {
        assertEquals("""{"a":1}""", HttpClient.requestBodyFor("POST", """{"a":1}"""))
        assertEquals("""{"a":1}""", HttpClient.requestBodyFor("GET", """{"a":1}"""))
    }
}
