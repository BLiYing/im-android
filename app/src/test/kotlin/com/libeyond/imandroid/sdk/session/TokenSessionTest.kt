package com.libeyond.imandroid.sdk.session

import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.sdk.session.RestoreDecision.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话恢复的三条判据（搬自 Web `sdk/tokenSession.ts`）。
 *
 * 打在 [RestoreDecision] 这个**真实实现**上——不在测试里复刻一份判定逻辑，
 * 那种复刻件会与实现漂移（改了实现忘了改复刻件，测试照样全绿）。
 */
class TokenSessionTest {

    private val NOT_TRANSPORT = false
    private val TRANSPORT = true

    // ——— 判据一：探活通过就直接用，不要重新登录 ———

    @Test
    fun `未登录直接回 NoCredentials`() {
        assertEquals(Step.NoCredentials, RestoreDecision.afterProbe(false, null, NOT_TRANSPORT))
    }

    /**
     * 探活成功 → Alive。**绝不能是"重新登录"**：
     * 直接 `/login` 会重新签发一条新会话，把「这台设备已被踢下线」当场自愈掉。
     */
    @Test
    fun `探活通过则复用会话`() {
        assertEquals(Step.Alive, RestoreDecision.afterProbe(true, null, NOT_TRANSPORT))
    }

    @Test
    fun `token 过期才轮到续期`() {
        assertEquals(Step.TryRefresh, RestoreDecision.afterProbe(true, ErrCode.TOKEN_EXPIRED, NOT_TRANSPORT))
        assertEquals(Step.TryRefresh, RestoreDecision.afterProbe(true, ErrCode.TOKEN_INVALID, NOT_TRANSPORT))
    }

    // ——— 判据三：连不上 ≠ 会话已死 ———

    @Test
    fun `探活网络失败不否定会话`() {
        assertEquals(Step.Unreachable, RestoreDecision.afterProbe(true, ErrCode.TOKEN_EXPIRED, TRANSPORT))
    }

    /** 服务端 500 没有否定会话本身，保留（否则后端抖一下全体用户被登出）。 */
    @Test
    fun `探活遇非鉴权错误不否定会话`() {
        assertEquals(Step.Unreachable, RestoreDecision.afterProbe(true, ErrCode.INTERNAL, NOT_TRANSPORT))
        assertEquals(Step.Unreachable, RestoreDecision.afterProbe(true, ErrCode.NO_PERMISSION, NOT_TRANSPORT))
    }

    // ——— 判据二：续期被明确拒绝 → 会话已死，不得回退重登 ———

    @Test
    fun `续期成功`() {
        assertEquals(Step.Alive, RestoreDecision.afterRefresh(true, null, NOT_TRANSPORT))
    }

    @Test
    fun `续期被拒即会话已死`() {
        assertEquals(Step.Dead, RestoreDecision.afterRefresh(true, ErrCode.TOKEN_INVALID, NOT_TRANSPORT))
        assertEquals(Step.Dead, RestoreDecision.afterRefresh(true, ErrCode.TOKEN_EXPIRED, NOT_TRANSPORT))
    }

    @Test
    fun `没有凭据即会话已死`() {
        assertEquals(Step.Dead, RestoreDecision.afterRefresh(false, null, NOT_TRANSPORT))
    }

    @Test
    fun `续期网络失败不清会话`() {
        assertEquals(Step.Unreachable, RestoreDecision.afterRefresh(true, ErrCode.TOKEN_EXPIRED, TRANSPORT))
    }

    // ——— SessionStore 的一条语义 ———

    /**
     * refresh_token 是**可选**的：会话登记降级时服务端不下发。
     * 这时不能把已有的那枚抹掉——否则一次降级登录就让本机永久失去续期能力。
     * （SessionStore 依赖 Context，这里只钉住那个条件判断的语义。）
     */
    @Test
    fun `降级登录不得抹掉已有凭据`() {
        var stored: String? = "r1"
        val incoming: String? = null
        if (!incoming.isNullOrEmpty()) stored = incoming
        assertEquals("r1", stored)

        val incoming2 = "r2"
        if (!incoming2.isNullOrEmpty()) stored = incoming2
        assertEquals("r2", stored)
    }
}
