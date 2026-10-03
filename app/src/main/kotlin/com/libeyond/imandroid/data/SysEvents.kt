package com.libeyond.imandroid.data

import androidx.annotation.StringRes
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import com.libeyond.imandroid.sdk.protocol.SysSegment
import com.libeyond.imandroid.ui.components.TimeFormat
import java.time.OffsetDateTime
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 系统消息结构化事件 `sys_event`/`sys_args`（P3 i18n，IMServer `docs/PROTOCOL.md` §6.6）按 App 语言渲染。
 *
 * `content`/`sys_segments` 是服务端预生成的**中文成品**，切英文也翻不动；新字段带的是事件枚举 + 原始参数，
 * 这里按文案表模板现拼。对齐 iOS `IMSysEventFormatter`、Web `sysEventRender.ts`。
 *
 * - 群系统消息 → [groupSegments]：产出与服务端 `sys_segments` 同构的分段，喂给 `SystemNote`
 *   现有的"名字按本地口径重渲染 + 可点"管线，不另起一套。
 * - 系统通知单聊（uid 777000）→ [noticeText]：没有人名段，按 `sys_args` 拼多行文本。
 *
 * **事件为空或不认识一律返回 null**，调用方回退 `content`/`sys_segments` 整句——老消息、管理后台发的、
 * 未来新增而本端尚不认识的事件都走这条降级，不是错误。
 * 新增事件要跟着改这里的表，见 IMServer `docs/SYMMETRY.md`（`internal/store/types.go` 一行）。
 */
object SysEvents {

    /**
     * @param params 模板占位符名，**顺序必须与 strings.json 该键的 `args` 键顺序一致**
     *   （生成器按这个顺序编号 `%1$s`、`%2$s`…）。
     * @param names 哪些占位符是人名，按服务端"人名段"约定的顺序依次消费带 uid 的段。
     */
    private class GroupEvent(@StringRes val res: Int, val params: List<String>, val names: List<String>)

    /** 权威表：IMServer `internal/store/types.go` 的 `SysEvent*` 常量注释。 */
    private val GROUP = mapOf(
        "group_create" to GroupEvent(R.string.sys_group_create, listOf("owner", "name"), listOf("owner")),
        // 人名段 = [邀请者, 被邀请者...]：names 吃掉邀请者之后的全部段（见 [groupSegments]）
        "member_invite" to GroupEvent(R.string.sys_group_member_invite, listOf("actor", "names"), listOf("actor")),
        "member_join" to GroupEvent(R.string.sys_group_member_join, listOf("actor"), listOf("actor")),
        "member_leave" to GroupEvent(R.string.sys_group_member_leave, listOf("actor"), listOf("actor")),
        "member_remove" to GroupEvent(R.string.sys_group_member_remove, listOf("actor", "target"), listOf("actor", "target")),
        "member_remove_ban" to GroupEvent(R.string.sys_group_member_remove_ban, listOf("actor", "target"), listOf("actor", "target")),
        "role_admin_set" to GroupEvent(R.string.sys_group_role_admin_set, listOf("target"), listOf("target")),
        "role_admin_revoked" to GroupEvent(R.string.sys_group_role_admin_revoked, listOf("target"), listOf("target")),
        "owner_transfer" to GroupEvent(R.string.sys_group_owner_transfer, listOf("target"), listOf("target")),
        "group_rename" to GroupEvent(R.string.sys_group_rename, listOf("name"), emptyList()),
        "group_avatar" to GroupEvent(R.string.sys_group_avatar, listOf("actor"), listOf("actor")),
        "announcement" to GroupEvent(R.string.sys_group_announcement, listOf("actor"), listOf("actor")),
        "mute_all_on" to GroupEvent(R.string.sys_group_mute_all_on, emptyList(), emptyList()),
        "mute_all_off" to GroupEvent(R.string.sys_group_mute_all_off, emptyList(), emptyList()),
        "mute_member" to GroupEvent(R.string.sys_group_mute_member, listOf("actor", "target"), listOf("actor", "target")),
        "unmute_member" to GroupEvent(R.string.sys_group_unmute_member, listOf("actor", "target"), listOf("actor", "target")),
    )

    private val ARGS_SERIALIZER = MapSerializer(String.serializer(), String.serializer())

    /** `sys_args` 落库用的 JSON；空 → null（与 sysSegments 同口径）。 */
    fun encodeArgs(args: Map<String, String>?): String? =
        args?.takeIf { it.isNotEmpty() }?.let { ProtocolJson.encodeToString(ARGS_SERIALIZER, it) }

    /** 解析落库的 `sys_args`；坏数据/空 → 空表（缺参数的占位符留空，不崩）。 */
    fun parseArgs(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching { ProtocolJson.decodeFromString(ARGS_SERIALIZER, json) }.getOrElse { emptyMap() }
    }

    /** 多人名单分隔：zh 顿号、en 逗号加空格（不做 "A, B and C" 语法优化，同 Web）。 */
    private fun separator(): String = if (Str.languageTag == "en") ", " else "、"

