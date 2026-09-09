package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * 群 @提及的纯逻辑（M4-8，仅群聊）——输入框 token 的识别/回填/还原 + 收端按片段切段。
 *
 * 对端逐条对齐：im-web `src/mention.ts`、iOS `IMChatViewController+Mention.m`
 * 与 `IMChatMessageLogic.m` 的 `IMChatTextContainsMentionToken` / `IMChatScanMentionSpans` /
 * `IMChatValidMentionSpans`。协议见 `IMServer/docs/PROTOCOL.md` §4.1「@提及」。
 *
 * ### 三条必须一致的口径（SYMMETRY 登记表 `*mention*` 那条）
 * 1. **偏移单位是 UTF-16 码元**。Kotlin 的 `String` 索引天生就是 UTF-16，与 iOS 的 `NSString`、
 *    JS 的 `String` 零换算。**别为了"更直观"改成码点**：`🎉@小明` 里 `@` 的 UTF-16 偏移是 2，
 *    码点偏移是 1，UTF-8 字节偏移是 4——一出现 emoji 就与另外两端和服务端校验全部对不上。
 * 2. **token 边界**：`@名字` 后面必须紧跟空白或字符串结尾。用裸子串会让「小美」被
 *    `@小美丽 开会` 误命中，从而收到一条**穿透免打扰**的错误强提醒。长名优先匹配。
 * 3. **有片段走片段、没有才回落按昵称扫文本**。超级群（2 万人）不下发成员表，
 *    老路在那里对普通成员必然失效——这正是 `mention_spans` 存在的全部理由。
 */
object Mention {

    /** `@所有人` 在输入框里的显示名（三端一致）。 */
    const val ALL_LABEL = "所有人"

    /** 触发提及的字符：半角 + 中文输入法的全角。**回填时一律写半角**。 */
    private const val AT_CHARS = "@＠"

    /**
     * 是不是"空白"。token 边界全靠它，三端必须同口径。
     *
     * 用 **Kotlin 的** `Char.isWhitespace()`：它等价于 `Character.isWhitespace() || isSpaceChar()`，
     * 因此认不间断空格 U+00A0（Zs 类）——与 JS 的 `/\s/`、iOS 的
     * `whitespaceAndNewlineCharacterSet` 一致。
     *
     * 别"顺手"换成 Java 的 `Character.isWhitespace(c)`：那个对 U+00A0 回 false
     * （实测 Kotlin true / Java false），于是「@张三<U+00A0>在吗」在另外两端算完整 token
     * （张三收到提醒）、在本端不算（收不到）——同一条消息三端分叉，肉眼完全看不出来。
     */
    private fun isBlank(c: Char): Boolean = c.isWhitespace()

    // ————————————————— 输入侧 —————————————————

    /**
     * 取"正在输入的 @查询词"：光标前最近一个 `@` 到光标之间、且**不含空白**的片段。
     * 返回 null = 当前不在 @ 输入态（不该弹面板）。
     *
     * `@` 后一旦打过空白就视为这次提及结束——用户在正常打字，再弹面板是打扰。
     */
    fun activeQuery(text: String, caret: Int): String? {
        if (text.isEmpty() || caret <= 0 || caret > text.length) return null
        val head = text.substring(0, caret)
        val at = head.lastIndexOfAny(AT_CHARS.toCharArray())
        if (at < 0) return null
        val q = head.substring(at + 1)
        if (q.any { isBlank(it) }) return null
        return q
    }

    /** [applyToken] 的结果：替换后的文本 + 光标该落在哪。 */
    data class TokenInsert(val text: String, val caret: Int)

    /**
     * 回填 token：把"正在输入的 @query"**整体替换**为 `@显示名 `。
     *
     * 尾随空格便于继续打字；但光标后本就以空白开头时不再补，否则会留下双空格。
     * 找不到 `@`（如从工具栏按钮触发）时兜底为"在光标处插入"。
     */
    fun applyToken(text: String, caret: Int, displayName: String): TokenInsert {
        val safe = caret.coerceIn(0, text.length)
        val head = text.substring(0, safe)
        val after = text.substring(safe)
        val at = head.lastIndexOfAny(AT_CHARS.toCharArray())
        val token = "@" + displayName + if (after.isNotEmpty() && isBlank(after[0])) "" else " "
        if (at < 0) return TokenInsert(head + token + after, safe + token.length)
        val before = text.substring(0, at)
        return TokenInsert(before + token + after, before.length + token.length)
    }

