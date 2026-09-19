package com.libeyond.imandroid.rtc

import java.util.concurrent.ConcurrentHashMap

/**
 * **兜底名片**的本机缓存 + 取数去重（纯逻辑，IO 在 [RtcProfileResolver]）。
 *
 * 通话界面显示什么以 IM 自己的数据为准（见 [RtcProfileSources]）；只有 IM 本地**一个名字都没有**的 uid
 * （群里从没加载过的成员、超级群里不在成员表的人）才走这里：取一次名片，之后照常用。
 * **不按时间过期、也不在通话开始时刷新**——保持新鲜是 IM 自己各页面的事，通话界面跟着 IM 走。
 *
 * 失败的 uid 隔 [retryAfterMs] 才允许再取，别让一个查不到的人把接口打成每帧一次。
 */
class RtcProfileBook(private val retryAfterMs: Long = 30_000) {

    data class Entry(val name: String, val avatarUrl: String)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    fun name(uid: String): String? = entries[uid]?.name

    fun avatarUrl(uid: String): String = entries[uid]?.avatarUrl.orEmpty()

    /** 该不该现在去取：缓存里没有、没有在途请求、且不在失败冷却期内。true 即已占位，调用方**必须**随后 [put] 或 [fail]。 */
    fun claim(uid: String, nowMs: Long): Boolean {
        if (uid.isEmpty() || entries.containsKey(uid)) return false
        val failed = failedAt[uid]
        if (failed != null && nowMs - failed < retryAfterMs) return false
        return inflight.add(uid)
    }

    fun put(uid: String, name: String, avatarUrl: String) {
        entries[uid] = Entry(name, avatarUrl)
        failedAt.remove(uid)
        inflight.remove(uid)
    }

    fun fail(uid: String, nowMs: Long) {
        failedAt[uid] = nowMs
        inflight.remove(uid)
    }
}
