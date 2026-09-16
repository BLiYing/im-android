package com.libeyond.imandroid.data

/**
 * 「粘贴图片」的判据（对齐 iOS `appendPastedImage:` + `refreshPasteBar`）。
 *
 * ### 本端与 iOS 的机制差别，以及为什么只能这么做
 * iOS 的剪贴板直接存 `UIImage` 字节，输入框粘贴时系统就把图交过来了。
 * 安卓没有"把位图放进剪贴板"的通用做法，各家输入法与应用认的都是 `content://`
 * （本端「复制」也是照这个来的，见 `ui/CopyImageAction.kt`）。而 Compose 的 `BasicTextField`
 * 只收**纯文本**：系统粘贴一条 URI 型剪贴项时，它按 `coerceToText` 转成字符串插进正文——
 * 于是用户按了「粘贴」，输入框里出现的是一行
 * `content://com.libeyond.imandroid.fileprovider/share/IMG_xxx.jpg`，
 * 图既没进来也没处去（2026-09-16 用户报：在本 App 里粘不上）。
 *
 * 所以本端的做法是**在正文里认出这段 URI**：把它从文本里摘掉、交给上层当一张待发图
 * （输入栏上方起一排缩略图，同 iOS 的 pasteBar）。判据放这里而不是塞进
 * `onInputChange` 的 lambda 里，是因为它有两条边界必须被测试钉住：
 *
 * 1. **只认 `content://`**，不认 http / https——后者是用户真的在发一条链接消息，
 *    吞掉它就是"我粘的网址消失了"；
 * 2. **摘干净但不吃掉别的字**：URI 前后用户自己打的字要原样留着，
 *    多摘一个字符就是在用户正在写的话里挖洞。
 *
 * ### 为什么 [find] 要带上位置，而不是只回一串 URI
 * **是不是图片由调用方问 `ContentResolver.getType`**（这里判不了，也不该联网/查库）：
 * 认出来只是"像个本地 URI"，是不是 image 类型得问系统。于是必然出现**一部分认领、一部分没认领**
 * 的情况（混着粘、或者用户自己手打了一段 content:// 文本）。没认领的那些**必须原地不动**——
 * 早先的实现把它们统一拼到正文末尾，「看这个 content://weird 和这个 content://real.jpg」
 * 会变成「看这个 和这个 content://weird」，用户没删没改，字却被搬了家
 * （2026-09-16 `/code-review` 抓出，当时代码上方的注释还写着"留在原处"）。
 * 带上区间，调用方就能**只摘走自己认领的那几段**。
 */
object PasteImage {

    /** 只接本地内容 URI。`file://` 不收——Android 7+ 跨应用传 `file://` 本来就会抛。 */
    private const val SCHEME = "content://"

    /**
     * URI 的合法字符集（RFC 3986 的 unreserved + 常见 sub-delims，够覆盖 FileProvider 生成的那些）。
     * **不含空格与引号**：用它当右边界，才能在「content://…/a.jpg 这张图好看吗」里只摘走前半段。
     *
     * ⚠️ **必须逐字写 ASCII 区间，不能用 `Char.isLetterOrDigit()`**：那个方法是 Unicode 感知的，
     * 汉字在它眼里也是字母——于是「content://…/3.jpg好看吗」（中文紧跟在 URI 后面、中间没空格）
     * 会被整条当成 URI 吞掉，用户打的字当场消失。`PasteImageTest` 里那条「URI 后面紧跟中文也能断开」
     * 就是钉这个的，它第一次跑就是红的。
     */
    private fun isUriChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~:/?#[]@!$&'()*+,;=%"

    /** 正文里的一段候选 URI 及它所在的区间（`[start, end)`）。 */
    data class Found(val uri: String, val start: Int, val end: Int)

    /** 找出正文里全部候选 URI，按出现顺序。**只是"像个本地 URI"**，是不是图片得调用方问系统。 */
    fun find(text: String): List<Found> {
        if (!text.contains(SCHEME)) return emptyList()
        val out = mutableListOf<Found>()
        var i = 0
        while (i < text.length) {
            if (text.startsWith(SCHEME, i)) {
                var end = i + SCHEME.length
                while (end < text.length && isUriChar(text[end])) end++
                out += Found(text.substring(i, end), i, end)
                i = end
            } else {
                i++
            }
        }
        return out
    }

    /**
     * 从正文里**只摘掉 [drop] 这几段**，其余一字不动（没被认领的 URI 因此留在原处）。
     *
     * 摘完常留下「字  字」这种双空格，压一下；首尾空白一并去掉。
     * [drop] 为空时原样返回——没命中就绝不能碰用户正在打的字。
     */
    fun removing(text: String, drop: List<Found>): String {
        if (drop.isEmpty()) return text
        val sb = StringBuilder()
        var i = 0
        for (f in drop.sortedBy { it.start }) {
            if (f.start > i) sb.append(text, i, f.start)
            i = maxOf(i, f.end)
        }
        if (i < text.length) sb.append(text, i, text.length)
        return sb.toString().replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    /** 粘贴栏最多同时挂几张。与一次选图的上限同为 9（超出的不收，**且要说一句**，别静默）。 */
    const val MAX_PENDING = 9
}
