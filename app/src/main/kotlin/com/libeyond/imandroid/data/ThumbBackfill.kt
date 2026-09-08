package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.sdk.protocol.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 给**老消息**补种极小缩略（M4-7 的补漏那一半）。
 *
 * ### 为什么这件事做得成
 * `thumb` 是随消息走的字段，本端接它之前收发的、以及三端历史消息都没有，
 * 而协议明写**服务端不做回溯**。但那条约束管的是**服务端不回填**，
 * 不等于收端只能永远空着：**原图在本地已经有了之后，自己算一张缩略存起来就行**。
 * 下次再进这个会话（滚动、重启、切回来）就有磨砂占位了。
 *
 * ### 三条边界
 * 1. **只补本机，不上行**——那条消息在服务端的字节不该被后来的客户端改写；
 * 2. **只补已经在本地的**（下载门控判定 Ready），绝不为补一张缩略去联网
 *    ——那正是门控要挡住的事；
 * 3. **每条只试一次**（[tried]）：解不出来的（坏文件 / 非图片）再试一百次也一样，
 *    而这段代码挂在"每次消息列表变化"上，不记账就是每帧读一遍磁盘。
 */
class ThumbBackfill(
    private val repo: MessageRepository,
    private val cache: MediaCache,
) {
    private val log = IMLog.tag("IM.Thumb")

    private val tried = HashSet<String>()

    /** 一次最多补几条——补种是锦上添花，不该跟正常渲染抢 IO。 */
    private val batch = 8

    /**
     * 扫一遍当前窗口，给缺缩略且原图已在本地的图片补上。
     *
     * @return 实际补了几条（0 = 没有可补的，调用方据此不必刷新）
     */
    suspend fun run(owner: String, convId: String, messages: List<MessageEntity>): Int {
        if (owner.isEmpty()) return 0
        val todo = messages.asSequence()
            .filter { it.contentType == ContentType.IMAGE }
            .filter { it.thumb.isNullOrBlank() }
            .filter { it.convSeq > 0 }
            .filter { key(convId, it.convSeq) !in tried }
            .filter { cache.isReady(it.content) }
            .take(batch)
            .toList()
        if (todo.isEmpty()) return 0

        var done = 0
        for (m in todo) {
            tried += key(convId, m.convSeq)
            val thumb = withContext(Dispatchers.IO) {
                ThumbEncode.fromFile(cache.fileFor(m.content))
            } ?: continue
            repo.setThumb(owner, convId, m.convSeq, thumb)
            done++
        }
        if (done > 0) log.i("thumb_backfilled", "count" to done)
        return done
    }

    private fun key(convId: String, seq: Long) = "$convId#$seq"
}
