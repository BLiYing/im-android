package com.libeyond.imandroid.data

/**
 * 会话 ID 的本地推导（PROTOCOL：单聊 `u_<a>_u_<b>`，a、b 按**普通字符串比较**升序）。
 *
 * 与后端 `p2pConvID`、iOS `IMConversationID`、Web `convIdFor` 同构。**不能按数值排序**：
 * uid 是 10 位随机数字，但系统账号 777000 只有 6 位，数值序与字典序在这里给出不同结果
 * （实测 `u_5205766476_u_777000` 就是字典序）。
 *
 * 抽成纯函数是为了被单测钉住：分享选择页「最近聊天 / 我的好友」两处的勾选态靠它对上，
 * 算错了的表现是「同一个人勾两次、发两份」，编译与 UI 都看不出来。
 */
object ConvIds {
    fun privateOf(myUid: String, peerUid: String): String {
        val (a, b) = if (myUid <= peerUid) myUid to peerUid else peerUid to myUid
        return "u_${a}_u_$b"
    }
}
