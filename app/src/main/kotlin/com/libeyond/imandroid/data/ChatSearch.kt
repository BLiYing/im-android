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

    /**
     * 为把一条命中拉进渲染窗口，最多允许把窗口涨到多少条。
     *
     * 本端的渲染窗口是「最近 N 条」（`ChatHost` 的 `windowLimit`），要跳到一条更早的消息
     * 只能把 N 撑大到覆盖它。**必须有上限**：13 万条的会话整窗构造对象会把聊天页渲染成空白
     * （`MessageDao.observeWindow` 的注释里记着这次实测）。超过上限就如实说跳不过去，
     * 不假装跳了 —— 真正的解法是锚点开窗（iOS `jumpToConvSeq:` / im-web `locateInChat`），
     * 那是独立一档，见 `docs/UI_PARITY_IOS.md`。
     */
    const val MAX_LOCATE_WINDOW = 5000

    /**
     * 撑窗口时多给的余量（条）。
     *
     * **不能刚好撑到目标那一条**：算出来的条数是"目标恰好成为窗口里最旧的一条"，余量为 0，
     * 而窗口取的是**当下最新的 N 条**——从算完到列表刷新之间只要再落库一条消息
     * （活跃群的新消息 / sync 回填 / 自己那条 ack 落表），最新 N 条就已经不含目标，
     * 跳转当场静默落空。这一类"差一条"的错不会报，只表现成"点了没反应"。
     */
    const val LOCATE_WINDOW_MARGIN = 200

    /**
     * 撑完窗口后等多久还没滚到，就认输并如实说一句。
     *
     * 兜底而不是主路径：正常情况下窗口一刷新就命中了。但只要有任何一条路让目标进不了列表，
     * 没有这道兜底就是**唯一一个不给任何反馈的失败分支**——而这一族的纪律是"跳不了要说出来"。
     */
    const val LOCATE_TIMEOUT_MS = 3_000L

    /** 撑完窗口、等满 [LOCATE_TIMEOUT_MS] 仍没滚过去。**不说"原消息不在了"**——它明明在，只是没跳成。 */
    const val LOCATE_FAILED_NOTICE = "没能定位到这条消息，请重试"

    /** 降级提示文案 —— **与 im-web `DEGRADED_SEARCH_NOTICE` 逐字一致**（同一处境不给两副说辞）。 */
    const val DEGRADED_SEARCH_NOTICE = "离线：仅搜索已下载的消息"

    /** 目标不在本机、但本地**确有缺口**——它多半只是还没同步下来。同 im-web `NEED_NETWORK_NOTICE`。 */
    const val NEED_NETWORK_NOTICE = "该消息尚未下载，需要联网加载"

    /**
     * 目标不在本机、且本地**是齐全的**——那它就是真的没了（撤回/为所有人删除/仅为我删除都是物理删行）。
     *
     * 两档必须分开：本端「仅为我删除」与「清空聊天记录」都会物理删行，
     * 而引用块自 2026-09-09 起**只要有原消息号就可点**，于是"自己删掉的消息"是一条常见路径——
     * 对它说"需要联网加载"是把原因归错了地方，用户会去检查网络。
     */
    const val GONE_NOTICE = "原消息不在了"

    /** 命中在本地、但比渲染窗口上限还早（见 [MAX_LOCATE_WINDOW]）。 */
    const val TOO_EARLY_NOTICE = "这条命中太早，本端暂时跳不过去"

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
