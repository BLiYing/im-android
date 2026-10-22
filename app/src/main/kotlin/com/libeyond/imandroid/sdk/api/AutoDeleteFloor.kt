package com.libeyond.imandroid.sdk.api

import java.util.concurrent.ConcurrentHashMap

/**
 * 各会话当前的自动删除档位（convId → 秒），供**服务端分页接口**的结果过滤用：
 * 会话媒体库 / 会话内搜索 / 置顶列表走服务端，服务端不按档位过滤（设计 §3.1），端上要自己丢掉已过期项，
 * 否则本地库清掉了、媒体库里还翻得出来。
 *
 * 数据来源与本地库同一份：会话列表快照整份换入、`conv_update` 逐条写入（sec + from 成对）（`MessageRepository`）。
 * 只是个内存镜像，丢了（进程重启）会在会话列表刷新后重建；判据本身见 `data/AutoDelete.kt`。
 */
object AutoDeleteFloor {
    private data class Tier(val sec: Int, val from: Long)

    private val tiers = ConcurrentHashMap<String, Tier>()

    /** sec 与 from 一起写（会话级共享，永远成对覆盖）；关闭则移除。 */
    fun set(convId: String, sec: Int, from: Long) {
        if (sec > 0) tiers[convId] = Tier(sec, from) else tiers.remove(convId)
    }

    /** 会话列表快照是全量权威：整份换入（convId → sec to from）。 */
    fun replaceAll(all: Map<String, Pair<Int, Long>>) {
        tiers.keys.retainAll(all.keys.toSet())
        all.forEach { (k, v) -> set(k, v.first, v.second) }
    }

    fun clear() = tiers.clear()

    fun secOf(convId: String): Int = tiers[convId]?.sec ?: 0

    /** 该会话里这条（时间戳 [ts]）此刻是否已过期；关闭或没登记 = 否。公式见 `data/AutoDelete.kt`。 */
    fun expired(convId: String, ts: Long, now: Long = System.currentTimeMillis()): Boolean {
        val t = tiers[convId] ?: return false
        return t.sec > 0 && ts >= t.from && ts + t.sec * 1000L <= now
    }

    fun <T> dropExpired(convId: String, items: List<T>, tsOf: (T) -> Long): List<T> {
        if (tiers[convId] == null) return items
        val now = System.currentTimeMillis()
        return items.filterNot { expired(convId, tsOf(it), now) }
    }
}
