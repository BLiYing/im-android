package com.libeyond.imandroid.ui

import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ErrorText
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.http.ApiException

/**
 * 把一个异常翻译成**给用户看的**一句话。
 *
 * 两条纪律：
 *  ① 服务端明确拒绝（有业务码）：收录了本地化的码用本地化文案（[ErrorText]，服务端 message 是英文），
 *     没收录的才用服务端文案——它知道为什么拒；
 *  ② 传输层失败**不能**照抄 OkHttp 的英文异常（`failed to connect to /10.0.2.2`），
 *    那对用户毫无意义，且会把内网地址泄在界面上。
 *
 * **不按文案字符串分支**——要按码分支一律读 `ErrCode` 常量（CODING_STYLE §5）。
 */
internal fun Throwable.userMessage(fallback: String): String {
    val api = this as? ApiException ?: return fallback
    if (api.isTransport) return Str.s(R.string.common_error_network_unavailable_detail, fallback)
    // 有本地化文案的码先走本地化（服务端 message 是英文）；没收录的才回退服务端原文
    ErrorText.friendlyRes(api.code)?.let { return Str.s(it) }
    return api.message.ifBlank { fallback }
}

/**
 * `runCatching`，但**不吞协程取消**。
 *
 * 裸 `runCatching` 抓的是 `Throwable`，`CancellationException` 也在内——于是页面已经退出、
 * 协程本该结束，`onFailure` 却照跑，还往一个死了的 Composable 里写状态、弹提示。
 * CODING_STYLE §5 明写了这条，这里给一个统一入口，省得每个调用点自己 `catch` 一遍。
 *
 * **本仓其余 Host（ChatHost / ContactsHost / GroupInfoHost / MainScreen）还在用裸
 * `runCatching`**，是同一个问题的存量，本次没一并改——那几个文件另一条线正在动，
 * 顺手改会撞车。
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
