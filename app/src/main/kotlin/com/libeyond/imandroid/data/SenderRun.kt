package com.libeyond.imandroid.data

import java.text.BreakIterator

/**
 * 群聊气泡上方「昵称 + 角色徽标」与**同发送者连续段**的纯逻辑（IMServer `CHAT_UI_SKETCH.html` §1.1）。
 *
 * 连续段口径逐条对齐 iOS `IMChatViewController+DataSource.m` 的 `message:sameSenderRunAs:`：
 * 名字只挂段首、头像只挂段末。本端此前**每条都显名字、从不显角色**（十七条对齐 #15），
 * 连发五条就是五行一模一样的昵称。
 */
object SenderRun {

    /** 参与连续段判定的一行（消息 / 相册 / 待发）的最小信息。 */
    data class Item(
        val sender: String,
        val system: Boolean = false,
        val recalled: Boolean = false,
    )

    /**
     * 两行是否属于同一连续段：同发送者、都不是系统消息、都没撤回。
     *
     * iOS 还判了「同一天」——本端不重复判：`buildChatRows` 在换日处必插日期行，
     * 日期行本身就把段断开了（见 `showsSenderName` 的邻行查找）。
     */
    fun sameRun(a: Item, b: Item): Boolean =
        a.sender == b.sender && !a.system && !b.system && !a.recalled && !b.recalled

    /** 昵称最多显示的字符簇数（§1.1）。 */
    const val NAME_MAX_CLUSTERS = 12

    /**
     * 昵称超长截成前 [max] 个**字符簇** +「…」。
     *
     * 按簇不按 `String.length`：一个 emoji 是两个 UTF-16 单元，按 length 截会把它劈成半个乱码。
     */
    fun clampName(name: String, max: Int = NAME_MAX_CLUSTERS): String {
        val clusters = graphemeClusters(name)
        return if (clusters.size <= max) name else clusters.take(max).joinToString("") + "…"
    }

    /** 昵称旁的角色徽标。普通成员不画。 */
    enum class Badge(val label: String) { Owner("群主"), Admin("管理员") }

    /**
     * 角色取**本群成员表里的当前角色**，拿不到才退回消息上冻结的 `from_role`。
     *
     * 顺序不能反：被撤掉管理员的人，他以前发的消息 `from_role` 仍是 admin，
     * 按消息上的值画就会让一个普通成员顶着「管理员」。成员表明确说是 member 时就不画。
     */
    fun badgeOf(memberRole: String?, fromRole: String?): Badge? {
        val role = memberRole?.takeIf { it.isNotBlank() } ?: fromRole
        return when (role) {
            "owner" -> Badge.Owner
            "admin" -> Badge.Admin
            else -> null
        }
    }
}

/** 按用户感知的「字符」切分（emoji、组合附加符算一个）。 */
internal fun graphemeClusters(s: String): List<String> {
    if (s.isEmpty()) return emptyList()
    val it = BreakIterator.getCharacterInstance()
    it.setText(s)
    val out = ArrayList<String>()
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        out += s.substring(start, end)
        start = end
        end = it.next()
    }
    return out
}
