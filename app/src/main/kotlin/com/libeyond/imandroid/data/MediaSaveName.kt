package com.libeyond.imandroid.data

/**
 * 存相册时的文件名与 MIME 推导（纯函数，不碰 Android）。
 *
 * 单独拎出来是因为这里每一条都踩过或差点踩：URL 上的 query 会原样进文件名、
 * 服务端的 `req-<id>__` 前缀会让相册里全是乱码名、MIME 与集合不匹配会在相册里
 * 留下一条**打不开的条目**（不报错，就是打不开）。
 */
object MediaSaveName {

    /** 文件名上限。MediaStore 本身能吃更长，但相册 UI 里超长名会挤掉整行。 */
    private const val MAX = 80

    /**
     * 从媒体 URL 推出存进相册的文件名。
     *
     * - 先砍掉 `?query` 与 `#fragment`——带 `?` 的 `DISPLAY_NAME` 在部分 ROM 上直接插入失败；
     * - 服务端命名是 `req-<id>__<原始名>`，取双下划线之后那截（与 [MediaUrl.displayFileName] 同口径）；
     * - 清掉路径分隔与前导点：`DISPLAY_NAME` 里带 `/` 会被当成子目录，前导点是隐藏文件；
     * - 认不出扩展名就按类型补 `.jpg` / `.mp4`，**不能不带扩展名**——相册按扩展名认类型。
     */
    fun fileNameFor(url: String, isVideo: Boolean, nowMs: Long): String {
        val ext = if (isVideo) "mp4" else "jpg"
        val bare = url.substringBefore('?').substringBefore('#')
        val last = bare.substringAfterLast('/')
        val human = last.substringAfter("__", last)
        val safe = sanitize(human)
        if (safe.isBlank()) return "IM_$nowMs.$ext"
        return if (hasExtension(safe)) safe else "$safe.$ext"
    }

    /**
     * MIME。**大类由 [isVideo] 说了算，扩展名只在大类内部挑细分**——
     * 大类必须与写入的 MediaStore 集合（Images / Video）一致，
     * 往 Images 里塞一条 `video/mp4` 不会报错，只是相册里多一条点不开的东西。
     */
    fun mimeFor(fileName: String, isVideo: Boolean): String {
        val e = fileName.substringAfterLast('.', "").lowercase()
        return if (isVideo) {
            when (e) {
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                "3gp" -> "video/3gpp"
                "mkv" -> "video/x-matroska"
                else -> "video/mp4"
            }
        } else {
            when (e) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic", "heif" -> "image/heic"
                else -> "image/jpeg"
            }
        }
    }

    /** 只留字母数字与 `. _ -` 和空格；中日韩字符 `isLetterOrDigit` 认，会保留。 */
    private fun sanitize(name: String): String =
        name.filter { it.isLetterOrDigit() || it in "._- " }.trim('.', ' ').take(MAX)

    private fun hasExtension(name: String): Boolean {
        val e = name.substringAfterLast('.', "")
        return e.length in 1..5 && e.all { it.isLetterOrDigit() }
    }
}
