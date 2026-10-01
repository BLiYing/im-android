package com.libeyond.imandroid.fcm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FcmNotifications.shouldCancel]：消息被撤回/删除时，通知栏里那条该不该拿掉；
 * [FcmNotifications.shouldClearOnRead]：读到某处之后该不该拿掉（PUSH_M5_DESIGN §3.5）。
 */
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
    fun `没初始化 Context（单测 和 进程早期）调 retract 和 clearReadThrough 不崩`() {
        FcmNotifications.retract("u_1_u_2", 42)
        FcmNotifications.clearReadThrough("u_1_u_2", 42)
    }

    @Test
    fun `已读到挂着的那条或更后——取消`() {
        assertTrue(FcmNotifications.shouldClearOnRead(displayedSeq = 42, readUpTo = 42))
        assertTrue(FcmNotifications.shouldClearOnRead(displayedSeq = 42, readUpTo = 50))
    }

    @Test
    fun `挂着的是位点之后来的新消息——留着`() {
        assertFalse(FcmNotifications.shouldClearOnRead(displayedSeq = 43, readUpTo = 42))
    }

    @Test
    fun `已读：不知道挂的是哪条或位点无效——留着`() {
        assertFalse(FcmNotifications.shouldClearOnRead(displayedSeq = null, readUpTo = 42))
        assertFalse(FcmNotifications.shouldClearOnRead(displayedSeq = 42, readUpTo = null))
        assertFalse(FcmNotifications.shouldClearOnRead(displayedSeq = 0, readUpTo = 0))
    }
}
