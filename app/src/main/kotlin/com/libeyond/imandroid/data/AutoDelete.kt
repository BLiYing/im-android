package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/**
 * 自动删除消息的纯判据（设计 `IMServer/docs/design/AUTO_DELETE_DESIGN.md` §2，三端一字不差；
 * 共用向量 `docs/conformance/auto_delete.json`，本端测试 `AutoDeleteTest`）。
 *
 * 个人级、只影响本机视图：`sec` 是某会话（或账号默认）的档位秒数，0 = 关。
 */
object AutoDelete {
    /** 档位白名单（关 / 1 天 / 1 周 / 30 天）。选择页的顺序也是这个。 */
    val TIERS: List<Int> = listOf(0, 86_400, 604_800, 2_592_000)

    /** 前台常驻扫描间隔（设计 §3.2）。 */
    const val SWEEP_INTERVAL_MS = 30_000L

    fun isValid(sec: Int): Boolean = sec in TIERS

    /** 含等号：`ts + sec*1000 <= now`；`sec <= 0` 恒不过期。 */
    fun expired(ts: Long, sec: Int, now: Long): Boolean = sec > 0 && ts + sec * 1000L <= now

    /** `ts <= floorTs` 即过期；关闭时 0（= 不过滤）。 */
    fun floorTs(sec: Int, now: Long): Long = if (sec > 0) maxOf(0L, now - sec * 1000L) else 0L

    /**
     * 改会话档位前要不要先弹 destructive 确认（§3.3 C）：开启，或缩短到更短的档位。
     * 关闭、放宽直接保存（不复活已清掉的消息，也不删任何东西）。
     */
    fun needsConfirm(old: Int, new: Int): Boolean = new > 0 && (old <= 0 || new < old)

    /** 档位的显示词：关闭 / 1 天 / 1 周 / 1 个月（非白名单值按「关闭」显示）。 */
    fun label(sec: Int): String = Str.s(
        when (sec) {
            86_400 -> R.string.autodel_day
            604_800 -> R.string.autodel_week
            2_592_000 -> R.string.autodel_month
            else -> R.string.common_off
        },
    )

    /** 确认框正文里的 `{period}`（`autodel.day/week/month`）。 */
    fun period(sec: Int): String = label(sec)

    /** 丢掉已过期的项（入库过滤用）；关闭时原样返回。 */
    fun <T> dropExpired(items: List<T>, sec: Int, now: Long, tsOf: (T) -> Long): List<T> =
        if (sec <= 0) items else items.filterNot { expired(tsOf(it), sec, now) }
}
