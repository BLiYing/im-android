package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Forward
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.libeyond.imandroid.data.MediaUrl
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
    /** [ContentType] 之一。**不吃 MessageEntity**：会话媒体归档那条路手上只有
     *  `ConvMediaItem`，为了调它去伪造一个 MessageEntity 是本末倒置。 */
    contentType: String,
    /** 媒体地址；待发消息是本地 `content://`。 */
    content: String,
    /** 视频封面（图片传空）。 */
    poster: String,
    host: String,
    useTls: Boolean,
    /** 已下载到本地的原件（由 Host 从下载器取）。有就用它显示/播放/存相册。 */
    localFile: java.io.File? = null,
    onSave: (url: String, isVideo: Boolean) -> Unit,
    /** 不传 = 这一处没有转发能力，**按钮直接不画**。摆一个点了没反应的按钮比没有更糟。 */
    onForward: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val isVideo = contentType == ContentType.VIDEO
    // 待发/失败的那条 content 是本地 content:// —— 原样用，别拼服务端前缀。
    // 存相册与渲染必须是**同一个地址**：分开算过一次就会出现「看到的是本地原图、
    // 存下来的是服务端压缩件」这种对不上账的情况。
    // **已下载的原件优先**：门控刚把它下到本地，查看器再从网络拉一遍就是白下。
    // 断网时也只有这条路能看（这正是"下载"的意义）。
    // `MediaUrl.absolute` 现在自己认得 `content://` / `file://`，不用在这里各挡一次。
    val source = localFile?.let { android.net.Uri.fromFile(it).toString() }
        ?: MediaUrl.absolute(content, host, useTls)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isVideo) {
            VideoPlayer(
                localFile = localFile,
                url = content,
                posterUrl = poster,
                host = host,
                useTls = useTls,
            )
        } else {
            ZoomableImage(model = source, contentDescription = "图片")
        }

        // 关闭：左上角。**不做「点空白关闭」**——图片可缩放，点空白与拖动/双击抢手势，
        // 视频那边还会和播放钮抢。iOS 侧同样是显式的关闭按钮。
        Row(
            modifier = Modifier.fillMaxWidth().systemBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerButton(Lucide.X, "关闭", onClose)
        }

        // 右下角一排：转发 + 下载（位置与 iOS `setupCommonControls` 的
        // `_downloadButton` 一致——用户靠位置形成肌肉记忆，两端摆得不一样就是两套）。
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .systemBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 转发**先关查看器再执行**：转发选择页要盖在聊天页上下文里，
            // 叠在查看器之上会出现「关掉选择页还留着一层黑底大图」的怪状态。
            // iOS 的 `showMoreSheet` 对外部动作也是这么处理的。
            if (onForward != null) {
                ViewerButton(Lucide.Forward, "转发", onForward)
                Spacer(Modifier.width(12.dp))
            }
            ViewerButton(Lucide.Download, "保存到相册") { onSave(source, isVideo) }
        }
    }
}

/** 查看器上的圆形按钮：黑底半透明 + 白色描边图标，压在任何画面上都看得见。 */
@Composable
private fun ViewerButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(Color.White),
        )
    }
}
