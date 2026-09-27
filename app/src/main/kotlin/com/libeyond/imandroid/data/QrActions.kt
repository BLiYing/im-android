package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.sdk.api.QrGroupCard

/**
 * 扫码/点链接解析结果 → 按钮态的纯映射（对齐 iOS `IMQRModels.m`、Web `src/qr.ts`）。
 * 语义判定全在服务端 `/qr/resolve`；本文件只把 `relation`/`joinable`/`reason` 映射成 UI 分支，
 * 纯函数，可单测。
 */

/** 名片码扫后主按钮态。 */
enum class QrUserAction { ADD, MESSAGE, SELF, BLOCKED }

/** 群码扫后主按钮态。 */
enum class QrGroupAction { JOIN, APPLY, ENTER, DISABLED }

/** relation → 名片码主按钮动作。 */
fun qrUserActionFor(relation: String): QrUserAction = when (relation) {
    "self" -> QrUserAction.SELF
    "friend" -> QrUserAction.MESSAGE
    "blocked" -> QrUserAction.BLOCKED
    else -> QrUserAction.ADD
}

/** 名片码主按钮文案。 */
fun qrUserActionLabel(action: QrUserAction): String = when (action) {
    QrUserAction.MESSAGE -> Str.s(R.string.qr_action_send_message)
    QrUserAction.SELF -> Str.s(R.string.qr_action_view_my_profile)
    QrUserAction.BLOCKED -> Str.s(R.string.qr_branch_view_profile)
    QrUserAction.ADD -> Str.s(R.string.qr_action_add_contact)
}

/**
 * `/qr/resolve` 的 `relation`（stranger/friend/self/blocked）→ [UserProfileHost] 期望的
 * `knownRelation`（accepted/pending/requested/blocked/self/空=陌生人）。
 * self/blocked 两端取值本就相同，只有 friend→accepted 需要转一道；stranger 落 else 分支，
 * 回退空串正好是 [UserProfileScreen] 的「陌生人」默认态。
 */
fun qrRelationToProfileRelation(relation: String): String = when (relation) {
    "self" -> MemberProfile.RELATION_SELF
    "friend" -> FriendEntry.ACCEPTED
    "blocked" -> FriendEntry.BLOCKED
    else -> ""
}

/** 群码 → 群主按钮动作。 */
fun qrGroupActionFor(card: QrGroupCard?): QrGroupAction {
    if (card == null) return QrGroupAction.DISABLED
    if (card.joined) return QrGroupAction.ENTER
    if (!card.joinable) return QrGroupAction.DISABLED
    if (card.reason == "approval") return QrGroupAction.APPLY
    return QrGroupAction.JOIN
}

/** 群码主按钮文案。 */
fun qrGroupActionLabel(action: QrGroupAction): String = when (action) {
    QrGroupAction.ENTER -> Str.s(R.string.qr_action_enter_group)
    QrGroupAction.APPLY -> Str.s(R.string.qr_action_apply)
    QrGroupAction.DISABLED -> Str.s(R.string.qr_action_cannot_join)
    QrGroupAction.JOIN -> Str.s(R.string.qr_action_join)
}

/** 群码不可加入/需审批时的说明文案（可空=不显示）。 */
fun qrGroupActionNote(card: QrGroupCard?): String? {
    if (card == null) return null
    if (card.joined) return null
    if (!card.joinable) {
        return when (card.reason) {
            "full" -> Str.s(R.string.qr_action_group_full_note)
            "banned" -> Str.s(R.string.qr_action_banned_note)
            "invite_revoked" -> Str.s(R.string.qr_action_admin_only_note)
            else -> null
        }
    }
    if (card.reason == "approval") return Str.s(R.string.qr_action_apply_note)
    return null
}

/** 外来码原文是否 http(s) URL；是则回域名主体（供二次确认高亮），否则回 null。 */
fun qrUnknownDomain(text: String): String? {
    val t = text.trim()
    if (!t.startsWith("http://", ignoreCase = true) && !t.startsWith("https://", ignoreCase = true)) return null
    return runCatching { java.net.URI(t).host }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * 一图多码候选列表的可读摘要（对齐 iOS `IMQRScannerViewController.labelForRaw:`）。
 * 本站码按路径前缀标注，其余给域名或文本首段——只为让用户分得清选哪一枚，不做语义判定
 * （语义判定在 `/qr/resolve`，选完之后才查）。
 */
fun qrScanLabelFor(raw: String): String {
    val t = raw.trim()
    return when {
        t.isEmpty() -> Str.s(R.string.qr_result_empty)
        t.contains("/q/u/") -> Str.s(R.string.qr_scan_label_user)
        t.contains("/q/g/") -> Str.s(R.string.qr_scan_label_group)
        t.contains("/q/l/") -> Str.s(R.string.qr_scan_label_login)
        else -> qrUnknownDomain(t)?.let { Str.s(R.string.qr_scan_label_url, it) }
            ?: (if (t.length > 20) t.take(20) + "…" else t)
    }
}
