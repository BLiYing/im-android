package com.libeyond.imandroid.rtc

import com.libeyond.imandroid.sdk.api.DeletedUser

/** 通话入口的纯规则（不碰 Kit 单例，JVM 单测可直接跑；`RtcCall` 一初始化就拉 Kit 静态）。 */
internal object RtcCallRules {
    /** 群通话被叫剔掉已注销者。 */
    fun liveCallees(uids: List<String>, isDeleted: (String) -> Boolean = DeletedUser::has): List<String> =
        uids.filterNot(isDeleted)
}
