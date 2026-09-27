package com.libeyond.imandroid.data

/**
 * 引用快照结构化标记 `reply_snapshot_kind`/`reply_snapshot_args`（P3 i18n，IMServer `docs/PROTOCOL.md` §4.3）。
 *
 * 老字段 `reply_snapshot` 里 `chat_record`/`contact` 是服务端**预本地化的中文成品**（`[聊天记录] 标题`），
 * 已撤回是 `[已撤回的消息]`，切英文翻不动。这里把 `kind`+`args` **还原成本端引用块已认得的原始 token 形态**
 * （`[chat_record] 标题`、`[file] 名`、`[voice] 0:12`…），显示时统一走 `localizeReplySnapshot` 换前缀——
 * 类型图标、文件名、本地化三处判据共用同一种输入，不为结构化字段另起一套分支。
 * 对齐 iOS `IMRenderReplySnapshot`、Web `localizeReplySnapshot`。
 *
 * `kind` 为空（纯文本引用 / 带 caption 的图文引用 / 老消息）或不认识 → 原样返回 `reply_snapshot`。
 */
object ReplySnapshots {

    /** 已撤回的原始 token（协议里没有这个字面量，是本端给 `recalled` 定的，只在本地流转）。 */
    const val RECALLED = "[recalled]"

    fun canonical(kind: String?, argsJson: String?, fallback: String?): String? {
        val args = SysEvents.parseArgs(argsJson)
        fun withTail(token: String, tail: String?) = if (tail.isNullOrBlank()) token else "$token $tail"
        return when (kind) {
            "recalled" -> RECALLED
            "chat_record" -> withTail("[chat_record]", args["title"])
            "file" -> withTail("[file]", args["name"])
            "voice" -> "[voice] " + MediaUrl.formatDuration(args["duration_ms"]?.toLongOrNull()?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt())
            "contact" -> withTail("[contact]", args["name"])
            "call" -> "[call]"
            "other" -> when (args["content_type"]) {
                "image" -> "[image]"
                "video" -> "[video]"
                else -> fallback
            }
            else -> fallback
        }
    }
}