    /**
     * 群系统消息 → 本语言的分段。人名段沿用服务端段自身的 `uid`/`text`，
     * 本地显示名（备注 > 群昵称）仍由 `SystemNote` 按 uid 解析、可点，这里不重复解析。
     * 候选人名段不足（脏数据）→ 该占位符留空，不崩。
     */
    fun groupSegments(event: String?, argsJson: String?, segmentsJson: String?): List<SysSegment>? {
        val def = GROUP[event ?: return null] ?: return null
        val args = parseArgs(argsJson)
        val people = SysSegments.parse(segmentsJson).filter(SysSegments::isName)
        val out = mutableListOf<SysSegment>()
        tokenize(Str.s(def.res)).forEach { tok ->
            when {
                tok !is Token.Slot -> if (tok.text.isNotEmpty()) out += SysSegment(text = tok.text)
                tok.index !in def.params.indices -> Unit
                def.params[tok.index] == "names" -> {
                    // 被邀请者逐个出段，才能各自可点；分隔符是普通文案段
                    people.drop(def.names.size).forEachIndexed { i, p ->
                        if (i > 0) out += SysSegment(text = separator())
                        out += p
                    }
                }
                else -> {
                    val name = def.params[tok.index]
                    val slot = def.names.indexOf(name)
                    if (slot >= 0) people.getOrNull(slot)?.let { out += it }
                    else args[name]?.takeIf { it.isNotEmpty() }?.let { out += SysSegment(text = it) }
                }
            }
        }
        return out.ifEmpty { null }
    }

    /** [groupSegments] 拼成纯文本（会话列表预览：服务端公开昵称，不挂点击）。 */
    fun groupText(event: String?, argsJson: String?, segmentsJson: String?, selfUid: String? = null): String? =
        groupSegments(event, argsJson, segmentsJson)?.joinToString("") {
            if (SysSegments.isName(it)) SysSegments.displayName(it, null, null, selfUid) else it.text
        }

    /**
     * 系统通知单聊（新设备登录 / 改密 / 被踢下线）→ 多行文本，行结构对齐服务端 `buildXxxNoticeText`。
     * 非这三个事件 → null，调用方显示 `content`。
     */
    fun noticeText(event: String?, argsJson: String?): String? {
        val a = parseArgs(argsJson)
        fun arg(k: String) = a[k].orEmpty()
        val at = formatAt(arg("at"))
        val lines = when (event) {
            "new_device_login" -> buildList {
                val unusual = arg("unusual") == "1"
                add(
                    if (unusual && arg("province").isNotEmpty()) {
                        Str.s(R.string.sys_notice_new_device_headline_unusual, at, arg("province"))
                    } else {
                        Str.s(R.string.sys_notice_new_device_headline_normal, at)
                    }
                )
                val device = arg("device").ifEmpty { Str.s(R.string.device_platform_unknown) }
                add(
                    if (arg("platform").isNotEmpty()) {
                        Str.s(R.string.sys_notice_new_device_device_platform_line, device, arg("platform"))
                    } else {
                        Str.s(R.string.sys_notice_new_device_device_line, device)
                    }
                )
                if (arg("ip").isNotEmpty()) add(Str.s(R.string.sys_notice_new_device_ip_line, arg("ip")))
                val familiars = arg("familiars").split(",").map(String::trim).filter(String::isNotEmpty)
                if (unusual && familiars.isNotEmpty()) {
                    add(Str.s(R.string.sys_notice_new_device_familiars_line, familiars.joinToString(separator())))
                }
                add(Str.s(R.string.sys_notice_new_device_footer))
            }
            "password_changed" -> buildList {
                add(Str.s(R.string.sys_notice_password_changed_headline, at))
                if (arg("device").isNotEmpty()) add(Str.s(R.string.sys_notice_password_changed_device_line, arg("device")))
                add(Str.s(R.string.sys_notice_password_changed_footer))
            }
            "device_kicked" -> buildList {
                val device = arg("target_device").ifEmpty { Str.s(R.string.common_unknown_device_kicked) }
                add(Str.s(R.string.sys_notice_device_kicked_headline, device))
                add(
                    if (arg("actor_device").isNotEmpty()) {
                        Str.s(R.string.sys_notice_device_kicked_by_line, arg("actor_device"), at)
                    } else {
                        Str.s(R.string.sys_notice_device_kicked_time_line, at)
                    }
                )
                add(Str.s(R.string.sys_notice_device_kicked_footer))
            }
            else -> return null
        }
        return lines.joinToString("\n")
    }

    /** 聊天页气泡正文：对端是系统通知账号且事件认识时返回本语言多行文本，否则 null（显示 content）。 */
    fun noticeTextOf(m: MessageEntity): String? =
        if (DetailActions.isSystemPeer(m.sender)) noticeText(m.sysEvent, m.sysArgs) else null

    /** RFC3339 → 本地「年月日 时:分」（同详情页归档的口径）；解析失败原样透传，不崩、不空白。 */
    private fun formatAt(rfc3339: String): String {
        if (rfc3339.isEmpty()) return ""
        return runCatching {
            TimeFormat.fileDateTime(OffsetDateTime.parse(rfc3339).toInstant().toEpochMilli())
        }.getOrElse { rfc3339 }
    }

    private sealed interface Token {
        val text: String
        data class Text(override val text: String) : Token
        /** `%N$s`/`%N$d` 占位符，[index] 从 0 起。 */
        data class Slot(val index: Int) : Token { override val text = "" }
    }

    private val PLACEHOLDER = Regex("""%(\d+)\$[sd]|%%""")

    /** 生成器产出的 Android 模板（未代入参数的原串）按占位符切段。 */
    private fun tokenize(template: String): List<Token> {
        val out = mutableListOf<Token>()
        val buf = StringBuilder()
        var last = 0
        PLACEHOLDER.findAll(template).forEach { m ->
            buf.append(template, last, m.range.first)
            last = m.range.last + 1
            if (m.value == "%%") {
                buf.append('%')
            } else {
                out += Token.Text(buf.toString())
                buf.clear()
                out += Token.Slot(m.groupValues[1].toInt() - 1)
            }
        }
        buf.append(template, last, template.length)
        out += Token.Text(buf.toString())
        return out
    }
}
