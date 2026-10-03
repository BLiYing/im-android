package com.libeyond.imandroid.data

/**
 * 个人资料页的关系取值。
 *
 * 「群成员行 → 资料卡种子」的 `relationOf` / `seedOf` 已随成员资料页并入 `MemberProfileHost`
 * （群成员 / 通讯录 / 收藏 / 扫码统一走聊天信息页，与聊天头像同一条路）一并删除。
 */
object MemberProfile {

    /** 端上自造的一档：看自己。与服务端 friend status 的取值空间不重叠。 */
    const val RELATION_SELF = "self"
}
