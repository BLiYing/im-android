package com.libeyond.imandroid.voice

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 极薄的键值存储抽象——单测注入内存实现，App 里用 SharedPreferences。 */
interface VoiceKv {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class PrefsVoiceKv(context: Context) : VoiceKv {
    private val prefs: SharedPreferences = context.getSharedPreferences("im_voice", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}

/**
 * 本机「已播放」集合（设计 §7）：**只做本地**，不上行、不跨端。判据是「点了」不是「听完了」。
 *
 * 键 = per-uid + per-conv（iOS `IMVoicePlayerPlayedKey`）：per-uid 防账号 A 播过的被 B 当已播。
 * 落盘保序、封顶 [CAP] 条按 FIFO 剔除；滚动热路径只查内存镜像（iOS 2026-08-27 修过每格读盘的卡顿）。
 */
class VoicePlayedStore(private val kv: VoiceKv) {
    private val cache = HashMap<String, LinkedHashSet<String>>()

    /** 每次 [markPlayed] 自增——Compose 订阅它来刷新红点（集合本身不是可观察的）。 */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun key(owner: String, convId: String) = "played.${owner.ifBlank { "anon" }}.${convId.ifBlank { "na" }}"

    @Synchronized
    private fun setFor(key: String): LinkedHashSet<String> = cache.getOrPut(key) {
        LinkedHashSet(kv.get(key)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty())
    }

    @Synchronized
    fun hasPlayed(owner: String, convId: String, id: String): Boolean = id in setFor(key(owner, convId))

    @Synchronized
    fun markPlayed(owner: String, convId: String, id: String) {
        val k = key(owner, convId)
        val set = setFor(k)
        if (!set.add(id)) return
        while (set.size > CAP) set.remove(set.first())
        kv.put(k, set.joinToString("\n"))
        _version.value++
    }

    companion object {
        const val CAP = 5000
    }
}

/** 会话级倍速记忆（§6.2「会话级记忆」，iOS `rateForConvID:`）。 */
class VoiceRateStore(private val kv: VoiceKv) {
    fun rateFor(convId: String): Float =
        kv.get("rate.${convId.ifBlank { "na" }}")?.toFloatOrNull()?.let(VoiceRules::normalizeRate) ?: 1f

    fun setRate(convId: String, rate: Float) {
        kv.put("rate.${convId.ifBlank { "na" }}", VoiceRules.normalizeRate(rate).toString())
    }
}

/**
 * 语音「转文字」的本地持久态（VOICE_TRANSCRIBE_DESIGN.md），对齐 iOS `IMVoiceTranscriber`
 * 的两条不变式（**不是代码形状，是必须跨端一致的判据**，SYMMETRY 的精神）：
 *
 * 1. **文本缓存按音频内容（`content`）去重，不按消息坐标**——同一段语音被转发/收藏出多条，
 *    只该转写一次；服务端 `im_voice_transcript` 主键同样是音频路径，端上跟服务端同口径才能
 *    「缓存命中零延迟展开」。
 * 2. **「取消转文字」的折叠态要落盘**——只在内存里的话，杀进程重进会话，文本缓存还在、
 *    折叠态却丢了，用户明明点过「取消」的面板又冒出来（iOS 2026-08-26 实测过的坑）。
 *
 * 两张表都是**内容寻址、永久缓存**，量随使用线性增长，FIFO 封顶防止无限膨胀
 * （文本 [CACHE_MAX] 条，折叠名单 [COLLAPSED_MAX] 条——折叠是"这条不想看"的一次性偏好，
 * 名单本身不需要很大）。
 */
class VoiceTranscriptStore(private val kv: VoiceKv) {
    private val textCache = HashMap<String, String>()
    private val collapsed = LinkedHashSet<String>().apply {
        kv.get(COLLAPSED_KEY)?.split('\n')?.filter { it.isNotEmpty() }?.let(::addAll)
    }
    private val cacheOrder = LinkedHashSet<String>().apply {
        kv.get(ORDER_KEY)?.split('\n')?.filter { it.isNotEmpty() }?.let(::addAll)
    }

    /** 命中缓存直接回文本；没转过 / 已被 FIFO 挤掉都是 null（调用方据此决定要不要发起识别）。 */
    @Synchronized
    fun cachedText(content: String): String? {
        if (content.isBlank()) return null
        textCache[content]?.let { return it.ifEmpty { null } }
        val t = kv.get(textKey(content)).orEmpty()
        textCache[content] = t
        return t.ifEmpty { null }
    }

    @Synchronized
    fun putText(content: String, text: String) {
        if (content.isBlank() || text.isBlank()) return
        textCache[content] = text
        kv.put(textKey(content), text)
        if (!cacheOrder.add(content)) return
        while (cacheOrder.size > CACHE_MAX) {
            val oldest = cacheOrder.first()
            cacheOrder.remove(oldest)
            textCache.remove(oldest)
            kv.put(textKey(oldest), "") // VoiceKv 只有 get/put 没有 delete：空串等效清空（cachedText 把空串当无值）
        }
        kv.put(ORDER_KEY, cacheOrder.joinToString("\n"))
    }

    /** `mid` 是否被本地折叠过（[com.libeyond.imandroid.voice.VoiceRules.playableId]）。 */
    @Synchronized
    fun isCollapsed(mid: String): Boolean = mid.isNotBlank() && mid in collapsed

    /** 「取消转文字」：先移再加，保证重复折叠时刷到队尾（FIFO 淘汰按最久未折叠）。 */
    @Synchronized
    fun collapse(mid: String) {
        if (mid.isBlank()) return
        collapsed.remove(mid)
        collapsed.add(mid)
        while (collapsed.size > COLLAPSED_MAX) collapsed.remove(collapsed.first())
        kv.put(COLLAPSED_KEY, collapsed.joinToString("\n"))
    }

    /** 取消折叠（缓存命中时点「转文字」= 只需重新展开）。 */
    @Synchronized
    fun expand(mid: String) {
        if (collapsed.remove(mid)) kv.put(COLLAPSED_KEY, collapsed.joinToString("\n"))
    }

    private fun textKey(content: String) = "transcript.$content"

    companion object {
        const val CACHE_MAX = 2000
        const val COLLAPSED_MAX = 500
        private const val COLLAPSED_KEY = "transcript_collapsed"
        private const val ORDER_KEY = "transcript_order"
    }
}
