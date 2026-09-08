package com.libeyond.imandroid.data

/**
 * 「整会话问题」该问谁 —— 本地 / 服务端 / 本地但降级。
 *
 * **三端同一份判据**（`../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md §4.9`）：
 * 对端是 im-web `src/convQuerySource.ts` 的 `pickQuerySource`、
 * iOS `IMProgram/Common/IMConvQuerySource.h` 的 `IMPickConvQuerySource`。
 *
 * 判错**不会崩、只会答案悄悄不对**（搜不到缺口里的消息），所以抽成纯函数 + 单测钉死，
 * 而不是在每个功能点各写一遍 if。
 */
enum class QuerySource {
    /** 本地齐全 → 查本地库（秒回、离线可用）。 */
    Local,

    /** 有缺口 + 在线 → 问服务端（权威、完整）。 */
    Server,

    /** 有缺口 + 离线 → 只能给本地结果，**且必须告诉用户**只搜了已下载的部分。 */
    LocalDegraded,
}

/**
 * 会话内搜索的判据与文案（SEARCH_DESIGN §4）。
 *
 * 命中口径与后端 G4（`internal/store/sqlite_message.go` 的 `SearchConvMessages`）、
 * im-web `src/searchPredicate.ts` 的 `messageMatchesNeedle` 逐条对齐：
 * **text 的 content / 任意 caption / file_name**，大小写不敏感子串。
 * 媒体/文件的 `content` 是 URL，**不参与**——撞上 URL 片段会命中一条屏幕上看不见那几个字的消息。
 */
object ChatSearch {

    /** 服务端单页上限（后端 `conversation.MaxSearchPageLimit`）。 */
    const val SERVER_PAGE_LIMIT = 50

    /**
     * 本地一次最多取多少条命中。
     *
     * 与服务端那 50 条同一个性质：**取的是最新的那一段**，更旧的命中被截掉，
     * 所以截断时计数要补 `+` 如实告知（见 [hitLabel]），不能悄悄显示成"总共就这些"。
     */
    const val LOCAL_PAGE_LIMIT = 500

    /** 降级提示文案 —— **与 im-web `DEGRADED_SEARCH_NOTICE` 逐字一致**（同一处境不给两副说辞）。 */
    const val DEGRADED_SEARCH_NOTICE = "离线：仅搜索已下载的消息"

    /** 有词但一条都没命中。 */
    const val NO_MATCH_LABEL = "无匹配"

    /**
     * 还没输入关键词时的提示。
     *
     * **不能在这里显示「无匹配」**——什么都没搜就说"没有匹配"，说的是一件没发生过的事。
     * im-web 是干脆把整条导航条藏掉；本端那条底栏是接替输入栏的位置，藏了会跳，所以改成给一句提示。
     */
    const val EMPTY_QUERY_LABEL = "输入关键词搜索本会话"

    /**
     * @param complete 本地这个会话齐不齐（见 [isLocalComplete]）
     * @param online   现在能不能上网
     */
    fun pickSource(complete: Boolean, online: Boolean): QuerySource = when {
        // 齐全时联不联网都走本地——没有理由为一个完整的本地库去问服务端
        complete -> QuerySource.Local
        online -> QuerySource.Server
        else -> QuerySource.LocalDegraded
    }

    /**
     * 本地这个会话是不是齐全的。
     *
     * 本端的同步游标是**从 0 开始连续推进**的（`SyncCursorRule`：本页全部落库成功才推进），
     * 所以本地覆盖的一定是连续区间 `[1, syncedConvSeq]`，会话上界是 `lastConvSeq`。
     * 判据因此简化成一句「游标追上上界了没有」——不必像 im-web 那样维护区间清单，
     * **但要一致的是「有没有缺口」这个不变式，不是清单那种手段**（`SYMMETRY.md` 那条）。
     *
     * **「清空聊天记录」之后仍然算齐全**，尽管本地一条都没有了——这是刻意的，且与 im-web 一致
     * （它的 `localStore.clearMessages` 同样只删消息行、不动区间清单）。理由是清空的语义就是
     * "**本机**不留了"：这时跑去问服务端，会把用户刚亲手清掉的消息整整齐齐搜回来，
     * 点过去还只能得到一句"这条不在本机"。如实回「无匹配」才是这个动作该有的结果。
     * 本端保留同步游标也是同一个理由（`MessageRepository.clearConversation`）。
     */
    fun isLocalComplete(syncedConvSeq: Long, lastConvSeq: Long): Boolean =
        lastConvSeq <= 0L || syncedConvSeq >= lastConvSeq // 空会话：本地就是全部

