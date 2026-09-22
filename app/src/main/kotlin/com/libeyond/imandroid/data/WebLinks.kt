package com.libeyond.imandroid.data

/**
 * 应用内浏览器（`WebViewScreen`）的地址判据。对齐 iOS `openLink:`：只收 http/https，
 * 交给 `SFSafariViewController` 在 App 内打开。
 */
object WebLinks {

    /** 页面里又要跳去哪。 */
    enum class Nav { InPage, External, Block }

    /**
     * 这些 scheme **绝不交给外部**：`javascript:` / `data:` / `blob:` 是网页自己的内容，
     * `file:` / `content:` 指向本机文件——让网页借一次跳转去读本机文件或别家应用的 provider，是 WebView 的经典漏洞。
     */
    private val NEVER_EXTERNAL = setOf("javascript", "file", "content", "data", "blob", "about")

    /**
     * 点一条链接时真正要打开的地址；null = 不在 App 内打开（iOS 对非 http/https 同样直接 return）。
     * `www.` 开头补 https——气泡里的预览卡就认它（[LinkDetect.firstUrl]），点卡片不能打不开。
     */
    fun entryUrl(raw: String): String? {
        val s = raw.trim()
        val lower = s.lowercase()
        for (scheme in listOf("https://", "http://")) {
            if (lower.startsWith(scheme)) return s.takeIf { it.length > scheme.length }
        }
        return if (lower.startsWith("www.") && s.length > "www.".length) "https://$s" else null
    }

    /**
     * 页面内部发起的一次跳转怎么处理。
     *
     * http/https 在本页里继续走；别的 scheme（`weixin://`、`tel:`、`intent://` 这类拉起应用的）
     * **只在用户真的点了一下时**才交给系统——很多站点一打开就自动重定向到自家 App 的 scheme，
     * 不拦的话「点个链接 → 被拽进另一个 App」，iOS 的 SFSafari 也会先问一句。
     */
    fun navigationFor(url: String, userGesture: Boolean): Nav {
        val scheme = url.trim().substringBefore(':', "").lowercase()
        return when {
            scheme == "http" || scheme == "https" -> Nav.InPage
            scheme.isEmpty() || scheme in NEVER_EXTERNAL -> Nav.Block
            userGesture -> Nav.External
            else -> Nav.Block
        }
    }

    fun isCleartext(url: String): Boolean = url.trim().lowercase().startsWith("http://")

    /**
     * 本站邀请链接判定（对齐 iOS `IMQRResultRouter routeInviteLinkIfOwn:`）：点开的这条链接是不是
     * 我们自己发的名片/群邀请链接——是就该走 `/qr/resolve` 原生加群/名片流程，不该跳浏览器。
     *
     * http(s) + host 与当前连接的服务器一致（或是 dev 场景的 localhost/127.0.0.1 回环——
     * `resolve` 走的是本机配置的 host，能通；真机连局域网 IP 时精确比对必失败，拦下来走站内
     * 流程好过跳一个打不开的浏览器）+ 路径命中 `/q/u/` 或 `/q/g/`（登录码 `/q/l/` 与其它路径
     * 放行走浏览器落地页承接，同 iOS）。
     */
    fun isOwnInviteLink(raw: String, host: String): Boolean {
        val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (uri.host.isNullOrEmpty()) return false
        val urlHost = if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host
        val isDevLoopback = uri.host == "localhost" || uri.host == "127.0.0.1"
        if (urlHost != host && !isDevLoopback) return false
        val path = uri.path ?: ""
        return path.startsWith("/q/u/") || path.startsWith("/q/g/")
    }

    /**
     * 主页面加载失败时的说明。未加密的 http 单独说：release 包的网络安全配置不放行明文
     * （PROTOCOL §0.1，`res/xml/network_security_config.xml`），这类页面在 App 内必然打不开，
     * 只说「检查网络」会让人对着好好的网络反复重试。
     */
    fun failureText(url: String): String =
        if (isCleartext(url)) "网页加载失败。这是未加密的 http 网页，App 内可能不允许打开，可以用浏览器打开"
        else "网页加载失败，请检查网络后重试"
}
