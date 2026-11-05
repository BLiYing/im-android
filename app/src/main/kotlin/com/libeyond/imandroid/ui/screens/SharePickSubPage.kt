package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ContactSection
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.PickEntry
import com.libeyond.imandroid.data.SharePick
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.ui.components.IMSearchField
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.launch

/**
 * 分享选择页的子页「我的好友」/「我的群聊」（SHARE_PICKER_UX_SKETCH ②）：在同一张卡片里切过去，
 * 返回只回主页、**不清已选**。行与主页同一种 [ForwardRow]，勾选态读同一份已选集合（按 convId）。
 *
 * 好友页按拼音 A–Z 分组 + 右侧索引尺（判据同通讯录，[ContactSection]）；群页平铺。
 *
 * @param entries `null` = 还在加载（转圈，不抢答「没有好友」），或 [failed] 时加载失败。
 * @param subtitle 多选态「已选 N/9」，单选态空串。
 */
@Composable
internal fun SharePickSubPage(
    title: String,
    subtitle: String,
    searchPlaceholder: String,
    emptyText: String,
    entries: List<PickEntry>?,
    /** 拉取失败且无离线快照：画「加载失败，点击重试」，点了调 [onRetry]（不吐司）。 */
    failed: Boolean,
    onRetry: () -> Unit,
    exclude: Set<String>,
    alphabetical: Boolean,
    multi: Boolean,
    isChecked: (String) -> Boolean,
    onTap: (ConversationEntity) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var query by remember { mutableStateOf("") }
    val rows = remember(entries, query, exclude) { SharePick.filter(entries.orEmpty(), query, exclude) }
    // 搜索中不分组、不画索引尺（结果是子集，组随打字剧烈变动；同 PickListScreen）
    val groups = remember(rows, alphabetical, query) {
        if (alphabetical && query.isBlank()) ContactSection.group(rows) { Forward.titleOf(it) } else emptyList()
    }
    val titles = remember(groups) { ContactSection.titlesOf(groups) }
    // 每组前有一个标题 item
    val groupStarts = remember(groups) { ContactSection.groupStartIndices(groups.map { it.items.size }, leadingItems = 0) }
    val listState = rememberLazyListState()
    val indexScope = rememberCoroutineScope()

    Column(modifier) {
        IMTopBar(
            title = title,
            subtitle = subtitle,
            onLeft = onBack,
            containerColor = c.pageBackground,
        )
        IMSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = searchPlaceholder,
            modifier = Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space2),
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                entries == null && failed -> Text(
                    stringResource(R.string.share_picker_load_failed_retry),
                    color = c.accent,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp).clickable(onClick = onRetry).padding(d.space3),
                )
                entries == null -> CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
                rows.isEmpty() -> Text(
                    if (query.isNotBlank()) stringResource(R.string.forward_picker_no_match) else emptyText,
                    color = c.textTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    if (groups.isEmpty()) {
                        items(rows, key = { it.convId }) { conv ->
                            ForwardRow(conv, multi, isChecked(conv.convId)) { onTap(conv) }
                        }
                    } else {
                        groups.forEach { g ->
                            item(key = "h-" + g.key) { SharePickHeader(g.key) }
                            items(g.items, key = { it.convId }) { conv ->
                                ForwardRow(conv, multi, isChecked(conv.convId)) { onTap(conv) }
                            }
                        }
                    }
                }
            }
            if (groups.isNotEmpty()) {
                ContactIndexBar(
                    titles = titles,
                    onPick = { i -> groupStarts.getOrNull(i)?.let { indexScope.launch { listState.scrollToItem(it) } } },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}
