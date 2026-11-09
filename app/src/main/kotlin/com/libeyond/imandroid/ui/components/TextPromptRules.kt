package com.libeyond.imandroid.ui.components

/**
 * [IMTextPrompt] 的字数判据（纯函数，单测钉住）。
 *
 * 上限可能在后台被调低（群名 / 群公告，「已有内容不截断」），于是原值本身就可能超限：
 * - 输入：**变短永远放行**——只认「≤ 上限」的话，超长旧名连删字都删不动；
 * - 确认：超限且**改过**（≠ 初始值）就不让点——否则全选粘贴一段更短但仍超限的文字会被放行；
 *   没改（= 初始值）照常可点，那就是「没改」，调用方原样提交与以前一致。
 */
object TextPromptRules {
    fun length(s: String, countByCodePoint: Boolean): Int =
        if (countByCodePoint) s.codePointCount(0, s.length) else s.length

    fun acceptsEdit(next: String, current: String, maxLen: Int, countByCodePoint: Boolean): Boolean {
        val n = length(next, countByCodePoint)
        return n <= maxLen || n < length(current, countByCodePoint)
    }

    fun canConfirm(value: String, initial: String, maxLen: Int, countByCodePoint: Boolean): Boolean =
        value == initial || length(value, countByCodePoint) <= maxLen
}
