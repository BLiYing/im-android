package com.libeyond.imandroid.data

/**
 * 列表页搜索（转发选择页等）的匹配口径，对齐 iOS `IMListSearch`（`IMListSearchNormalizedQuery` /
 * `IMListSearchMatches`）与 Web `listSearch.ts`：去首尾空白、大小写不敏感的子串匹配，
 * 任一字段命中即算；没在搜（空查询）= 全部命中。
 *
 * 抽出来是为了**别让每个选择页各写一份**：CLIENT_PARITY「列表页搜索」那一行记的就是
 * 本端几个选人页各自在等「列表搜索收敛」，一直没人接。
 */
object ListSearch {

    fun normalizedQuery(raw: String): String = raw.trim()

    fun matches(query: String, fields: List<String>): Boolean {
        val q = normalizedQuery(query)
        if (q.isEmpty()) return true
        return fields.any { it.isNotEmpty() && it.contains(q, ignoreCase = true) }
    }
}
