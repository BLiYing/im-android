package com.libeyond.imandroid.data

import androidx.annotation.StringRes
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/**
 * 聊天页媒体查看器「更多」里的外部动作。内置的「下载」由查看器自己排在最前，不在这里。
 * `label` 惰性取值（[Str.s]），切语言不需要重建这些枚举实例。
 */
enum class ViewerAction(@StringRes private val labelRes: Int, val destructive: Boolean = false) {
    Locate(R.string.media_viewer_locate),
    Favorite(R.string.common_favorite),

    /** 复制图片到剪贴板（视频没有这一项，同 iOS）。 */
    Copy(R.string.common_copy),
    Forward(R.string.common_forward),
    Delete(R.string.common_delete, destructive = true),
    ;

    val label: String get() = Str.s(labelRes)
}

/**
 * 对齐 iOS `IMChatViewController+Media.m` 的 `mediaViewerMoreActionsForMessage:`
 * （定位 / 收藏 / 复制 / 转发 / 删除）。此前本端查看器右下角只有「转发 + 下载」两枚钮，
 * 没有「更多」（2026-09-16 用户报）。
 *
 * 与 iOS 的差异（刻意）：
 * - **收藏要求已确认、未撤回**：iOS 恒给；本端收藏接口按 conv_seq 记（[SelectionActions.favoritable] 同一道），
 *   未确认 / 已撤回的点了必然失败。
 * - 「复制」2026-09-16 补上（此前本端没有复制图片字节的能力）：本端复制的是**图片文件的
 *   `content://`**（经 FileProvider 交出去，见 `ui/CopyImageAction.kt`），不是位图字节——
 *   粘到别的应用里是同一张图，粘进纯文本框里会是一个 URI 而不是图。iOS 那侧复制的是 UIImage。
 */
object ViewerActions {

    /**
     * @param isVideo 视频没有「复制」（无"复制字节"语义，复制链接意义不大；iOS 同一道）。
     */
    fun chatMoreActions(convSeq: Long, recalled: Boolean, isVideo: Boolean): List<ViewerAction> = buildList {
        // 定位要 conv_seq：本地未确认的那条在服务端不存在，窗口里也无从定位
        if (convSeq > 0) add(ViewerAction.Locate)
        if (convSeq > 0 && !recalled) add(ViewerAction.Favorite)
        // **复制不要求 conv_seq**（同 iOS）：刚发出还没 ack 的那张图，本地就有字节，照样复制得了。
        // 撤回的不给：正文已被服务端脱敏，复制出来的是个失效地址。
        if (!isVideo && !recalled) add(ViewerAction.Copy)
        // 转发透传的是服务端地址：未确认 / 已撤回的转出去对端必然看不到（iOS 同一道）
        if (convSeq > 0 && !recalled) add(ViewerAction.Forward)
        // 危险项恒在最后（destructive-last，CHAT_UX §14）。也要 conv_seq：两档删除的判据
        // （MessageActions）对未确认的消息什么都不给，摆一个点了只说「不能删除」的项比没有更糟
        if (convSeq > 0) add(ViewerAction.Delete)
    }
}
