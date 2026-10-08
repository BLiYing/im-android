package com.libeyond.imandroid.rtc

import com.libeyond.imandroid.sdk.api.RtcTokenResult
import com.libeyond.imandroid.sdk.http.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [rtcTokenFrom]：从换票结果（`POST /api/v1/rtc/token`）推导最终可用的 token。
 * 换票机制从「调试密钥本地签票」迁移到「IMServer 代理换票」时新增——网络层本身（`RtcApi`）
 * 这个项目没有 mock 基础设施覆盖（`ProfileApi`/`AuthApi` 等既有 Api 类同样没有），
 * 抽出这个纯函数是这次改动里唯一能独立测的判定逻辑。
 *
 * 故意是包级函数而不是 `RtcCall` 的成员测试：`RtcCall`（object）的类初始化依赖
 * `Handler(Looper.getMainLooper())`，纯 JVM 单测下一碰它的任何成员就抛异常
 * （首次这样写时真的踩到过：931 个用例里 4 个全红，见 `rtcTokenFrom` 上的注释）。
 */
class RtcCallTest {

    @Test
    fun successful_result_with_token_is_used() {
        val result = Result.success(RtcTokenResult(token = "rtc-jwt", expiresInSec = 43200))
        assertEquals("rtc-jwt", rtcTokenFrom(result))
    }

    @Test
    fun successful_result_with_empty_token_is_null() {
        // 服务端理论上不该回空 token，但响应缺字段时不该让空串冒充有效票。
        val result = Result.success(RtcTokenResult(token = ""))
        assertNull(rtcTokenFrom(result))
    }

    @Test
    fun failed_result_is_null() {
        val result = Result.failure<RtcTokenResult>(ApiException(600001, "rtc not configured"))
        assertNull(rtcTokenFrom(result))
    }

    @Test
    fun transport_failure_is_null() {
        val result = Result.failure<RtcTokenResult>(RuntimeException("network down"))
        assertNull(rtcTokenFrom(result))
    }
}
