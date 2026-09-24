package com.libeyond.imandroid.data

/**
 * 建群默认群名与长度规则——与 iOS `Common/IMGroupNameDefault.m` 逐字同规则（Web 侧另见 `src/groupName.ts`）。
 */
object GroupNameDefault {
    /** 群名上限（rune 数），与服务端 textguard 及 iOS `IMMaxGroupNameLength` 同值。 */
    const val MAX_LENGTH = 30

    /**
     * rune 数：Unicode 码点数，与服务端 Go 的 `len([]rune(s))` 同口径（代理对/emoji 算 1）。
     * `String.codePointCount` 本就是这个语义，不必像 iOS 那样手扫 UTF-16 代理对。
     */
    fun runeLength(s: String): Int = s.codePointCount(0, s.length)

    /** 截到前 [n] 个 rune；`n<=0` 或空串回空串，本就不超长原样返回。 */
    fun truncateToRunes(s: String, n: Int): String {
        if (s.isEmpty() || n <= 0) return ""
        if (runeLength(s) <= n) return s
        val end = s.offsetByCodePoints(0, n)
        return s.substring(0, end)
    }

    /** 公开显示名：备注不算（会广播给全群），昵称 → @username → 内部 ID 原样兜底。 */
    fun publicUserName(nickname: String?, username: String?, userId: String?): String {
        val nick = nickname?.trim().orEmpty()
        if (nick.isNotEmpty()) return nick
        val uname = username?.trim().orEmpty()
        if (uname.isNotEmpty()) return "@$uname"
        return userId?.trim().orEmpty()
    }

    /**
     * 预填群名：按 [names] 顺序拼 "、"，超过 [maxLen] 就到此为止；
     * 一个都放不下（首名本身超长）时硬截首名。
     */
    fun defaultName(names: List<String>, maxLen: Int = MAX_LENGTH): String {
        val parts = mutableListOf<String>()
        for (raw in names) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            val candidate = (parts + name).joinToString("、")
            if (runeLength(candidate) <= maxLen) {
                parts.add(name)
                continue
            }
            if (parts.isEmpty()) return truncateToRunes(name, maxLen)
            break
        }
        return parts.joinToString("、")
    }
}
