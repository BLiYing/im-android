package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import com.libeyond.imandroid.sdk.protocol.SysSegment
import kotlinx.serialization.builtins.ListSerializer

/**
 * 系统消息分段（PROTOCOL §6，2026-08-29）。
 *
 * `content` 是**全群共享的一条字符串**，服务端生成时只能填公开昵称，于是两件事做不到：
 * ① 我给某人设了备注，这条消息里还是显示他的昵称；② 名字不可点、看不到那是谁。
 * 分段把整句拆开，`uid` 非空的那段是名字，**收端按本地口径重渲染**并挂点击。
 *
 * **隐私**：`text` 恒为服务端生成时的公开昵称，绝不含备注——这条消息全群都收得到。
 * 备注只在**收端本地**按 `uid` 重解析时才出现（IMServer `docs/UI.md` 的隐私红线）。
 */
object SysSegments {

    /** 解析落库的 JSON；坏数据/空 → 空列表（调用方回退整句渲染，不崩）。 */
    fun parse(json: String?): List<SysSegment> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            ProtocolJson.decodeFromString(ListSerializer(SysSegment.serializer()), json)
        }.getOrElse { emptyList() }
    }

    /**
     * 渲染用的分段。**拿不到分段就回退成整句一段**——历史系统消息本来就没有分段
     * （服务端当时没存这一列），协议里明写"不做回溯"。
     */
    fun render(json: String?, content: String): List<SysSegment> {
        val segs = parse(json)
        return if (segs.isEmpty()) listOf(SysSegment(uid = "", text = content)) else segs
    }

    /**
     * 名字段显示成什么。**回退链：备注 → 群昵称 → 服务端给的公开昵称**。
     *
     * **自己恒显示「我」**（[selfUid] 命中，对齐 iOS `localNameForUID:selfUID:`），优先于备注/群昵称。
     *
     * 末级用 `seg.text`（服务端生成时的公开昵称）而**不是 uid**：uid 是 10 位内部 ID，
     * 露在界面上对用户毫无意义（账号重构后的既定纪律）。
     */
    fun displayName(seg: SysSegment, remark: String?, groupNickname: String?, selfUid: String? = null): String =
        if (!selfUid.isNullOrBlank() && seg.uid == selfUid) Str.s(R.string.common_me)
        else remark?.takeIf { it.isNotBlank() }
            ?: groupNickname?.takeIf { it.isNotBlank() }
            ?: seg.text

    /** 这一段是不是可点的名字。 */
    fun isName(seg: SysSegment): Boolean = seg.uid.isNotBlank()

    /**
     * 分段拼起来必须等于 `content`（协议保证）。**用来自检而不是用来渲染**：
     * 对不上说明服务端或本地库串了，此时宁可回退整句也不要显示半句。
     */
    fun matchesContent(segs: List<SysSegment>, content: String): Boolean =
        segs.joinToString("") { it.text } == content
}
