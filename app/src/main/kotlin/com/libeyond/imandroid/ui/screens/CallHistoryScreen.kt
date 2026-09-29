package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowDownLeft
import com.composables.icons.lucide.ArrowUpRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Phone
import com.composables.icons.lucide.Video
import com.imrtc.engine.IMCallHistoryRecord
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.CallHistory
import com.libeyond.imandroid.data.CallRecord
import com.libeyond.imandroid.data.DisplayName
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.components.TimeFormat
import com.libeyond.imandroid.ui.theme.IMTheme

/** 顶部「全部 / 未接」分段控制的两个态（CALL_HISTORY_DESIGN.md §3.5）。 */
internal enum class CallHistoryTab { All, Missed }

/**
 * 「我 ▸ 最近通话」画面（CALL_HISTORY_DESIGN.md，UX 稿 `CALL_HISTORY_UX_SKETCH.html` §02/§03）。
 *
 * 这一页几乎不自己算东西——分组、身份、过滤都是 [CallHistoryHost] 已经算好交下来的
 * （同 `FavoritesScreen` 的分工），这里只管画。
 */
@Composable
internal fun CallHistoryScreen(
    /** 我的 uid：判定未接 / 呼出呼入 / 1v1 副标题的视角，都靠它与 `record.caller` 比较得出。 */
    me: String,
    tab: CallHistoryTab,
    onTabChange: (CallHistoryTab) -> Unit,
    /** 已按当前 tab 过滤、按天分组的记录（倒序）。 */
    groups: List<CallHistory.DayGroup>,
    /** 一条通话记录都没有（区分"还没有通话记录"与"当前 tab 过滤后为空"）。 */
    noneAtAll: Boolean,
    loading: Boolean,
    failed: Boolean,
    errorText: String,
    hasMore: Boolean,
    /** 当前 tab 已显示条数：「加载更多」项按它换 key，翻完一页还在屏上时不会再触发一次。 */
    loadedCount: Int,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    nameOf: (IMCallHistoryRecord) -> String,
    avatarOf: (IMCallHistoryRecord) -> String,
    onOpen: (IMCallHistoryRecord) -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = stringResource(R.string.ios_settings_row_recent_calls), onLeft = onBack)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item(key = "tabs") {
                CallHistorySegment(
                    listOf(stringResource(R.string.call_history_tab_all), stringResource(R.string.call_history_tab_missed)),
                    tab.ordinal,
                ) { i -> onTabChange(CallHistoryTab.entries[i]) }
            }
            when {
                loading && noneAtAll -> item { Hint(stringResource(R.string.common_loading)) }
                failed && noneAtAll -> item { CallHistoryRetryHint(errorText, onRetry) }
                noneAtAll -> item { EmptyCallHistory() }
                groups.isEmpty() -> item { Hint(stringResource(R.string.call_history_empty)) }
                else -> groups.forEachIndexed { idx, g ->
                    item(key = "day-$idx") { DayHeader(g.label) }
                    items(g.records, key = { it.callId }) { r ->
                        CallHistoryRowView(r, me, nameOf(r), avatarOf(r), onClick = { onOpen(r) })
                    }
                }
            }
            if (hasMore && !noneAtAll) item(key = "more-$loadedCount") { LoadMore(onLoadMore) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * 「全部 / 未接」分段控制（UX 稿 `.seg`）：两段**等宽铺满整行**，同 iOS `UISegmentedControl` / Web 的做法。
 * 不用 [SegTabBar]——那是给详情页四个页签按内容宽度排的，只有两段时右侧留一大片空白。
 */
@Composable
private fun CallHistorySegment(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = IMTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(9.dp)).background(c.subtleFill).padding(2.dp),
    ) {
        titles.forEachIndexed { i, t ->
            val on = i == selected
            Box(
                Modifier.weight(1f)
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(7.dp)) else Modifier)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) c.surfaceElevated else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                // 选中/未选中同为主文字色，只靠字重 + 药丸区分（同 MediaSeg / iOS IMLiquidSegmentedControl）
                Text(
                    t, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

/** 日期分组头（今天 / 昨天 / 具体日期，UX 稿 §03：12 Bold 次要色）。 */
@Composable
private fun DayHeader(label: String) {
    val c = IMTheme.colors
    Text(
        label, color = c.textSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().background(c.groupedBackground).padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

/**
 * 一行通话记录（UX 稿 §03「行规格」）：头像 + 方向箭头 + 名字 + 类型图标 + 状态文案 + 右侧时间。
 * 未接来电（[CallHistory.isMissed]）整行（箭头 + 名字）变 `danger` 色，与聊天气泡未接来电同一个令牌。
 */
@Composable
private fun CallHistoryRowView(record: IMCallHistoryRecord, me: String, name: String, avatarUrl: String, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val missed = CallHistory.isMissed(record, me)
    val video = record.mediaType == "video"
    val outgoing = record.caller == me
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.surface).clickable(onClick = onClick)
                .padding(horizontal = d.space4, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val displayName = if (record.isGroup) name.ifBlank { groupSummary(record) } else name.ifBlank { DisplayName.UNNAMED }
            // 群行与会话列表同一口径画群头像（种子用群会话 id，颜色与会话列表一致）；本机没有这个群时退回首字色块
            IMAvatar(
                displayName = displayName,
                seed = if (record.isGroup) record.chatGroupId else CallHistory.peerUid(record, me),
                avatarUrl = avatarUrl, size = 40.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        imageVector = if (outgoing) Lucide.ArrowUpRight else Lucide.ArrowDownLeft,
                        contentDescription = null, modifier = Modifier.size(13.dp),
                        colorFilter = ColorFilter.tint(if (missed) c.danger else c.textSecondary),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        displayName, color = if (missed) c.danger else c.textPrimary,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        imageVector = if (video) Lucide.Video else Lucide.Phone,
                        contentDescription = null, modifier = Modifier.size(12.dp), colorFilter = ColorFilter.tint(c.textSecondary),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (record.isGroup) groupSummary(record) else subtitleOf(record, outgoing),
                        color = c.textSecondary, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 分组头已给出日期，行内只写 HH:mm（UX 稿 §02 示例，同 iOS/Web）
            Text(
                TimeFormat.bubbleTime(record.startedAtMs), color = c.textSecondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Top),
            )
        }
        Box(Modifier.fillMaxWidth().padding(start = 66.dp).height(0.5.dp).background(c.separator))
    }
}

/**
 * 1v1 行的副标题：复用 [CallRecord] 的 reason 文案判定（`d>0` → 时长；否则按 reason 查表），不新造一套。
 * **红色与否不看这里**——那是 [CallHistory.isMissed] 的简单两字段判据（设计文档 §0.7 把两件事分开管）：
 * `CallRecord.render` 只覆盖 cancel/reject/no_answer/busy/offline 五种 reason 的措辞，
 * 未接来电这个"事实"本页按自己的规则单独判，两者不必一一对应。
 */
private fun subtitleOf(record: IMCallHistoryRecord, outgoing: Boolean): String {
    val content = CallRecord.Content(record.callId, record.mediaType == "video", record.reason, record.durationSec, isGroup = false)
    return CallRecord.render(content, viewerIsSender = outgoing).text
}

/** 群通话行副标题/兜底名：「群语音通话 · N人」/「群视频通话 · N人」。 */
@Composable
private fun groupSummary(record: IMCallHistoryRecord): String {
    val kind = stringResource(if (record.mediaType == "video") R.string.call_record_kind_video else R.string.call_record_kind_voice)
    return stringResource(R.string.call_history_group_subtitle, kind, CallHistory.groupMemberCount(record))
}

/** 一条通话记录都没有（UX 稿 §04-A）。 */
@Composable
private fun EmptyCallHistory() {
    val c = IMTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 96.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(Lucide.Phone, null, Modifier.size(44.dp), colorFilter = ColorFilter.tint(c.textTertiary))
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.call_history_empty), color = c.textTertiary,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
    }
}

/** 首屏拉失败且手里没旧数据（UX 稿 §04-B：已加载部分保留，仅底部提示；这里是完全没加载出来的档）。 */
@Composable
private fun CallHistoryRetryHint(errorText: String, onRetry: () -> Unit) {
    val c = IMTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(errorText, color = c.textPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.common_tap_to_retry), color = c.accent, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable(onClick = onRetry),
        )
    }
}
