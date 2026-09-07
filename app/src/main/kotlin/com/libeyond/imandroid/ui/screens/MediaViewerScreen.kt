package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.libeyond.imandroid.data.MediaUrl
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.protocol.ContentType
import com.libeyond.mediapicker.ZoomableImage

/**
 * 单条媒体查看器：图片可缩放、视频可播放。
 *
 * **图片那半复用 `:media-picker` 的 [ZoomableImage]**，不另写一份——
 * 那是本工程第一份可缩放查看器，两份实现必然在「边界回弹」「双击倍率」上分叉。
 * 为此把该模块的 `LocalPickerImageLoader` 默认值从 `error(...)` 改成回落 Coil 单例
 * （导出一个只能在自家内部跑的组件等于没导出）。
 *
 * **刻意只做单条**：iOS/Web 的查看器还能在会话媒体时间线上左右翻页
 * （CLIENT_PARITY 任务3），那要先有「会话媒体列表」这套数据，是独立一块。
 * 这里先把「点开能看/能放」这条闭上——上一轮刚让视频能发，点开却没反应，
 * 是我自己捅的缺口。
 */
@Composable
internal fun MediaViewerScreen(
    msg: MessageEntity,
    host: String,
    useTls: Boolean,
    onClose: () -> Unit,
) {
    val isVideo = msg.contentType == ContentType.VIDEO
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isVideo) {
            VideoPlayer(
                url = msg.content,
                posterUrl = msg.poster,
                host = host,
                useTls = useTls,
            )
        } else {
            ZoomableImage(
                // 待发/失败的那条 content 是本地 content:// —— 原样交给 Coil，别拼服务端前缀
                model = if (msg.content.startsWith("content://")) {
                    msg.content
                } else {
                    MediaUrl.absolute(msg.content, host, useTls)
                },
                contentDescription = "图片",
            )
        }

        // 关闭：左上角。**不做「点空白关闭」**——图片可缩放，点空白与拖动/双击抢手势，
        // 视频那边还会和播放钮抢。iOS 侧同样是显式的关闭按钮。
        Row(
            modifier = Modifier.fillMaxWidth().systemBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(36.dp).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = Lucide.X,
                    contentDescription = "关闭",
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(Color.White),
                )
            }
        }
    }
}
