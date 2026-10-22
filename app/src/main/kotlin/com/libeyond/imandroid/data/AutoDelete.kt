package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ErrCode

/**
 * 自动删除消息的纯判据（设计 `IMServer/docs/design/AUTO_DELETE_DESIGN.md` §2，三端一字不差；
 * 共用向量 `docs/conformance/auto_delete.json`，本端测试 `AutoDeleteTest`）。
 *
 * v2 会话级共享：`sec` 是某会话（或账号默认）的档位秒数，0 = 关；`from` 是会话级计时起点（毫秒，不追溯）。
 */
object AutoDelete {
    private const val MIN = 60
    private const val HOUR = 3_600
    private const val DAY = 86_400

    /** 选择页的固定行（关 / 2 分钟 / 1 天 / 1 周 / 1 个月）；其余档位走「自定义」弹窗。 */
    val PRESETS: List<Int> = listOf(0, 2 * MIN, DAY, 7 * DAY, 30 * DAY)

    /** 「自定义」弹窗的 18 档（升序；月 = 30 天、年 = 365 天）。 */
    val CUSTOM_TIERS: List<Int> =
        listOf(2 * MIN, HOUR) + (1..6).map { it * DAY } + (1..3).map { it * 7 * DAY } +
            (1..6).map { it * 30 * DAY } + listOf(365 * DAY)

    /** 档位白名单 = 关 + 18 档（与向量 `valid_sec` 同序）。 */
    val TIERS: List<Int> = listOf(0) + CUSTOM_TIERS

    /** 前台常驻扫描间隔（设计 §3.2）。 */
    const val SWEEP_INTERVAL_MS = 30_000L

    fun isValid(sec: Int): Boolean = sec in TIERS

    /** 解析层防御：`sec>0` 却 `from<=0` 是脏数据（服务端恒下发 from = 系统消息时间+1 > 0），按关闭，避免从 1970 起追溯清空。 */
    fun effectiveSec(sec: Int, from: Long): Int = if (sec > 0 && from <= 0L) 0 else sec

    /** 含等号：`sec>0 && ts>=from && ts + sec*1000 <= now`；`sec <= 0` 恒不过期，`from` 之前的消息永不过期（不追溯）。 */
    fun expired(ts: Long, sec: Int, from: Long, now: Long): Boolean = sec > 0 && ts >= from && ts + sec * 1000L <= now

    /** 过期窗口 `[from, now - sec*1000]`（含两端）；关闭或尚未满期（hi < lo）为 null。清理 SQL 的 `BETWEEN` 就取它。 */
    fun window(sec: Int, from: Long, now: Long): LongRange? {
        if (sec <= 0) return null
        val hi = now - sec * 1000L
        return if (hi < from) null else from..hi
    }

    /** 档位变更系统消息（timestamp = [sysTs]）对应的 `from`：`sysTs + 1`（通知自己不过期）；关闭为 0。 */
    fun fromAfterSystemMessage(sysTs: Long, sec: Int): Long = if (sec > 0) sysTs + 1 else 0L

    /**
     * 改会话档位前要不要先弹说明确认（§3.3 C，v2 不追溯所以不再 destructive）：新档位>0 且 ≠ 当前
     * （开启、改档位都弹一次）；关闭、没变不弹。
     */
    fun needsConfirm(old: Int, new: Int): Boolean = new > 0 && new != old

    /** 谁能设（§0）：单聊任一方；群聊仅群主 / 管理员（普通成员 UI 只读，服务端另有 `300204`）。 */
    fun canSet(isGroup: Boolean, iAmManager: Boolean): Boolean = !isGroup || iAmManager

    /** 保存失败的 toast：`300204`（越权 / 被撤管理员的在途请求）→ 仅管理员可设；其余 → 设置失败。 */
    fun saveFailedRes(code: Int?): Int =
        if (code == ErrCode.NO_GROUP_PERMISSION) R.string.autodel_group_admin_only else R.string.autodel_save_failed

    /** 确认框正文（`{period}` = [period]）：单聊 / 群两套，不再 destructive。 */
    fun confirmMessageRes(isGroup: Boolean): Int =
        if (isGroup) R.string.autodel_confirm_message_group else R.string.autodel_confirm_message

    /** 会话档位页页脚：单聊 / 群两套（账号默认页用 `autodel_default_footer`）。 */
    fun footerRes(isGroup: Boolean): Int = if (isGroup) R.string.autodel_conv_footer_group else R.string.autodel_conv_footer

    /** `sec` 或 `from` 变了且新 `sec>0` → 扫该会话（§3.2）；不再有「开启即清历史」，扫出来的只是窗口里的消息。 */
    fun needsSweep(oldSec: Int, oldFrom: Long, newSec: Int, newFrom: Long): Boolean =
        newSec > 0 && (newSec != oldSec || newFrom != oldFrom)

    /** 时长单位（对应 `autodel.unit.*` 复数键）。 */
    enum class Unit(val plural: Int) {
        MINUTE(R.plurals.autodel_unit_minute), HOUR(R.plurals.autodel_unit_hour), DAY(R.plurals.autodel_unit_day),
        WEEK(R.plurals.autodel_unit_week), MONTH(R.plurals.autodel_unit_month), YEAR(R.plurals.autodel_unit_year),
    }

    /** 折算（优先级自上而下）：整除 365 天→年；30 天→月；7 天→周；1 天→天；1 小时→小时；否则分钟。 */
    fun unitOf(sec: Int): Pair<Unit, Int> = when {
        sec % (365 * DAY) == 0 -> Unit.YEAR to sec / (365 * DAY)
        sec % (30 * DAY) == 0 -> Unit.MONTH to sec / (30 * DAY)
        sec % (7 * DAY) == 0 -> Unit.WEEK to sec / (7 * DAY)
        sec % DAY == 0 -> Unit.DAY to sec / DAY
        sec % HOUR == 0 -> Unit.HOUR to sec / HOUR
        else -> Unit.MINUTE to sec / MIN
    }

    /** 「自定义」行是否承载当前档位：开启且不是 5 个固定档之一（✓ 与右值时长都归它）。 */
    fun isCustomSelected(sec: Int): Boolean = sec > 0 && sec !in PRESETS

    /** 档位的显示词：关闭 / 2 分钟 / 3 天 / 1 年…（任意正整数秒均可折算；<=0 按「关闭」）。 */
    fun label(sec: Int): String {
        if (sec <= 0) return Str.s(R.string.common_off)
        val (u, n) = unitOf(sec)
        return Str.p(u.plural, n, n)
    }

    /** 确认框正文里的 `{period}`（`autodel.unit.*` 折算结果）。 */
    fun period(sec: Int): String = label(sec)

    /** 丢掉已过期的项（入库过滤用）；关闭时原样返回。 */
    fun <T> dropExpired(items: List<T>, sec: Int, from: Long, now: Long, tsOf: (T) -> Long): List<T> =
        if (sec <= 0) items else items.filterNot { expired(tsOf(it), sec, from, now) }
}
