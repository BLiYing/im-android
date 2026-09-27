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
