package com.libeyond.imandroid.data

import java.io.File
import java.security.MessageDigest

/**
 * 已下载媒体的本地落盘（M4-7）。
 *
 * **文件名用 URL 的 SHA-1**，不用原始文件名：同名不同内容的文件（`IMG_0001.jpg` 满地都是）
 * 会互相覆盖，而带路径的服务端相对地址里有 `/` 不能直接当文件名。
 *
 * **没有额外索引表**——「在不在本地」就是「文件在不在」。多一张表就多一处能与磁盘不一致
 * 的状态（删了文件表还说在、或反过来），而这个判断每次渲染都要做，必须绝对可靠。
 */
class MediaCache(private val root: File) {

    init {
        runCatching { root.mkdirs() }
    }

    /** 某个媒体地址在本地的落点。`url` 用**相对地址**（换 host 不该让缓存全失效）。 */
    fun fileFor(url: String, isVideo: Boolean = false): File {
        val ext = extensionOf(url) ?: if (isVideo) "mp4" else "bin"
        return File(root, sha1(url) + "." + ext)
    }

    fun isReady(url: String, isVideo: Boolean = false): Boolean =
        url.isNotBlank() && fileFor(url, isVideo).let { it.isFile && it.length() > 0 }

    /** 下载中写的是 `.part`，**下完才改名**——半截文件被当成"已就绪"是最难查的一类。 */
    fun partFor(url: String, isVideo: Boolean = false): File =
        File(fileFor(url, isVideo).absolutePath + ".part")

    fun delete(url: String, isVideo: Boolean = false) {
        runCatching { fileFor(url, isVideo).delete() }
        runCatching { partFor(url, isVideo).delete() }
    }

    /** 缓存总字节（设置页「清理」要显示它）。 */
    fun totalBytes(): Long =
        runCatching { root.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    fun clear() {
        runCatching { root.listFiles()?.forEach { it.delete() } }
    }

    companion object {
        /** 取扩展名：去掉 `?query`/`#frag`，只认最后一段里的 `.xxx`（1~5 位字母数字）。 */
        fun extensionOf(url: String): String? {
            val clean = url.substringBefore('?').substringBefore('#')
            val name = clean.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            if (dot < 0 || dot == name.length - 1) return null
            val ext = name.substring(dot + 1)
            return ext.takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } }?.lowercase()
        }

        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
