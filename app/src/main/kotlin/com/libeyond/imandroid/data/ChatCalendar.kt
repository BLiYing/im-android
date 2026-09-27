package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.api.ConvCalendarDay

/**
 * 聊天页 📅 日历跳转的纯逻辑（对齐 iOS `IMChatViewController+Search.m` 的
 * `searchCalTapped`/`handleDateJumpKind:day:`，Compose 那侧不用 `UICalendarView`，
 * 改用 Material3 `DatePicker`——这里只管"选中一天之后该跳到哪条"，UI 另见 `ChatCalendarDialog`）。
 *
 * **为什么"跳到某天"必须区分本地完整/有缺口**：目标那天的消息可能整段落在本地缺口里，
 * 只查本地库会跳过缺口静默落到别的日子——这正是本会话仓一直在防的"少了要说，不能错"
 * （`OFFLINE_BACKLOG_DESIGN.md` §4.9）。本地完整时直接查本地（[MessageRepository.firstConvSeqAtOrAfter]）；
 * 有缺口时改问服务端日历接口，这里的 [firstSeqOnOrAfter] 就是解那份服务端天表用的。
 */
object ChatCalendar {

    const val DAY_MS = 86_400_000L

    /**
     * 一次性向服务端要的日历跨度。
     *
     * **不能照抄 iOS 的「近两年」**：iOS `searchCalTapped` 写的是 `toMs - 730天`，
     * 但服务端 `conversation.MaxCalendarSpan` 只放行约 400 天——iOS 那个请求实际上会被服务端拒绝
     * （`errcode.ParamInvalid "time range too wide"`），是 iOS 那侧一个既有的欠账，不是本端该照搬的目标。
     * 本端取 390 天（留 10 天余量，不踩服务端上限的线）。
     */
    const val QUERY_SPAN_MS = 390L * DAY_MS

    val DEGRADED_NOTICE: String get() = Str.s(R.string.conv_query_offline_calendar)
    val NO_MESSAGE_ON_OR_AFTER: String get() = Str.s(R.string.chat_search_day_no_messages_after)
    val NOTHING_TODAY: String get() = Str.s(R.string.chat_search_today_no_messages_jumped_latest)

    /**
     * 本地日 00:00 对应的 UTC 毫秒。
     *
     * 镜像后端 `ConvDayBuckets` 的整除分桶公式（`internal/store/sqlite_message.go`）：
     * `((timestamp + utcOffsetMs) / dayMs) * dayMs - utcOffsetMs`。两边必须逐字一致——
     * 算法漂了的表现是"点这天跳到了另一天"，界面上看不出错在哪。
     */
    fun dayStartMs(timestampMs: Long, utcOffsetMs: Long): Long =
        Math.floorDiv(timestampMs + utcOffsetMs, DAY_MS) * DAY_MS - utcOffsetMs

    /**
     * 服务端日历天表里，[pickedDayStartMs] 当天或之后**最近一个有消息的天**的 `firstConvSeq`。
     * 那天没有消息就自然退到下一个有消息的日子；表里找不到（超出请求跨度）则回 null，
     * 调用方据此回退到本地查询或如实说"该日期之后无消息"。
     *
     * @param days 须按 `dayStartMs` 升序——服务端本就这样回，见 `ConversationsApi.calendar` 的注释。
     */
    fun firstSeqOnOrAfter(days: List<ConvCalendarDay>, pickedDayStartMs: Long): Long? =
        days.firstOrNull { it.dayStartMs >= pickedDayStartMs }?.firstConvSeq?.takeIf { it > 0 }
}
