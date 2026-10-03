package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.GroupInfo
import com.libeyond.imandroid.sdk.api.ServerConfig

/**
 * 群成员满员时的「可升级为大群」提示行（对齐 iOS `showsUpgradeHintRow`；**只告知、不给升级按钮**——升级不可逆、要管理员）。
 *
 * 三条件缺一不可：不是超级群；`server-config` 已拉到且 `supergroup_enabled` 且上限 > 0（**拉不到就不显示**，
 * 猜一个上限会把没满的群说成满了——别硬编码 500/2000，Web 早期就栽过）；成员总数 ≥ 上限。
 * 总数取 `member_count`，没有再取成员表长度。
 */
object GroupUpgradeHint {
    fun shows(info: GroupInfo, cfg: ServerConfig?): Boolean {
        if (info.isSuper || cfg == null || !cfg.supergroupEnabled || cfg.maxGroupMembers <= 0) return false
        val total = if (info.memberCount > 0) info.memberCount else info.members.size
        return total >= cfg.maxGroupMembers
    }
}
