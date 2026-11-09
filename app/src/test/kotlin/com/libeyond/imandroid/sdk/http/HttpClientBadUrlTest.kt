package com.libeyond.imandroid.sdk.http

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 用户手敲的非法 host 必须收成 transport [ApiException]，不能漏出 IllegalArgumentException 把登录页崩掉。 */
class HttpClientBadUrlTest {
    @Before fun setUp() = IMLog.useSinksForTest()

    @Test fun `非法 host 抛 transport ApiException`() = runBlocking {
        for (host in listOf("10.0.2.2:", "a b:8080", "10.0.2.2:abc")) {
            try {
                HttpClient(host).call("GET", "/api/v1/public-config", authenticated = false)
                fail("host=$host 应抛 ApiException")
            } catch (e: ApiException) {
                assertTrue("host=$host", e.isTransport)
            }
        }
    }
}
