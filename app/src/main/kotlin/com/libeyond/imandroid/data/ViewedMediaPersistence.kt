package com.libeyond.imandroid.data

import android.content.Context

/**
 * 「查看器里看过」登记的落盘（对齐 Web `optedIn.ts` / iOS `IMViewedMediaStore`）。
 *
 * 按账号隔离；条数封顶由调用方（[MediaDownloader]）负责，这里只管读写。
 * 抽成接口是为了 JVM 单测不碰 Android 的 SharedPreferences。
 */
interface ViewedMediaPersistence {
    /** 该账号已登记的地址，**旧→新**。空账号 / 没有记录回空表。 */
    fun load(owner: String): List<String>

    fun save(owner: String, urls: List<String>)

    fun clear(owner: String)
}

/** 生产实现：一个账号一个键，地址用换行拼（媒体相对地址不含换行）。 */
class PrefsViewedMediaPersistence(context: Context) : ViewedMediaPersistence {
    private val prefs = context.applicationContext.getSharedPreferences("im_viewed_media", Context.MODE_PRIVATE)

    private fun key(owner: String) = "viewed.$owner"

    override fun load(owner: String): List<String> {
        if (owner.isEmpty()) return emptyList()
        return runCatching { prefs.getString(key(owner), null) }.getOrNull()
            ?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
    }

    override fun save(owner: String, urls: List<String>) {
        if (owner.isEmpty()) return
        runCatching { prefs.edit().putString(key(owner), urls.joinToString("\n")).apply() }
    }

    override fun clear(owner: String) {
        if (owner.isEmpty()) return
        runCatching { prefs.edit().remove(key(owner)).apply() }
    }
}
