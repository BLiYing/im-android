package com.libeyond.imandroid.ui

import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.protocol.ErrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownServiceException

/**
 * 登录失败的文案。**最贵的一条不是翻译准不准，而是别把一种失败说成另一种**。
 *
 * 2026-09-07 真机实测撞见：OPPO 填 192.168.1.12:8080（iOS 同地址能连），
 * 本端提示「网络连接失败，请检查服务器地址」——而地址完全正确，
 * 真实原因是 Android 的明文流量策略把请求挡在了出口（`UnknownServiceException`），
 * **请求根本没上路、服务端日志一行都没有**。这条误导直接让人去反复核对一个对的地址。
 *
 * `LoginError.friendly` 接了 `IMServer/docs/i18n/strings.json` 生成的字符串资源（2026-09-27），
 * 但取文案的方式是注入的 [LoginError.Strings]，不是真 [android.content.Context]——本仓 JVM 单测
 * 没接 Robolectric，这里用一个按 resId 返回当前生成文案的假实现，只验证「走对分支」，
 * 资源内容本身由 `gen-i18n.mjs --check` 与生成的 XML 把关。
 */
class LoginErrorTest {

    /** resId 对应值照抄 `values/i18n_strings.xml` 当前内容；文案表改了这里要跟着改，否则测试会撒谎。 */
    private val strings = LoginError.Strings { resId, args ->
        when (resId) {
            R.string.net_error_unreachable -> "无法连接服务器，请确认后端已启动、地址端口正确"
            R.string.err_200001 -> "用户不存在"
            R.string.err_200003 -> "账号已被封禁"
            R.string.err_200004 -> "用户名已被注册"
            R.string.err_100002 -> "操作过于频繁，请稍后再试"
            R.string.err_request_failed -> "请求失败(${args[0]})"
            else -> error("LoginErrorTest 的假 Strings 没覆盖 resId=$resId")
        }
    }

    private fun transport(cause: Throwable?) = ApiException(
        code = ApiException.TRANSPORT, message = "boom", cause = cause,
    )

    @Test
    fun `明文被拦截不能说成地址有问题`() {
        val msg = LoginError.friendly(transport(UnknownServiceException("CLEARTEXT ... not permitted")), strings)
        assertFalse("不能让人去查地址——地址是对的", msg.contains("确认后端已启动"))
        assertTrue(msg.contains("明文"))
    }

    @Test
    fun `普通连不上仍然提示查地址`() {
        // 真连不上时「查地址」是对的建议，别为了修上一条把这条也改坏
        assertEquals(
            "无法连接服务器，请确认后端已启动、地址端口正确",
            LoginError.friendly(transport(IOException("timeout")), strings),
        )
        assertEquals(
            "无法连接服务器，请确认后端已启动、地址端口正确",
            LoginError.friendly(transport(null), strings),
        )
    }

    @Test
    fun `明文判定只在传输层失败时成立`() {
        // 业务码非 0 时 cause 一般为空；就算带上也不该被认成明文拦截
        val biz = ApiException(ErrCode.WRONG_PASSWORD, "x", cause = UnknownServiceException("y"))
        assertFalse(biz.isCleartextBlocked)
        assertEquals("用户名或密码错误", LoginError.friendly(biz, strings))
    }

    @Test
    fun `按业务码分支而不是 parse 文案`() {
        assertEquals("用户不存在", LoginError.friendly(ApiException(ErrCode.USER_NOT_FOUND, "no such user"), strings))
        assertEquals("账号已被封禁", LoginError.friendly(ApiException(ErrCode.ACCOUNT_BANNED, "banned"), strings))
        // 未覆盖的码回退服务端文案，比显示码号强
        assertEquals("服务端说了句什么", LoginError.friendly(ApiException(999999, "服务端说了句什么"), strings))
        assertEquals("请求失败(999999)", LoginError.friendly(ApiException(999999, ""), strings))
    }
}
