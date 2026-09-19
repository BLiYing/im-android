package com.libeyond.imandroid.rtc

import java.util.concurrent.ConcurrentHashMap

/**
 * 通话界面要显示的「这个 uid 是谁」的本机缓存 + 取数去重（**纯逻辑**，IO 在 [RtcProfileResolver]）。
 *
 * Kit 每次重画都会同步问一遍名字，而名字要去网络上取：所以问的时候**先答缓存里有的、没有的只发一次请求**，
 * 取回来再通知 Kit 重画。失败的 uid 隔 [retryAfterMs] 才允许再取，别让一个查不到的人把接口打成每帧一次。
 */
class RtcProfileBook(
    private val retryAfterMs: Long = 30_000,
    /** 取到的名片超过这么久算陈旧，再被问到时后台刷新一次（对方刚换了头像 / 昵称，别一直显示旧的）；旧值照常先答。 */
    private val staleAfterMs: Long = 60_000,
) {

    data class Entry(val name: String, val avatarUrl: String, val fetchedAt: Long = 0)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    fun name(uid: String): String? = entries[uid]?.name

    fun avatarUrl(uid: String): String = entries[uid]?.avatarUrl.orEmpty()

    /** 该不该现在去取这个 uid：缓存里没有（或已陈旧）、没有在途请求、且不在失败冷却期内。返回 true 即已占位，调用方**必须**随后 [put] 或 [fail]。 */
    fun claim(uid: String, nowMs: Long): Boolean {
        if (uid.isEmpty()) return false
        val cached = entries[uid]
        if (cached != null && nowMs - cached.fetchedAt < staleAfterMs) return false
        val failed = failedAt[uid]
        if (failed != null && nowMs - failed < retryAfterMs) return false
        return inflight.add(uid)
    }

    fun put(uid: String, name: String, avatarUrl: String, nowMs: Long) {
        entries[uid] = Entry(name, avatarUrl, nowMs)
        failedAt.remove(uid)
        inflight.remove(uid)
    }

    fun fail(uid: String, nowMs: Long) {
        failedAt[uid] = nowMs
        inflight.remove(uid)
    }
}
