package com.libeyond.imandroid.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.libeyond.imandroid.sdk.logging.IMLog

/**
 * 从 MediaStore 读相册。**只有这一层碰 Android API**，判定逻辑全在 [MediaPick]（可单测）。
 *
 * 调用方须自行保证已有权限（[MediaPermission]）——无权限时 query 不抛异常、只是返回空游标，
 * 表现成「相册是空的」，比报错更难查。
 */
object MediaStoreSource {

    private val log = IMLog.tag("IM.Media")

    /** 一次读多少张。相册几万张时全读会卡住主线程后的第一帧；分页在 UI 里做，这里先给上限。 */
    const val PAGE = 2000

    /**
     * 查询图片。**只查图片**：`sendMedia` 是整包字节一次性上传（无分片），
     * 视频进来会直接 OOM，见 [MediaPick] 的说明。
     *
     * 必须在 IO 线程调用。
     */
    fun images(context: Context, limit: Int = PAGE): List<MediaAsset> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val out = ArrayList<MediaAsset>(minOf(limit, 512))
        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cur ->
                val idIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val bucketIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val bucketNameIdx = cur.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cur.moveToNext() && out.size < limit) {
                    val id = cur.getLong(idIdx)
                    out.add(
                        MediaAsset(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            mime = cur.getString(mimeIdx) ?: "image/jpeg",
                            displayName = cur.getString(nameIdx) ?: "image_$id.jpg",
                            sizeBytes = cur.getLong(sizeIdx),
                            // DATE_ADDED 是**秒**；当成毫秒用会让所有照片排到 1970 年
                            dateAddedSec = cur.getLong(dateIdx),
                            bucketId = cur.getString(bucketIdx) ?: "unknown",
                            bucketName = cur.getString(bucketNameIdx) ?: "",
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            // 权限被撤、存储被卸载、厂商 ROM 的 provider 抽风都会走到这里。
            // 返回空列表让 UI 显空态 + 「用系统选择器」入口，比崩溃强。
            log.w("mediastore_query_failed", "err" to e.javaClass.simpleName)
            return emptyList()
        }
        log.d("mediastore_loaded", "count" to out.size)
        return out
    }
}
