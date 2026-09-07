package com.libeyond.imandroid.ui

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

    fun friendly(e: ApiException): String = when {
        // 必须排在 isTransport 之前：它是 transport 的一个子情形，且诊断完全不同
        e.isCleartextBlocked ->
            "系统拦截了明文 HTTP 连接（地址没写错）。开发请装 debug 包；正式版只允许 https/wss。"
        e.isTransport -> "网络连接失败，请检查服务器地址"
        else -> when (e.code) {
            ErrCode.WRONG_PASSWORD -> "用户名或密码错误"
            ErrCode.USER_NOT_FOUND -> "用户不存在"
            ErrCode.USER_ALREADY_EXISTS -> "该用户名已被占用"
            ErrCode.ACCOUNT_BANNED -> "账号已被封禁"
            ErrCode.PARAM_INVALID -> e.message.ifEmpty { "输入不合法" }
            ErrCode.RATE_LIMITED -> "操作太频繁，请稍后再试"
            else -> e.message.ifEmpty { "请求失败（${e.code}）" }
        }
    }
}
