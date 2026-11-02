package com.libeyond.imandroid.data

/**
 * 「关于我们」用的纯函数：法务页链接拼接、版本串。无 Android 依赖，可单测。
 * 设计：IMServer/docs/design/ABOUT_LEGAL_DESIGN.md；路径与后端 `internal/legal` 的 `Paths` 一致。
 */
object AboutLinks {
    const val PATH_PRIVACY = "/legal/privacy"
    const val PATH_TERMS = "/legal/terms"
    const val PATH_SUPPORT = "/support"

    private val allowedPaths = setOf(PATH_PRIVACY, PATH_TERMS, PATH_SUPPORT)

    /**
     * 法务页完整链接：服务器地址 + 路径 + `?lang=<zh-Hans|en>`（用 App 内语言偏好，不是系统语言）。
     * [host] 为空或 [path] 不在白名单返回 null（调用方提示“无法打开”，而不是拼出一个坏链接）。
     */
    fun legalUrl(host: String, useTls: Boolean, path: String, lang: ResolvedLanguage): String? {
        if (host.isBlank() || path !in allowedPaths) return null
        return "${if (useTls) "https" else "http"}://${host.trim()}$path?lang=${lang.wire}"
    }

    /** 「0.1.0 (1)」：营销版本 + 构建号；任一缺失退化为另一个，都缺为「-」。 */
    fun versionString(name: String?, code: Long?): String = when {
        !name.isNullOrBlank() && code != null -> "$name ($code)"
        !name.isNullOrBlank() -> name
        code != null -> code.toString()
        else -> "-"
    }

    /** 点「版本」行复制的整串，带平台名便于贴给客服：「Android 0.1.0 (1)」。 */
    fun clipboardText(version: String): String = "Android $version"
}
