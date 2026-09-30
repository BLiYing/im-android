package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [NotificationRoute]：点系统推送通知后"待跳转会话"的判据与消费纪律（M5 批次 2）。
 * **存在的理由**：只有真正确认跳转成功才能 consume——这里钉住"token 对不上就不清"这条闸，
 * 以及 conv_id 判私聊/群聊/解不出来兜底群聊的分支。
 *
 * [NotificationRoute] 是进程内单例（同 [InAppBannerStore]），每个测试自己在开头清掉上一条残留状态
 * ——同 `InAppBannerTest` 里 `InAppBannerStore.dismiss()` 的既有手法，不依赖测试方法的执行顺序。
 */
class NotificationRouteTest {

    @Before
    fun clearPending() {
        NotificationRoute.pending.value?.let { NotificationRoute.consume(it.token) }
    }

    @Test
    fun `request 产生待跳转，token 对不上不清，对上才清`() {
        NotificationRoute.request("g_1", "群 A")
        val first = NotificationRoute.pending.value!!
        assertEquals("g_1", first.convId)

        NotificationRoute.consume(first.token - 1)
        assertEquals(first, NotificationRoute.pending.value)

        NotificationRoute.consume(first.token)
        assertNull(NotificationRoute.pending.value)
    }

    @Test
    fun `再次 request 覆盖上一条，token 递增`() {
        NotificationRoute.request("g_1", "群 A")
        val t1 = NotificationRoute.pending.value!!.token
        NotificationRoute.request("g_2", "群 B")
        val second = NotificationRoute.pending.value!!
        assertTrue(second.token > t1)
        assertEquals("g_2", second.convId)
    }

    @Test
    fun `convId 为空不产生待跳转`() {
        NotificationRoute.request("", "无效")
        assertNull(NotificationRoute.pending.value)
    }

    @Test
    fun `私聊 conv_id 解出对端——不论自己排在字典序前段还是后段`() {
        val convId = "u_1002_u_2005"
        assertEquals(NotificationRoute.Kind.Private("2005"), NotificationRoute.resolveKind(convId, myUid = "1002"))
        assertEquals(NotificationRoute.Kind.Private("1002"), NotificationRoute.resolveKind(convId, myUid = "2005"))
    }

    @Test
    fun `群聊 conv_id 直接判 Group`() {
        assertEquals(NotificationRoute.Kind.Group, NotificationRoute.resolveKind("g_abc123", myUid = "1002"))
    }

    @Test
    fun `未知形状 或 两段都不是自己——兜底当群聊，不是死路`() {
        assertEquals(NotificationRoute.Kind.Group, NotificationRoute.resolveKind("weird_shape", myUid = "1002"))
        assertEquals(NotificationRoute.Kind.Group, NotificationRoute.resolveKind("u_9_u_8", myUid = "1002"))
    }
}
