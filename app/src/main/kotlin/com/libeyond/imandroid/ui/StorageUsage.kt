package com.libeyond.imandroid.ui

import android.content.Context
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import com.libeyond.imandroid.data.MediaDownloader
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「存储用量」量什么、清什么（对齐 iOS `IMDataStorageViewController` 的 `sizeDirs` / `clearCache`）。
 *
 * 两块，**少算一块就会「显示 0 KB 却清出几百 MB」**（iOS 注释原话）：
 * - 下载的媒体原件 `filesDir/media`（iOS 的 `IMDownloads` + `im_original_videos`）；
 * - Coil 的磁盘图片缓存——头像这类直接走网络的图（iOS 的 `im_image_cache`）。
 *
 * 各自**经 owner 清**，不直删目录（iOS 同一条纪律）：媒体经 [MediaDownloader.clearAll]
 * （在途任务与内存状态要一起清），图片经 Coil 的 `diskCache` / `memoryCache`
 * （直删目录会与它的日志文件打架，内存里的位图也还在，头像看起来「清了但没清」）。
 *
 * 不算进来的：`cacheDir/share`、`cacheDir/camera` 是分享 / 拍照的临时中转文件，不是「下载的缓存」；
 * 本地消息库也不算（iOS 同）——清掉它就是清聊天记录，那是另一个功能。
 */
internal object StorageUsage {

    private val log = IMLog.tag("IM.Download")

    suspend fun measure(context: Context, downloads: MediaDownloader): Long = withContext(Dispatchers.IO) {
        downloads.cachedBytes() + imageCacheBytes(context)
    }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clear(context: Context, downloads: MediaDownloader) = withContext(Dispatchers.IO) {
        val images = imageCacheBytes(context)
        downloads.clearAll()
        val loader = Coil.imageLoader(context)
        loader.diskCache?.clear()
        loader.memoryCache?.clear()
        log.i("image_cache_cleared", "bytes" to images)
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun imageCacheBytes(context: Context): Long = Coil.imageLoader(context).diskCache?.size ?: 0L
}
