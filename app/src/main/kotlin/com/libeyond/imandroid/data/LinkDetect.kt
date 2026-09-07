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
}