    /**
     * 文本里是否存在一个**完整的** `@名字` token（token 后紧跟空白或结尾）。
     *
     * 命中的若是更长名字的前缀就继续往后找——`@小美丽` 里不该判出「小美」。
     */
    fun containsToken(text: String, displayName: String): Boolean {
        if (text.isEmpty() || displayName.isEmpty()) return false
        val needle = "@$displayName"
        var from = 0
        while (true) {
            val idx = text.indexOf(needle, from)
            if (idx < 0) return false
            val after = idx + needle.length
            if (after >= text.length || isBlank(text[after])) return true
            from = idx + 1
        }
    }

    /**
     * 发送前把文本还原成被 @ 的 uid 列表：只保留**文本里仍存在完整 token** 的候选。
     *
     * 用户手动删掉 token 就自动不再 @ 他。结果去重、顺序稳定（按候选表插入序），
     * 便于测试与跨端日志比对——所以 [candidates] 要传有序 Map。
     */
    fun resolveMentions(text: String, candidates: Map<String, String>): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for ((uid, name) in candidates) {
            if (uid.isEmpty() || name.isEmpty()) continue
            if (containsToken(text, name)) out.add(uid)
        }
        return out.toList()
    }

    /** `@所有人` 是否仍生效：既要标记在、文本里也要还留着完整的 `@所有人` token。 */
    fun resolveMentionAll(text: String, pending: Boolean): Boolean =
        pending && containsToken(text, ALL_LABEL)

    /**
     * 发送前算出 @ 片段。与 [resolveMentions] 同源同规则，只是多记了位置。
     *
     * 「所有人」放在成员**之后**覆盖：同名成员碰上字面「所有人」时以 `@所有人` 为准——
     * 与服务端校验一致（空 uid 只在 `mention_all` 时合法；反过来记成员 uid 会因不在
     * `mentions` 里而被服务端整段丢弃）。
     *
     * 同名多人时一段文本只能链向一个 uid，这是**片段的固有限制**；
     * 谁收到强提醒不受影响——那由 [resolveMentions] 对每个候选独立判定，两个同名的人都会收到。
     */
    fun resolveSpans(
        text: String,
        candidates: Map<String, String>,
        mentionAll: Boolean,
    ): List<MentionSpan> {
        val nameToUid = LinkedHashMap<String, String>()
        for ((uid, name) in candidates) {
            if (uid.isNotEmpty() && name.isNotEmpty() && !nameToUid.containsKey(name)) nameToUid[name] = uid
        }
        if (mentionAll) nameToUid[ALL_LABEL] = ""
        return scanTokens(text, nameToUid)
    }

    /**
     * 按 token 边界扫描文本里的 `@名字`。边界规则与 [containsToken] 完全一致。
     * **只认半角 `@`**——回填的 token 一律是半角（见 [applyToken]）。
     */
    internal fun scanTokens(text: String, nameToUid: Map<String, String>): List<MentionSpan> {
        if (text.isEmpty() || nameToUid.isEmpty()) return emptyList()
        // 长名优先：`@小美丽` 必须先于 `@小美` 命中，否则前缀会把长名切碎
        val names = nameToUid.keys.filter { it.isNotEmpty() }.sortedByDescending { it.length }
        val out = ArrayList<MentionSpan>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '@') {
                val hit = names.firstOrNull { n ->
                    val end = i + 1 + n.length
                    end <= text.length &&
                        text.regionMatches(i + 1, n, 0, n.length) &&
                        (end >= text.length || isBlank(text[end]))
                }
                if (hit != null) {
                    out.add(MentionSpan(offset = i, length = hit.length + 1, uid = nameToUid[hit] ?: ""))
                    i += hit.length + 1
                    continue
                }
            }
            i += 1
        }
        return out
    }

    // ————————————————— 渲染侧 —————————————————

    /**
     * 过滤掉与本地文本对不上的片段。**两边都不信任偏移**——判据与服务端
     * `normalizeMentionSpans` 同一条：长度为正、不越界、该位置确是 `@`、互不重叠、按 offset 升序。
     *
     * 对不上就丢弃该段（编辑过的老消息、折叠截断、脏数据），一段不剩时调用方自然回落老路。
     */
    fun validSpans(text: String, spans: List<MentionSpan>): List<MentionSpan> {
        if (text.isEmpty() || spans.isEmpty()) return emptyList()
        val out = ArrayList<MentionSpan>()
        var end = 0
        for (s in spans.sortedBy { it.offset }) {
            if (s.length <= 0 || s.offset < 0 || s.offset + s.length > text.length) continue
            if (s.offset < end) continue
            if (text[s.offset] != '@') continue
            out.add(s)
            end = s.offset + s.length
        }
        return out
    }

    /**
     * 渲染侧：按服务端下发的片段把文本切段。**不需要任何成员表**——这就是这套机制的全部意义。
     * 没有可用片段时原样返回单段，由调用方决定要不要走 [segmentByNames] 那条老路。
     */
    fun segmentBySpans(text: String, spans: List<MentionSpan>): List<MentionSegment> {
        if (text.isEmpty()) return emptyList()
        val valid = validSpans(text, spans)
        if (valid.isEmpty()) return listOf(MentionSegment(text, mention = false))
        val segs = ArrayList<MentionSegment>()
        var at = 0
        for (s in valid) {
            if (s.offset > at) segs.add(MentionSegment(text.substring(at, s.offset), mention = false))
            segs.add(
                MentionSegment(
                    text.substring(s.offset, s.offset + s.length),
                    mention = true,
                    uid = s.uid.ifEmpty { null },
                ),
            )
            at = s.offset + s.length
        }
        if (at < text.length) segs.add(MentionSegment(text.substring(at), mention = false))
        return segs
    }

    /**
     * **老路**：按已知 `@显示名` token 切段（没有片段的老消息 / 老客户端发来的）。
     *
     * 普通群里这条路本就够用；超级群不下发成员表，这里拿不到 names 就整段不高亮
     * ——这正是片段机制要解决的那个场景，别在这里想办法补救。
     */
    fun segmentByNames(text: String, displayNames: List<String>): List<MentionSegment> {
        if (text.isEmpty()) return emptyList()
        val nameToUid = LinkedHashMap<String, String>()
        for (n in displayNames) if (n.isNotEmpty()) nameToUid.putIfAbsent(n, "")
        if (nameToUid.isEmpty()) return listOf(MentionSegment(text, mention = false))
        // 复用同一套扫描：老路只要"哪几段是提及"，uid 一律为空（老路本就点不动）
        return segmentBySpans(text, scanTokens(text, nameToUid))
    }

    /** 我能否 `@所有人`：仅群主/管理员。服务端另有校验（越权 300204），这里只决定面板画不画那一行。 */
    fun canMentionAll(role: String?): Boolean = role == "owner" || role == "admin"

    // ————————————————— 落库 / 解析 —————————————————

    /** 解析落库或下行的 JSON；坏数据一律丢弃该项，解不出就空表（调用方回落老路，不崩）。 */
    fun parseSpans(json: String?): List<MentionSpan> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            ProtocolJson.decodeFromString(ListSerializer(MentionSpan.serializer()), json)
        }.getOrElse { emptyList() }
            .filter { it.length > 0 && it.offset >= 0 }
    }

    /** 序列化落库。空表存 null——省得每行都塞一个 `[]`。 */
    fun encodeSpans(spans: List<MentionSpan>): String? =
        if (spans.isEmpty()) null
        else ProtocolJson.encodeToString(ListSerializer(MentionSpan.serializer()), spans)
}

/**
 * 一段 `@` 提及：从哪开始、多长、指向谁。
 *
 * `uid` 为空串 = `@所有人`（**只高亮不可点**）。`offset`/`length` 的单位是 **UTF-16 码元**，
 * 且片段**覆盖整个 token（含前导 `@`）**，即 `text[offset]` 必然是 `@`。
 */
@Serializable
data class MentionSpan(
    val offset: Int = 0,
    val length: Int = 0,
    @SerialName("user_id") val uid: String = "",
)

/** 切段结果：`mention=true` 的段要高亮；`uid` 非空才可点（`@所有人` 为 null）。 */
data class MentionSegment(val text: String, val mention: Boolean, val uid: String? = null)
