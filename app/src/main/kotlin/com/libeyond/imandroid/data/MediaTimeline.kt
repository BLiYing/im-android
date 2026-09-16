package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.protocol.ContentType

/**
 * 媒体查看器**左右翻页**的判据（CLIENT_PARITY 任务3；对端 iOS `IMMediaTimeline.h` +
 * `IMMediaPagerViewController`、im-web `src/album.ts` 的 `msgKey` + `App.tsx` 的 `goViewer`）。
 *
 * ### 三端共守的不变式
 * 1. **翻页序列是「整个会话的媒体时间线」**（image/video 混排、未撤回、内容非空），
 *    不是"当前屏幕上那几张"。iOS 的注释把理由写死了：拿渲染窗口当序列，用户看到的是
 *    「这张图前后没有别的图了」——而那是假的，且表现为一次正常的滑到头，没有任何报错。
 * 2. **身份用 `conv_seq`**（服务端分配、会话内唯一），不能用 clientMsgId：入站消息该字段恒空，
 *    拿它定位会让所有入站消息都命中"第一条空值"（im-web 真出过：点最后一张却定位到第一张）。
 * 3. **越界即停**，不绕回第一张。
 *
 * ### 本端与另两端刻意不同的一点
 * iOS 一次性查本地库取全量，im-web 只在内存已加载的范围里翻；**本端翻到最旧一端时会向服务端
 * 续拉更早的一页**（`GET /conversations/{id}/media`，OFFLINE_BACKLOG_DESIGN §4.9 第 5 项）。
 * 因为本端的本地库天然有缺口（离线积压 C1~C6 整套未做，§4.11.1），不续拉就会在缺口边缘
 * 静默停住。**续拉只能往更旧**：服务端游标是 `conv_seq < cursor` 的倒序分页
 * （`internal/store/sqlite_message.go` 的 `convSearchDESC`），没有"往更新翻"的方向。
 */
object MediaTimeline {

    /** 离最旧那一端还剩几张就去续拉。留一张的余量，避免用户滑到最后一张才开始等网络。 */
    const val PREFETCH_MARGIN = 2

    /**
     * 这一条能不能进翻页序列。
     *
     * `convSeq <= 0`（还没 ack）**不进**：翻页序列要按 conv_seq 定位，没有序号的那条既定位不了、
     * 续拉时也无从比较新旧。它仍然能被单独打开——查看器不要求序列非空。
     */
    fun viewable(contentType: String, content: String, convSeq: Long, recalled: Boolean): Boolean =
        convSeq > 0 &&
            !recalled &&
            content.isNotBlank() &&
            (contentType == ContentType.IMAGE || contentType == ContentType.VIDEO)

    /**
     * 在序列里定位一条消息。找不到回 `-1`（调用方据此退化成"只看这一条"）。
     *
     * `convSeq <= 0` 一律回 -1：见 [viewable]，没有序号的那条不在序列里。
     */
    fun indexOf(items: List<ViewerMedia>, convSeq: Long): Int =
        if (convSeq <= 0L) -1 else items.indexOfFirst { it.convSeq == convSeq }

    // 「越界即停、不绕回」这条不变式本端**由 `HorizontalPager` 自己保证**（pageCount 定死），
    // 所以这里不再摆一个 step()——抽一个没有调用点的纯函数出来，就是 SYMMETRY 里记的
    // im-web `moreLocalBelow` 长成死代码那条：看着像权威判据，实际没人读。

    /**
     * 现在该不该去拉更早的一页。
     *
     * @param loading 在途守卫。**不守的话滚到边缘会连续触发**，同一页被追加两次——
     *   本工程在成员分页、归档分页上各栽过一次（IMServer docs/SYMMETRY.md 开头那张表第一行）。
     */
    fun wantsOlder(index: Int, count: Int, hasMore: Boolean, loading: Boolean): Boolean =
        hasMore && !loading && count > 0 && index <= PREFETCH_MARGIN

    /**
     * 把更早的一页并到序列**前面**，并回报真正新增了几条。
     *
     * 判据与会话内搜索翻页同源（iOS `IMChatSearchPrependOlderHits` / im-web `searchPaging.ts`）：
     * - **只收比当前最旧还旧的**：防重复页 / 游标回退把已有项再塞一遍；
     * - **按 conv_seq 去重**；
     * - 调用方必须把当前下标 **+= added**——序列在前面变长了，不挪下标的话用户会当场跳到另一张图上。
     */
    fun prependOlder(current: List<ViewerMedia>, page: List<ViewerMedia>): PrependResult {
        val oldest = current.firstOrNull()?.convSeq
        val seen = current.mapTo(HashSet()) { it.convSeq }
        val older = page
            .filter { it.convSeq > 0 && (oldest == null || it.convSeq < oldest) && seen.add(it.convSeq) }
            .sortedBy { it.convSeq }
        return PrependResult(older + current, older.size)
    }

    data class PrependResult(val items: List<ViewerMedia>, val added: Int)
}

/**
 * 翻页序列里的一项。
 *
 * **两个入口的数据形状不同**——聊天页手上是本地 [MessageEntity]，详情页归档手上是服务端
 * [ConvMediaItem]——查看器只认这一个最小集，免得为了调它去伪造一个另一侧的实体
 * （`MediaViewerScreen` 的注释里记着同一条理由）。
 */
data class ViewerMedia(
    val convSeq: Long,
    val contentType: String,
    val content: String,
    val poster: String = "",
    /**
     * 谁发的 + 什么时候。**「更多」里的判据要用**：能不能「为所有人删除」看的是"这条是不是我发的"
     * （[MessageActions]/[ArchiveActions] 的 `mine`）。少带这两个字段的话，翻到服务端续拉回来的
     * 那几张时判据会**静默降级**成"都不是我发的"——菜单少一档，而且不报错。
     */
    val sender: String = "",
    val timestamp: Long = 0,
    /** 原件字节数（`file_size`）。「查看原视频」胶囊要显示它，下载器也拿它当进度分母。 */
    val sizeBytes: Long = 0,
) {
    val isVideo: Boolean get() = contentType == ContentType.VIDEO
}

/** 本地消息 → 序列项。不可进序列的返回 null（撤回 / 未确认 / 非图视频）。 */
fun MessageEntity.toViewerMedia(): ViewerMedia? {
    val recalled = (recalledAt ?: 0L) > 0L || (deletedAt ?: 0L) > 0L
    if (!MediaTimeline.viewable(contentType, content, convSeq, recalled)) return null
    return ViewerMedia(convSeq, contentType, content, poster.orEmpty(), sender, timestamp, fileSize ?: 0L)
}

/**
 * 服务端归档项 → 序列项。
 *
 * 撤回 / 为所有人删除 / 「仅为我删除」 / 入群下界**服务端已经滤掉了**，这里不再判一遍——
 * 判据分叉的表现是"归档里翻得到、聊天页看不到的图"（`ConversationsApi.media` 的注释同）。
 */
fun ConvMediaItem.toViewerMedia(): ViewerMedia? {
    if (!MediaTimeline.viewable(contentType, content, convSeq, recalled = false)) return null
    return ViewerMedia(convSeq, contentType, content, poster, sender, timestamp, fileSize)
}
