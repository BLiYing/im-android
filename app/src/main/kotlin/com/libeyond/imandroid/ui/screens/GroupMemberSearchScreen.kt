package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.GroupMemberSearch
import com.libeyond.imandroid.sdk.api.GroupMember
import com.libeyond.imandroid.ui.components.IMSearchField
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 群成员搜索页（大群专用）。**只负责"看"**——去抖/翻页/去重口径在
 * [GroupMemberSearch]（纯函数，有单测）与接线层 `GroupMemberSearchHost`，这里只画。
 *
 * 三种空态是三回事，不能都写「没有匹配」（同 iOS `refreshEmptyState`）：还没打字 = 提示总人数，
 * 打了字但没命中 = 无匹配，请求失败 = 网络错误（**保留上一次结果**，不清空成"查无此人"，
 * 那会让用户以为搜错了而不是网断了——本页交由调用方决定 `results` 是否清空）。
 */
@Composable
fun GroupMemberSearchScreen(
    totalMembers: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<GroupMember>,
    hasMore: Boolean,
    loading: Boolean,
    failed: Boolean,
    onLoadMore: () -> Unit,
    onPickMember: (GroupMember) -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = stringResource(R.string.group_member_search), onLeft = onBack, showDivider = false)
        IMSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.group_member_search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space2),
        )
        if (results.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        failed -> stringResource(R.string.group_picker_search_failed)
                        query.isNotBlank() -> stringResource(R.string.group_picker_no_match)
                        else -> stringResource(R.string.group_member_search_hint, totalMembers)
                    },
                    color = c.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(results, key = { _, m -> m.userId }) { idx, m ->
                    // 滚到底自动续拉：搜 "big" 能命中几千人，结果本身就是长列表，不该让人点几十次
                    if (GroupMemberSearch.shouldAutoLoadMore(idx, results.size, hasMore, loading)) {
                        LaunchedEffect(results.size) { onLoadMore() }
                    }
                    MemberRow(m, onClick = { onPickMember(m) }, onLongClick = {})
                }
                if (hasMore) {
                    item {
                        // 末尾这行保留为失败重试入口（自动续拉遇错会停下，没有它只能退出重搜）
                        Box(Modifier.fillMaxWidth().clickable { onLoadMore() }.padding(14.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (loading) stringResource(R.string.common_loading) else stringResource(R.string.common_load_more_results),
                                color = c.accent,
                            )
                        }
                    }
                }
            }
        }
    }
}
