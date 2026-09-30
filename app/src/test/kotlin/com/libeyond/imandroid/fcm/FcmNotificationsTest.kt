package com.libeyond.imandroid.fcm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FcmNotifications.shouldCancel]：消息被撤回/删除时，通知栏里那条该不该拿掉。 */
class FcmNotificationsTest {

    @Test
    fun `挂着的正是被收回的那条——取消`() {
        assertTrue(FcmNotifications.shouldCancel(displayedSeq = 42, retractedSeq = 42))
    }

    @Test
    fun `被收回的是更早的一条，通知栏里是后来的消息——不动`() {
        assertFalse(FcmNotifications.shouldCancel(displayedSeq = 43, retractedSeq = 42))
    }

    @Test
    fun `任一侧不知道是哪条——不取消，宁可多留不错杀`() {
        assertFalse(FcmNotifications.shouldCancel(displayedSeq = null, retractedSeq = 42))
        assertFalse(FcmNotifications.shouldCancel(displayedSeq = 42, retractedSeq = null))
        assertFalse(FcmNotifications.shouldCancel(displayedSeq = null, retractedSeq = null))
    }

    @Test
    fun `seq 为 0 不是一条真消息——不取消`() {
        assertFalse(FcmNotifications.shouldCancel(displayedSeq = 0, retractedSeq = 0))
    }

    @Test
    fun `没初始化 Context（单测 和 进程早期）调 retract 不崩`() {
        FcmNotifications.retract("u_1_u_2", 42)
    }
}
