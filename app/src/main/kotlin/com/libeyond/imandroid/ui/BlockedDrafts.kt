package com.libeyond.imandroid.ui

import androidx.compose.runtime.mutableStateMapOf
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.protocol.ErrCode

/**
 * 「点确认就先关弹窗、再发请求」的编辑框（群名 / 简介 / 公告 / 我的群昵称 / 好友申请附言）
 * 被敏感词拦截（100004，SENSITIVE_WORDS_DESIGN §3「表单内容保留」）时，把用户写的字记下来：
 * 下次打开同一个编辑框用它当初值，而不是退回服务端旧值——否则改了半天的字一拦就没了。
 *
 * 只记 100004：其它失败（网络、无权限）原样重填的意义不大，维持原行为。成功一次即清掉。
 * 只在内存，跟随宿主页面的生命周期。
 */
class BlockedDrafts {
    private val texts = mutableStateMapOf<String, String>()

    /** 打开 [key] 那个编辑框时的初值：有被拦下的草稿用草稿，否则 [current]。 */
    fun initial(key: String, current: String): String = texts[key] ?: current

    /** 执行 [block]；抛 100004 时记下 [text] 再原样抛出（提示仍由调用方统一给），成功清掉草稿。 */
    suspend fun <T> keepIfBlocked(key: String, text: String, block: suspend () -> T): T =
        try {
            block().also { texts.remove(key) }
        } catch (e: ApiException) {
            if (e.code == ErrCode.CONTENT_BLOCKED) texts[key] = text
            throw e
        }
}
