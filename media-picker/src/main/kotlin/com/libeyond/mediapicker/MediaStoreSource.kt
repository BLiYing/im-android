package com.libeyond.mediapicker

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

/**
 * 从 MediaStore 读相册。**只有这一层碰 Android API**，判定逻辑全在 [MediaPick]（可单测）。
 *
 * ### 为什么分成「先查相册列表、再分页查格子」两步
 * 初版是「一次读最新 2000 张，相册列表从这 2000 张归纳」。那样**一个只装旧照片的相册
 * 会整个不出现在切换列表里**——用户翻遍了也找不到自己两年前导入的那批。
 * 相册列表必须由**全量**归纳（只读 4 个轻列，几万行也就一次游标扫描），
 * 格子才分页拿（用户不会滑到第 3000 张，但他会想切到第 20 个相册）。
 *
 * 调用方须自行保证已有权限（[MediaPermission]）——**无权限时 query 不抛异常、只返回空游标**，
 * 表现成「相册是空的」，比报错更难查。
 */
object MediaStoreSource {

    /** 一页多少个。滑到底自动续下一页。 */
    const val PAGE = 300

    private val FILES: Uri = MediaStore.Files.getContentUri("external")

    /** 只取图片与视频；`MEDIA_TYPE` 常量 1=image、3=video。 */
    private const val WHERE_MEDIA =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}," +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

    private const val WHERE_IMAGE_ONLY =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"

    private const val ORDER = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

    /**
     * `MediaStore.MediaColumns.DURATION` 是 API 29 才有的常量，但底层列名一直是 "duration"。
     * minSdk 26，所以用字面量并靠 `getColumnIndex` 是否 ≥0 兜底。
     */
    private const val COL_DURATION = "duration"

    /**
     * 相册列表：**全量扫一遍**，只读归纳相册所必需的列。
     *
     * 必须在 IO 线程调用。查询失败返回空列表（UI 显空态），不抛。
     */
    fun buckets(context: Context, includeVideo: Boolean, log: MediaPickerLog = MediaPickerLog.None): List<MediaBucket> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
        )
        // 按桶累计：数量、最新时间、最新那项的 uri（= 封面）
        val count = HashMap<String, Int>()
        val newest = HashMap<String, Long>()
        val cover = HashMap<String, String>()
        val name = HashMap<String, String>()
        var total = 0
        var globalNewest = -1L
        var globalCover = ""
        try {
            query(context.contentResolver, projection, where(includeVideo), ORDER, null)?.use { cur ->
                val idIdx = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeIdx = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val dateIdx = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val bIdx = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
                val bnIdx = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                while (cur.moveToNext()) {
                    val bucket = cur.getString(bIdx) ?: continue
                    val date = cur.getLong(dateIdx)
                    total++
                    count[bucket] = (count[bucket] ?: 0) + 1
                    if (date >= (newest[bucket] ?: -1L)) {
                        newest[bucket] = date
                        cover[bucket] = itemUri(cur.getLong(idIdx), cur.getInt(typeIdx)).toString()
                        name[bucket] = cur.getString(bnIdx) ?: ""
                    }
                    if (date >= globalNewest) {
                        globalNewest = date
                        globalCover = itemUri(cur.getLong(idIdx), cur.getInt(typeIdx)).toString()
                    }
                }
            }
        } catch (e: Exception) {
            log.w("mediastore_bucket_query_failed", "err" to e.javaClass.simpleName)
            return emptyList()
        }
        if (total == 0) return emptyList()
        val all = MediaBucket(MediaPick.ALL_BUCKET, "全部", total, globalCover)
        val rest = count.keys
            .sortedByDescending { newest[it] ?: 0L }
            .map { MediaBucket(it, name[it]?.ifBlank { "未命名" } ?: "未命名", count[it] ?: 0, cover[it] ?: "") }
        log.d("mediastore_buckets", "buckets" to rest.size, "total" to total)
        return listOf(all) + rest
    }

    /**
     * 取一页格子。[bucketId] 传 [MediaPick.ALL_BUCKET] 表示不限相册。
     *
     * 分页两条实现：**Android 11(API 30) 起必须走 Bundle 参数**——那之后 MediaProvider
     * 会拒绝 `sortOrder` 里夹带的 `LIMIT`（旧写法在新系统上直接抛），而 Bundle 参数
     * 在 API 26~29 上又不被支持。所以两条都得留，删掉任一条都会在某段系统上炸。
     */
    fun page(
        context: Context,
        bucketId: String,
        offset: Int,
        limit: Int = PAGE,
        includeVideo: Boolean = true,
        log: MediaPickerLog = MediaPickerLog.None,
    ): List<MediaAsset> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            COL_DURATION,
        )
        var sel = where(includeVideo)
        var args: Array<String>? = null
        if (bucketId != MediaPick.ALL_BUCKET) {
            sel += " AND ${MediaStore.Files.FileColumns.BUCKET_ID} = ?"
            args = arrayOf(bucketId)
        }
        val out = ArrayList<MediaAsset>(limit)
        try {
            pageQuery(context.contentResolver, projection, sel, args, offset, limit)?.use { cur ->
                val idIdx = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeIdx = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val nameIdx = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeIdx = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeIdx = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateIdx = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val bIdx = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
                val bnIdx = cur.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                val durIdx = cur.getColumnIndex(COL_DURATION) // 老系统上可能没有
                while (cur.moveToNext()) {
                    val id = cur.getLong(idIdx)
                    val type = cur.getInt(typeIdx)
                    val isVideo = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    out.add(
                        MediaAsset(
                            id = id,
                            uri = itemUri(id, type).toString(),
                            mime = cur.getString(mimeIdx) ?: if (isVideo) "video/mp4" else "image/jpeg",
                            displayName = cur.getString(nameIdx)
                                ?: (if (isVideo) "video_$id.mp4" else "image_$id.jpg"),
                            sizeBytes = cur.getLong(sizeIdx),
                            dateAddedSec = cur.getLong(dateIdx),
                            bucketId = cur.getString(bIdx) ?: "unknown",
                            bucketName = cur.getString(bnIdx) ?: "",
                            isVideo = isVideo,
                            durationMs = if (durIdx >= 0 && !cur.isNull(durIdx)) cur.getInt(durIdx) else 0,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            // 权限被撤、存储被卸载、厂商 ROM 的 provider 抽风都会走到这里。
            // 返回空页让 UI 停止续页并显空态，比崩溃强。
            log.w("mediastore_page_failed", "err" to e.javaClass.simpleName, "offset" to offset)
            return emptyList()
        }
        log.d("mediastore_page", "offset" to offset, "got" to out.size)
        return out
    }

    private fun where(includeVideo: Boolean) = if (includeVideo) WHERE_MEDIA else WHERE_IMAGE_ONLY

    private fun itemUri(id: Long, mediaType: Int): Uri =
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        } else {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }

    private fun query(
        cr: ContentResolver,
        projection: Array<String>,
        selection: String,
        order: String,
        args: Array<String>?,
    ): Cursor? = cr.query(FILES, projection, selection, args, order)

    private fun pageQuery(
        cr: ContentResolver,
        projection: Array<String>,
        selection: String,
        args: Array<String>?,
        offset: Int,
        limit: Int,
    ): Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        // API 27+ 支持 Bundle 形式的 query（QUERY_ARG_* 常量自 API 26 起就在，
        // 但 MediaProvider 对 LIMIT/OFFSET 的支持从 O_MR1 起才稳）
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, ORDER)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        cr.query(FILES, projection, bundle, null)
    } else {
        // API 26：只能把 LIMIT 夹在 sortOrder 里（Android 11 起这条会被拒，所以只走老系统）
        cr.query(FILES, projection, selection, args, "$ORDER LIMIT $limit OFFSET $offset")
    }
}
