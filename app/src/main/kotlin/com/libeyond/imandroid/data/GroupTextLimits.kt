package com.libeyond.imandroid.data

/** 一个文本输入框的上限与计数口径（交给 `IMTextPrompt` 的 `maxLen` / `countByCodePoint`）。 */
data class TextLimit(val maxLen: Int, val countByCodePoint: Boolean)

/**
 * 群资料三个文本字段的输入上限。**一律按 Unicode 码点计**，与服务端 `len([]rune(s))` 同口径
 * （按 UTF-16 计的话一个 emoji 算 2，用户还没到上限就打不进去了）。
 * 群名 / 群公告读 server-config（后台「系统设置」S2 可改）；群简介是服务端常量 `group.MaxGroupIntroLen`。
 * 超长旧值可往短里删、改过且超限时禁用确认，由 `ui/components/TextPromptRules.kt` 统一处理。
 */
object GroupTextLimits {
    /** 与服务端 `internal/group` 的 `MaxGroupIntroLen` 同为 200（rune）。 */
    const val INTRO_MAX_LEN = 200

    fun name(limits: ServerLimits) = TextLimit(limits.groupNameMaxLen, countByCodePoint = true)

    fun intro() = TextLimit(INTRO_MAX_LEN, countByCodePoint = true)

    fun announcement(limits: ServerLimits) = TextLimit(limits.groupAnnouncementMaxLen, countByCodePoint = true)
}