    /**
     * `LIKE` 通配符转义 —— **镜像后端 `internal/store/sqlite_message.go` 的 `escapeLike`**。
     *
     * 不转的话搜「50%」会变成"以 50 开头的任意内容"（后端那侧有一条测试专门钉这个），
     * 而搜「a_b」会把「axb」也算命中。顺序要紧：反斜杠必须先转，否则会把后面补上的转义符再转一遍。
     */
    fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * 一条消息是否命中关键词。`needle` 须已 trim + lowercase。
     *
     * 与 DAO 里那条 SQL 是**同一个判据的两次表达**。**实际判据是 SQL**（它是第一道、且更窄：
     * SQLite 的 `LIKE` 只对 ASCII 大小写不敏感，与后端 Go 那侧同款限制）；这个纯函数是它的
     * **可测镜像 + 更严的复核**，对 SQL 取回的候选再过一遍。留两份不是冗余——
     * SQL 判据没法单测（本仓没有 Robolectric / instrumented 测试），而判据漂了的表现是
     * "搜不到"而不是报错，只有这一层能把它钉住。**改 SQL 一定要同步改这里**，反之亦然。
     */
    fun matches(
        contentType: String,
        content: String,
        caption: String?,
        fileName: String?,
        needle: String,
    ): Boolean {
        if (needle.isEmpty()) return false
        val isText = contentType.isEmpty() || contentType == "text"
        if (isText && content.lowercase().contains(needle)) return true
        if (!caption.isNullOrEmpty() && caption.lowercase().contains(needle)) return true
        if (!fileName.isNullOrEmpty() && fileName.lowercase().contains(needle)) return true
        return false
    }

    /**
     * `text` 里 `needle` 出现的所有位置（大小写不敏感，不重叠）。命中词高亮用。
     *
     * 抽出来是为了能单测——高亮画错的表现是"高亮飘在别的字上"，
     * 在 Compose 里没法自动验（本仓没有 instrumented / Robolectric 测试）。
     * `needle` 由调用方 trim；空词回空表（不高亮），不是"整段高亮"。
     */
    fun matchRanges(text: String, needle: String): List<IntRange> {
        val n = needle.trim()
        if (n.isEmpty() || text.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i <= text.length - n.length) {
            val at = text.indexOf(n, i, ignoreCase = true)
            if (at < 0) break
            out += at until (at + n.length)
            i = at + n.length
        }
        return out
    }

    /**
     * 底部导航条的计数文案。
     *
     * `truncated` = 命中集被单页上限截断（真实命中更多）→ 补 `+` 如实告知。
     * 悄悄截成 50 条还写「/ 50」会让人以为大群里就只有这些命中。
     */
    fun hitLabel(idx: Int, count: Int, truncated: Boolean, hasQuery: Boolean = true): String = when {
        !hasQuery -> EMPTY_QUERY_LABEL
        count <= 0 -> NO_MATCH_LABEL
        else -> "${clampHitIndex(idx, count) + 1} / $count${if (truncated) "+" else ""}"
    }

    /** 默认停在**最新一条命中**（贴合"找刚才那条"的直觉，同 iOS/Web）。命中集按 conv_seq 升序。 */
    fun defaultHitIndex(count: Int): Int = if (count <= 0) 0 else count - 1

    fun clampHitIndex(idx: Int, count: Int): Int =
        if (count <= 0) 0 else idx.coerceIn(0, count - 1)
}

/** 一条命中：跳转与计数只需要这两个字段（同 im-web `useChatSearch` 里的 `{convSeq,timestamp}`）。 */
data class SearchHit(val convSeq: Long, val timestamp: Long)
