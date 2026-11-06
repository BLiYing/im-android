package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * 后台下发的「关于我们」四个链接的**原始字段值**（取值语义见 [AboutLinks.resolve]）。
 * 法务三项留空（老服务端 / 异常）退回内置路径；在线客服留空 = 隐藏入口。
 */
data class AboutLinkValues(
    val privacy: String,
    val terms: String,
    val support: String,
    val customerService: String,
) {
    companion object {
        /** 拉不到时的兜底：法务页走内置路径（与改造前一致），客服入口隐藏。 */
        val FALLBACK = AboutLinkValues(AboutLinks.PATH_PRIVACY, AboutLinks.PATH_TERMS, AboutLinks.PATH_SUPPORT, "")

        fun of(privacy: String?, terms: String?, support: String?, customerService: String?) = AboutLinkValues(
            privacy = privacy?.trim().orEmpty().ifEmpty { AboutLinks.PATH_PRIVACY },
            terms = terms?.trim().orEmpty().ifEmpty { AboutLinks.PATH_TERMS },
            support = support?.trim().orEmpty().ifEmpty { AboutLinks.PATH_SUPPORT },
            customerService = customerService?.trim().orEmpty(),
        )
    }
}

/**
 * 链接的内存缓存：进页面先用上次的值立刻渲染，再后台刷新（后台改完下次进页就生效，服务端不缓存）。
 * 键 = 服务器地址 + 是否带登录态（匿名拿不到客服地址，两种结果不能互相覆盖）。
 */
object AboutLinksStore {
    private val log = IMLog.tag("IM.About")
    private val cache = ConcurrentHashMap<String, AboutLinkValues>()

    fun key(host: String, authenticated: Boolean) = "${host.trim()}|$authenticated"

    fun cached(key: String): AboutLinkValues? = cache[key]

    /** 拉取并更新缓存；失败不报错：返回旧缓存，没有旧缓存就退回 [AboutLinkValues.FALLBACK]。 */
    suspend fun load(key: String, fetch: suspend () -> AboutLinkValues): AboutLinkValues =
        try {
            fetch().also { cache[key] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("about_links_failed", "err" to e.javaClass.simpleName)
            cache[key] ?: AboutLinkValues.FALLBACK
        }

    /**
     * [url] 是否落在某个已缓存的在线客服地址的同源上（决定要不要给该页的 WebView 追加 App 身份 UA）。
     * 客服字段若是 "/" 开头的本服务器相对路径，源取缓存键里的 host + [useTls]。
     */
    fun isCustomerServiceUrl(url: String, useTls: Boolean): Boolean =
        cache.entries.any { (k, v) ->
            val cs = v.customerService
            when {
                cs.isEmpty() -> false
                cs.startsWith("/") -> AboutLinks.sameOrigin("${if (useTls) "https" else "http"}://${k.substringBeforeLast('|')}", url)
                else -> AboutLinks.sameOrigin(cs, url)
            }
        }
}
