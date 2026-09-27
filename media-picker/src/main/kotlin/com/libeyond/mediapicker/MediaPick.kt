package com.libeyond.mediapicker

/**
 * 选择器的纯逻辑（不碰 Android 类型，可单测）。
 *
 * **为什么有这个自建模块**——iOS 用系统 `PHPickerViewController`（进程外、免相册权限），
 * 本端本来照抄了这个思路，用 androidx `PickMultipleVisualMedia`。但 androidx 那层是
 * **按能力回退**的：
 *
 * | 系统 | 实际落到哪 |
 * |---|---|
 * | Android 13+ (API 33) | 系统 Photo Picker ✅ |
 * | Android 11~12 且 `build.version.extensions.r >= 2` | Play 服务回填的 Photo Picker ✅ |
 * | 其余 | `ACTION_OPEN_DOCUMENT`（DocumentsUI 文件浏览器）❌ |
 *
 * 项目最低支持 **Android 11**，而 extension 随**系统更新**下发、不随 Play 服务走——
 * 2026-09-07 在 Pixel 2 XL（Android 11 / Play 服务 26.32.68 是新的）实测
 * `build.version.extensions.r = 0`、`ACTION_PICK_IMAGES` 无任何 handler，落到了 DocumentsUI。
 * 没有宫格、没有编号多选、要先在文件夹里翻——这不是能接受的发图体验。
 *
 * 详见 IMServer `docs/UI_SPEC.md` §6.4（登记为刻意的平台差异）。
 */
object MediaPick {

    /**
     * 单次最多选几个。与 iOS `IMMediaPicker` 的聊天 limit、app 侧 `AlbumLayout.MAX` 同值 9。
     * **app 侧有一条测试钉死这两个常量相等**（`AlbumLimitParityTest`）——本模块不认识
     * `AlbumLayout`（依赖单向），所以只能用测试守住，不能用引用守住。
     */
    const val LIMIT = 9

    /** 「全部」这个伪相册的桶 ID（不是 MediaStore 的真实 bucket）。 */
    const val ALL_BUCKET = "__all__"

    /** 服务端单文件上限 2GB（`uploadLimitByKind`，图片/视频同档）。 */
    const val MAX_BYTES = 2L shl 30

    /**
     * 选中态是**有序**的，不是 `Set`：格子右上角的编号 1..n 就是发送顺序，
     * 取消中间一张之后，后面的编号必须顺延（选了 1234 取消 2 → 剩下的显 1 2 3，不是 1 3 4）。
     * 用 Set 存就没有顺序可言，只能靠渲染时再排一次序，那个序又和发送顺序对不上。
     *
     * @return 新的选中列表；**超限返回 null**（调用方吐司提示，而不是静默丢弃——
     *         静默丢弃会让用户以为选上了，发完才发现少了几张）。
     */
    fun toggle(selected: List<Long>, id: Long, limit: Int = LIMIT): List<Long>? {
        if (selected.contains(id)) return selected.filter { it != id }
        if (selected.size >= limit) return null
        return selected + id
    }

    /** 编号（1-based）；未选中返回 0。 */
    fun indexOf(selected: List<Long>, id: Long): Int = selected.indexOf(id) + 1

    /**
     * 能不能选。`sizeBytes = 0` 是 MediaStore 里的坏行（文件已删、或正在写入），
     * 选了必然发失败——所以和「超上限」一样置灰，而不是让用户白等一次上传。
     */
    fun selectable(asset: MediaAsset): Boolean = asset.sizeBytes in 1..MAX_BYTES

    /**
     * 相册分桶。**「全部」恒在首位**，其余桶按**桶内最新一项的时间倒序**——
     * 不按数量排：数量最多的往往是 Screenshots，而用户刚拍的那个相册才是他要找的。
     * 封面取桶内最新一项（与列表排序同一个基准，避免封面和排序讲两个故事）。
     *
     * 入参假定已按 dateAdded 倒序（MediaStore 查询就是这么排的），但这里**不依赖**该假定，
     * 桶内自己再取一次最大值——依赖调用方的排序是隐形契约，改查询语句就会悄悄坏掉。
     */
    fun buckets(assets: List<MediaAsset>, allName: String, unnamedName: String): List<MediaBucket> {
        if (assets.isEmpty()) return emptyList()
        val all = MediaBucket(
            id = ALL_BUCKET,
            name = allName,
            count = assets.size,
            coverUri = assets.maxBy { it.dateAddedSec }.uri,
        )
        val rest = assets.groupBy { it.bucketId }
            .map { (id, items) ->
                val newest = items.maxBy { it.dateAddedSec }
                MediaBucket(
                    id = id,
                    name = newest.bucketName.ifBlank { unnamedName },
                    count = items.size,
                    coverUri = newest.uri,
                )
            }
            .sortedByDescending { b -> assets.filter { it.bucketId == b.id }.maxOf { it.dateAddedSec } }
        return listOf(all) + rest
    }

    /** 按桶过滤；[ALL_BUCKET] 返回全部。 */
    fun filter(assets: List<MediaAsset>, bucketId: String): List<MediaAsset> =
        if (bucketId == ALL_BUCKET) assets else assets.filter { it.bucketId == bucketId }

    /**
     * 发送前把选中的 ID 还原成资源，**按选中顺序**（不是相册顺序）。
     * 选中列表里有、资源列表里没有的（用户在选择过程中删了照片）直接跳过，不报错。
     */
    fun ordered(assets: List<MediaAsset>, selected: List<Long>): List<MediaAsset> {
        val byId = assets.associateBy { it.id }
        return selected.mapNotNull { byId[it] }
    }

    /**
     * 「原图」勾选框旁边显示的总字节数。**只统计选中的**；没选中显示 0。
     * 视频永远按原字节算（本模块不转码），所以这个数在有视频时就是真实上传量。
     */
    fun totalBytes(assets: List<MediaAsset>, selected: List<Long>): Long =
        ordered(assets, selected).sumOf { it.sizeBytes }

    /**
     * 人类可读的体积。**1024 进制**（和系统相册、文件管理器一致）。
     * 刻意保留一位小数：`1.0 MB` 与 `1 MB` 之间的信息差在「原图开关」这个场景里是有意义的。
     */
    fun sizeLabel(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    }

    /** 视频时长 `m:ss`（与 app 侧 `MediaUrl.formatDuration` 同口径）。 */
    fun durationLabel(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}

/**
 * 相册一项。**刻意不用 `android.net.Uri`**——用 String 存，纯逻辑才能进单测。
 */
data class MediaAsset(
    val id: Long,
    val uri: String,
    val mime: String,
    val displayName: String,
    val sizeBytes: Long,
    /** MediaStore `DATE_ADDED` 是**秒**不是毫秒——当成毫秒用会把所有照片排到 1970 年。 */
    val dateAddedSec: Long,
    val bucketId: String,
    val bucketName: String,
    val isVideo: Boolean = false,
    /** 视频时长（毫秒）；0 = 图片或读不到。 */
    val durationMs: Int = 0,
)

/** 相册（MediaStore 的 bucket），加上「全部」这个伪桶。 */
data class MediaBucket(
    val id: String,
    val name: String,
    val count: Int,
    val coverUri: String,
)
