package com.libeyond.imandroid.sdk.api

import java.util.concurrent.ConcurrentHashMap

/**
 * 各会话当前的自动删除档位（convId → 秒），供**服务端分页接口**的结果过滤用：
 * 会话媒体库 / 会话内搜索 / 置顶列表走服务端，服务端不按档位过滤（设计 §3.1），端上要自己丢掉已过期项，
 * 否则本地库清掉了、媒体库里还翻得出来。
 *
 * 数据来源与本地库同一份：会话列表快照整份换入、`conv_update` 逐条写入（`MessageRepository`）。
 * 只是个内存镜像，丢了（进程重启）会在会话列表刷新后重建；判据本身见 `data/AutoDelete.kt`。
 */
object AutoDeleteFloor {
    private val secs = ConcurrentHashMap<String, Int>()

    fun set(convId: String, sec: Int) {
        if (sec > 0) secs[convId] = sec else secs.remove(convId)
    }

    /** 会话列表快照是全量权威：整份换入。 */
    fun replaceAll(all: Map<String, Int>) {
        secs.keys.retainAll(all.keys.toSet())
        all.forEach { (k, v) -> set(k, v) }
    }

    fun clear() = secs.clear()

    fun secOf(convId: String): Int = secs[convId] ?: 0

    /** 该会话此刻的过期线；关闭为 0（不过滤）。 */
    fun floorTs(convId: String, now: Long = System.currentTimeMillis()): Long {
        val sec = secOf(convId)
        return if (sec > 0) maxOf(0L, now - sec * 1000L) else 0L
    }

    fun <T> dropExpired(convId: String, items: List<T>, tsOf: (T) -> Long): List<T> {
        val floor = floorTs(convId)
        return if (floor <= 0L) items else items.filter { tsOf(it) > floor }
    }
}
