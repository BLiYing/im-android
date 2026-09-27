package com.libeyond.imandroid.ui

import android.content.Context
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.protocol.ErrCode

/**
 * 业务码 → 用户可读文案。**按码分支，绝不 parse 服务端文案**
 * （文案会改、会多语言；PROTOCOL §8 也写明 message 只给开发看）。
 *
 * 未覆盖的码回退服务端文案——比显示一个码号强，且能暴露我们还没处理的分支。
 *
 * 从 `AppRoot.kt` 里抽出来（2026-09-07）**就是为了能测**：
 * 这里最贵的一条不是翻译对不对，而是**别把一种失败说成另一种**——
 * 「明文被系统拦截」被说成「请检查服务器地址」，会让人去反复核对一个完全正确的地址。
 */
object LoginError {

    /**
     * 取文案的方式抽成接口而不是直接吃 [Context]：本仓 JVM 单测没有接 Robolectric，真 [Context]
     * 在纯 JUnit 里连 `getString` 都会因为 android.jar 是桩实现而直接抛异常。resId 是编译期常量，
     * 单测按 id 断言分支走对了就够，不需要真的加载资源——资源内容本身由生成器的
     * `gen-i18n.mjs --check` 与 XML 文件把关。
     */
    fun interface Strings {
        fun get(resId: Int, vararg args: Any): String
    }

    fun friendly(e: ApiException, strings: Strings): String = when {
        // 必须排在 isTransport 之前：它是 transport 的一个子情形，且诊断完全不同。
        // 这两条是本端特有的传输层诊断，`err.*` 共享表里没有对应条目，不参与本轮 i18n 接线。
        e.isCleartextBlocked ->
            "系统拦截了明文 HTTP 连接（地址没写错）。开发请装 debug 包；正式版只允许 https/wss。"
        e.isTransport -> strings.get(R.string.net_error_unreachable)
        else -> when (e.code) {
            // 故意不复用 IMServer/docs/i18n/strings.json 的 err.200002（"密码错误"）——那条会暴露
            // 「用户名是对的、只是密码错了」，本端刻意用「用户名或密码错误」防用户名被枚举出来，
            // 这是既有的安全考量，不因为接上多语言就悄悄改掉。
            ErrCode.WRONG_PASSWORD -> "用户名或密码错误"
            ErrCode.USER_NOT_FOUND -> strings.get(R.string.err_200001)
            ErrCode.USER_ALREADY_EXISTS -> strings.get(R.string.err_200004)
            ErrCode.ACCOUNT_BANNED -> strings.get(R.string.err_200003)
            ErrCode.PARAM_INVALID -> e.message.ifEmpty { "输入不合法" }
            ErrCode.RATE_LIMITED -> strings.get(R.string.err_100002)
            else -> e.message.ifEmpty { strings.get(R.string.err_request_failed, e.code.toString()) }
        }
    }

    /** 生产代码用这个：直接包一层 [Context.getString]。 */
    fun friendly(e: ApiException, context: Context): String =
        friendly(e, Strings { resId, args -> context.getString(resId, *args) })
}
