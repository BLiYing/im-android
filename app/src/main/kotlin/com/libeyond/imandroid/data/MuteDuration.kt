package com.libeyond.imandroid.data

import androidx.annotation.StringRes
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/**
 * 定时免打扰时长菜单的 5 个选项（NOTIFICATIONS_P1_DESIGN §4.1，已拍板③④：不做「自定义到某天」）。
 * `mute_until` 用**调用方传入的当前时间**算出绝对时间戳再发给服务端（§4.1 原话）。
 */
enum class MuteDuration(@StringRes private val labelRes: Int, private val millis: Long?) {
    OneHour(R.string.mute_1h, 60 * 60 * 1000L),
    EightHours(R.string.mute_8h, 8 * 60 * 60 * 1000L),
    OneDay(R.string.mute_1d, 24 * 60 * 60 * 1000L),
    SevenDays(R.string.mute_7d, 7 * 24 * 60 * 60 * 1000L),
    /** 永久：`mute_until = 0`（PROTOCOL §6.10）。 */
    Forever(R.string.common_permanent, null),
    ;

    val label: String get() = Str.s(labelRes)

    /** 这一项对应的绝对 `mute_until`（毫秒，0 = 永久）。 */
    fun muteUntil(nowMs: Long = System.currentTimeMillis()): Long = millis?.let { nowMs + it } ?: 0L
}
