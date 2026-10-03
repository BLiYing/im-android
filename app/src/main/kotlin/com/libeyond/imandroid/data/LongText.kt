package com.libeyond.imandroid.data

/** 长文本分档（对齐 iOS `IMBubbleCell textTierForContent:` 与 Web `longtext.ts`；**三端阈值必须一致**）。 */
enum class TextTier { Short, Long, Huge }

object LongText {
    const val LONG_CHARS = 300
    const val LONG_LINES = 10
    const val HUGE_CHARS = 2000
    const val HUGE_LINES = 60

    /** 折叠态：最多 8 行、400 字。 */
    const val COLLAPSED_LINES = 8
    const val COLLAPSED_CHARS = 400

    /** 摘要卡预览：最多 3 行、120 字，换行折成空格。 */
    const val HUGE_PREVIEW_LINES = 3
    const val HUGE_PREVIEW_CHARS = 120

    /** 字数按 **Unicode 码点**数（emoji 等增补字符算一个），不是 UTF-16 长度。 */
    fun charCount(s: String): Int = s.codePointCount(0, s.length)

    /** 硬换行数 + 1，**不 trim**（末尾换行也多算一行，三端同）。 */
    fun lineCount(s: String): Int = s.count { it == '\n' } + 1

    /** 整条消息就是一个链接：恒 Short（交给链接打开逻辑，不折叠）。 */
    fun isUrlOnly(s: String): Boolean {
        val t = s.trim()
        return t.isNotEmpty() && LinkDetect.firstUrl(t) == t
    }

    fun tierOf(content: String): TextTier {
        if (content.isEmpty() || isUrlOnly(content)) return TextTier.Short
        val chars = charCount(content)
        val lines = lineCount(content)
        return when {
            chars >= HUGE_CHARS || lines >= HUGE_LINES -> TextTier.Huge
            chars >= LONG_CHARS || lines >= LONG_LINES -> TextTier.Long
            else -> TextTier.Short
        }
    }

    /** 取前 [maxLines] 个硬行，再截到 [maxChars] 个**码点**（不劈开代理对）。 */
    fun truncate(s: String, maxLines: Int, maxChars: Int): String {
        var end = s.length
        var seen = 0
        for (i in s.indices) {
            if (s[i] == '\n') { seen++; if (seen >= maxLines) { end = i; break } }
        }
        val byLines = s.substring(0, end)
        if (charCount(byLines) <= maxChars) return byLines
        return byLines.substring(0, byLines.offsetByCodePoints(0, maxChars))
    }

    fun collapsed(s: String): String = truncate(s, COLLAPSED_LINES, COLLAPSED_CHARS)

    /** 摘要卡预览：前 3 行 / 120 字，`\n` 换成空格。 */
    fun hugePreview(s: String): String = truncate(s, HUGE_PREVIEW_LINES, HUGE_PREVIEW_CHARS).replace('\n', ' ')

    /** 「8,400」——千分位逗号（Locale.ROOT，别随系统地区变成别的分隔符）。 */
    fun countLabel(n: Int): String = String.format(java.util.Locale.ROOT, "%,d", n)
}
