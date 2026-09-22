package com.libeyond.imandroid.data

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
    QrUserAction.MESSAGE -> "发消息"
    QrUserAction.SELF -> "查看我的资料"
    QrUserAction.BLOCKED -> "查看资料"
    QrUserAction.ADD -> "添加到通讯录"
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
    QrGroupAction.ENTER -> "进入群聊"
    QrGroupAction.APPLY -> "申请加入"
    QrGroupAction.DISABLED -> "无法加入"
    QrGroupAction.JOIN -> "加入群聊"
}

/** 群码不可加入/需审批时的说明文案（可空=不显示）。 */
fun qrGroupActionNote(card: QrGroupCard?): String? {
    if (card == null) return null
    if (card.joined) return null
    if (!card.joinable) {
        return when (card.reason) {
            "full" -> "群成员已达上限，暂时无法加入"
            "banned" -> "你已被移出该群，暂时或永久不可加入"
            "invite_revoked" -> "该群已改为仅管理员可邀请，此邀请已失效"
            else -> null
        }
    }
    if (card.reason == "approval") return "该群需管理员审批"
    return null
}

/** 外来码原文是否 http(s) URL；是则回域名主体（供二次确认高亮），否则回 null。 */
fun qrUnknownDomain(text: String): String? {
    val t = text.trim()
    if (!t.startsWith("http://", ignoreCase = true) && !t.startsWith("https://", ignoreCase = true)) return null
    return runCatching { java.net.URI(t).host }.getOrNull()?.takeIf { it.isNotBlank() }
}
