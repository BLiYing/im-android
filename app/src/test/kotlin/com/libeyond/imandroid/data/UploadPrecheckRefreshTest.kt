package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.ServerConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import com.libeyond.imandroid.sdk.logging.IMLog

class UploadPrecheckRefreshTest {
    @org.junit.Before fun setUp() {
        IMLog.useSinksForTest()
    }

    @org.junit.After fun tearDown() {
        ServerConfigStore.refresher = null
        ServerConfigStore.forget()
    }

    // 本地预检按缓存值拦下时要请求重拉：管理员关了上传又打开，不重拉就要等下次重连 / 回前台才放行。
    @Test
    fun `预检拦下时请求重拉配置，放行时不请求`() = runTest {
        val reasons = mutableListOf<String>()
        ServerConfigStore.refresher = { reasons += it }
        ServerConfigStore.forget()
        assertEquals(null, UploadPrecheck.checkCurrent("a.png", 10, isVoice = false))
        assertEquals(emptyList<String>(), reasons)

        ServerConfigStore.refresh("t", now = 0) { ServerConfig(uploadEnabled = false) }
        assertEquals(UploadPrecheck.Failure.Disabled, UploadPrecheck.checkCurrent("a.png", 10, isVoice = false))
        assertEquals(listOf("upload_precheck_rejected"), reasons)
    }
}
