package com.libeyond.imandroid.rtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 被服务端踢下线后引擎收掉、账号还记着：呼叫入口要现场重启一次（否则永远停在"通话服务未启动"）。
 * 退出登录（账号已忘）不能重启；引擎还在跑不用重启。对端：iOS `IMRtcCallInCallTests.testRestartOnlyWhenKickedAndAccountRemembered`。
 */
class RtcRestartPolicyTest {
    @Test fun kicked_with_account_restarts() = assertTrue(RtcRestartPolicy.shouldRestart(engineRunning = false, wantedAccount = true))
    @Test fun running_engine_does_not_restart() = assertFalse(RtcRestartPolicy.shouldRestart(engineRunning = true, wantedAccount = true))
    @Test fun logged_out_does_not_restart() = assertFalse(RtcRestartPolicy.shouldRestart(engineRunning = false, wantedAccount = false))
}
