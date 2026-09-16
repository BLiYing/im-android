package com.libeyond.imandroid.data

/**
 * 从文本里找出**第一个** URL（链接预览卡只对第一个出预览，与 iOS/Web 同）。
 *
 * 抽成纯函数是因为「哪些算 URL」这条线很容易各写各的：
 * 少认一种（如无 scheme 的 `www.`）就没卡片，多认一种（如把句末标点吃进去）
 * 就会拿一个带 `。` 的地址去请求服务端，白白吃掉每账号 60/min 的配额还必然抓空。
 */
object LinkDetect {

    // 只认 http/https 与 www. 开头；结尾把常见中英文标点剥掉。
    // 刻意**不认**裸域名（`example.com`）——中文句子里误判率太高（「等等.然后」）。
    //
    // 字符类里**必须排除中日韩字符与全角标点**：中文里 `https://a.com，然后说` 这句话
    // 逗号和后面的字都不是空格，只靠"剥末尾标点"救不回来——正则会把 `，然后说` 整段吃进地址，
    // 拿去请求必然抓空，还白吃每账号 60/min 的配额（实测第一版就是这样红的）。
    private val RE = Regex(
        """(https?://|www\.)[^\s<>"'）)】\]\u3000-\u303f\u4e00-\u9fff\uff00-\uffef]+""",
        RegexOption.IGNORE_CASE,
    )
    private const val TRAILING = ".,;:!?。，；：！？、"

    /** 文本里第一个 URL；没有则 null。返回的是**规范化后可直接请求**的地址。 */
    fun firstUrl(text: String): String? {
        val m = RE.find(text) ?: return null
        var s = m.value
        // 句末标点不属于地址：`看这个 https://a.com。` 里的句号会让服务端必然抓空
        while (s.isNotEmpty() && s.last() in TRAILING) s = s.dropLast(1)
        if (s.isBlank()) return null
        return if (s.startsWith("www.", ignoreCase = true)) "https://$s" else s
    }

    /** 展示用的站点名兜底：从 URL 里取 host（服务端没给 site_name 时用）。 */
    fun hostOf(url: String): String =
        runCatching {
            java.net.URI(url).host.orEmpty().removePrefix("www.")
        }.getOrDefault("").ifBlank {
            url.substringAfter("://").substringBefore('/').removePrefix("www.")
        }

    /**
     * 文本里**全部** http(s) 链接的区间（气泡正文高亮 + 可点，iOS `IMBubbleCell applyURLHighlight:`）。
     *
     * 正则**逐字照抄** iOS `IMURLRegexShared`（IMProgram `Common/IMMediaUtil.m`）：中部只允许 URL 合法字符
     * （汉字、中文标点、空白天然成边界），末字符再收窄一档，把句末的 `.,;:?)` 让出去。
     * 与上面 [firstUrl] 口径不同是刻意的：那条决定**要不要去服务端抓预览**（多认 `www.`），
     * 这条决定**正文里哪几个字画成链接**——iOS 两处也是分开的。
     */
    private val RANGE_RE = Regex("""https?://[-A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%]+[-A-Za-z0-9_~/#\[\]@!$&'*+=%]""")

    fun urlRanges(text: String): List<IntRange> = RANGE_RE.findAll(text).map { it.range }.toList()

    /** 正文里的一小段 `[start, end)`；落在链接上时 [link] 是**整条**链接的区间（被截断的那段点了也打开整条）。 */
    data class Piece(val start: Int, val end: Int, val link: IntRange?)

    /**
     * 把 `[start, end)` 这一段按链接切开。
     *
     * 正文先按 @提及切过段（提及优先，见 `chatBodyText`），链接只在非提及段里再切一次，
     * 所以要处理「链接跨过段边界」：截在段内，但记着整条地址。
     */
    fun splitByLinks(start: Int, end: Int, links: List<IntRange>): List<Piece> {
        if (start >= end) return emptyList()
        val out = mutableListOf<Piece>()
        var at = start
        for (r in links.sortedBy { it.first }) {
            val from = maxOf(r.first, at)
            val to = minOf(r.last + 1, end)
            if (from >= to) continue
            if (from > at) out += Piece(at, from, null)
            out += Piece(from, to, r)
            at = to
        }
        if (at < end) out += Piece(at, end, null)
        return out
    }
}
