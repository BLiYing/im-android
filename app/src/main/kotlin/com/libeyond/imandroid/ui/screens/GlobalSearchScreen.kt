package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ChatSearch
import com.libeyond.imandroid.data.GlobalSearch
import com.libeyond.imandroid.data.SettingsSearchEntry
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.sdk.api.FriendEntry
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMSearchField
import com.libeyond.imandroid.ui.components.IMSectionHeader
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 首页全局搜索页（对齐 iOS `IMGlobalSearchViewController`）：会话 / 联系人 / 聊天记录 / 设置四组本地结果，
 * 有关键词时末尾恒显「搜索用户「x」」入口（在线找人 / 加好友）。纯展示，数据与动作全经参数注入。
 */
@Composable
fun GlobalSearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    convs: List<ConversationEntity>,
    friends: List<FriendEntry>,
    records: List<GlobalSearch.RecordHit>,
    /** 设置项命中（[com.libeyond.imandroid.data.SettingsSearchIndex.hits]）；空 = 不出「设置」分组。 */
    settings: List<SettingsSearchEntry>,
    titleOf: (ConversationEntity) -> String,
    onOpenConv: (ConversationEntity) -> Unit,
    onOpenFriend: (FriendEntry) -> Unit,
    onOpenRecord: (GlobalSearch.RecordHit) -> Unit,
    onOpenSetting: (SettingsSearchEntry) -> Unit,
    onSearchUser: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val keyword = query.trim()
    Column(Modifier.fillMaxSize().background(c.groupedBackground).systemBarsPadding()) {
        IMTopBar(title = stringResource(R.string.common_search), onLeft = onBack)
        IMSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.search_global_placeholder),
            modifier = Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space2),
        )
        if (keyword.isEmpty()) return@Column
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (convs.isNotEmpty()) {
                item("h-conv") { IMSectionHeader(stringResource(R.string.search_section_conversations)) }
                items(convs.size, key = { "c-" + convs[it].convId }) { i ->
                    val conv = convs[i]
                    val title = titleOf(conv)
                    ResultRow(
                        name = title, seed = if (conv.isGroup) conv.convId else conv.peerUid.ifBlank { conv.convId },
                        avatarUrl = conv.avatarUrl, title = title, keyword = keyword,
                        // iOS 群行副标题是「N 人」；本端会话表不存成员数（在群资料表里），暂不显示。
                        subtitle = "",
                        onClick = { onOpenConv(conv) },
                    )
                }
            }
            if (friends.isNotEmpty()) {
                item("h-friend") { IMSectionHeader(stringResource(R.string.search_section_contacts)) }
                items(friends.size, key = { "f-" + friends[it].userId }) { i ->
                    val f = friends[i]
                    ResultRow(
                        name = f.displayName, seed = f.userId, avatarUrl = f.avatarUrl, title = f.displayName,
                        keyword = keyword, subtitle = stringResource(R.string.search_section_contacts),
                        onClick = { onOpenFriend(f) },
                    )
                }
            }
            if (records.isNotEmpty()) {
                item("h-rec") { IMSectionHeader(stringResource(R.string.search_section_records)) }
                items(records.size, key = { "r-" + records[it].conv.convId + "-" + records[it].msg.convSeq }) { i ->
                    val r = records[i]
                    val title = titleOf(r.conv)
                    ResultRow(
                        name = title, seed = if (r.conv.isGroup) r.conv.convId else r.conv.peerUid.ifBlank { r.conv.convId },
                        avatarUrl = r.conv.avatarUrl, title = title, keyword = "",
                        subtitle = r.snippet, subtitleKeyword = keyword,
                        onClick = { onOpenRecord(r) },
                    )
                }
            }
            if (settings.isNotEmpty()) {
                item("h-settings") { IMSectionHeader(stringResource(R.string.settings_title)) }
                items(settings.size, key = { "s-" + settings[it].id }) { i ->
                    SettingsResultRow(settings[i], keyword) { onOpenSetting(settings[i]) }
                }
            }
            item("h-user") { IMSectionHeader(stringResource(R.string.search_section_users)) }
            item("user") {
                ResultRow(
                    name = stringResource(R.string.search_user_avatar_initial), seed = "__user_search__", avatarUrl = "",
                    title = stringResource(R.string.search_user_row_title, keyword), keyword = "",
                    subtitle = stringResource(R.string.search_user_row_subtitle),
                    onClick = onSearchUser,
                )
            }
        }
    }
}

@Composable
private fun ResultRow(
    name: String,
    seed: String,
    avatarUrl: String,
    title: String,
    keyword: String,
    subtitle: String,
    onClick: () -> Unit,
    subtitleKeyword: String = "",
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column {
        Row(
            Modifier.fillMaxWidth().background(c.surface).clickable(onClick = onClick)
                .padding(horizontal = d.space4, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IMAvatar(displayName = name, seed = seed, avatarUrl = avatarUrl, size = 44.dp)
            Spacer(Modifier.width(d.space3))
            Column(Modifier.weight(1f)) {
                Text(
                    text = highlighted(title, keyword), color = c.textPrimary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = highlighted(subtitle, subtitleKeyword), color = c.textSecondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** 命中词用强调色 + 柔底高亮（口径同会话内搜索，区间来自 [ChatSearch.matchRanges]）。 */
@Composable
internal fun highlighted(text: String, keyword: String): AnnotatedString {
    val c = IMTheme.colors
    val accent = c.accent
    val soft = c.accentSoft
    return remember(text, keyword, accent, soft) {
        val ranges = ChatSearch.matchRanges(text, keyword)
        if (ranges.isEmpty()) return@remember AnnotatedString(text)
        buildAnnotatedString {
            var at = 0
            for (r in ranges) {
                append(text.substring(at, r.first))
                withStyle(SpanStyle(color = accent, background = soft)) { append(text.substring(r.first, r.last + 1)) }
                at = r.last + 1
            }
            append(text.substring(at))
        }
    }
}
