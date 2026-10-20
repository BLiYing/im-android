package com.libeyond.imandroid.data

import android.content.Context
import java.io.File

/**
 * 注销后清 Room 之外的账号相关本机落地物（DELETE_ACCOUNT_DESIGN §3.4：注销不保留已下载记录）。
 *
 * - 目录：媒体缓存 `media/`（按 URL 存、不按账号，整个清，其它账号会重新下载）、群资料快照 `group_info/`、
 *   待发媒体 `pending_media/`、待发语音 `voice_pending/`；
 * - SharedPreferences：看过的媒体、聊天文件夹、语音本地状态、收藏页模式、聊天横幅。
 * 设备级偏好（外观 / 语言 / 省电 / 通知 / 登录会话）**不动**。
 */
object LocalWipe {
    val DIRS = listOf("media", "group_info", "pending_media", "voice_pending")
    /** 不分账号的偏好：整个清。 */
    val PREFS = listOf("im_voice", "im_favorites", "chat_banners")

    /** 按账号分键的偏好（文件名 → 键）：只删被注销账号的键，同机其它账号的不动。 */
    val OWNER_KEYED_PREFS = mapOf("im_viewed_media" to "viewed.", "im_chat_folders" to "folders.")

    fun filesAndPrefs(context: Context, owner: String) {
        // 删完立刻重建空目录：MediaCache / VoiceRecorder 只在构造时 mkdirs，本进程内接着登录不能写到不存在的目录
        DIRS.forEach { File(context.filesDir, it).also { d -> d.deleteRecursively(); d.mkdirs() } }
        PREFS.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        OWNER_KEYED_PREFS.forEach { (name, prefix) ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().remove(prefix + owner).commit()
        }
    }
}
