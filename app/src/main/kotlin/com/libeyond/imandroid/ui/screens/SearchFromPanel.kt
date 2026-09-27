package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.theme.IMTheme

/** 「来自」候选发件人——本会话已发过消息的人，去重、按最近发言时间排（[com.libeyond.imandroid.data.db.MessageDao.distinctSenders]）。 */
data class SearchSenderCandidate(val uid: String, val name: String, val avatarUrl: String)

/**
 * 👤「来自」候选面板：贴在搜索命中导航条上方（对齐 iOS `searchFromPanel`，同款位置，不弹 sheet）。
 *
 * 候选**不是全量群成员表**——没发过言的成员过滤后必 0 命中，列出无意义（iOS `senderCandidatesForConv:`
 * 的取舍，同一条理由）。
 */
@Composable
internal fun SearchFromPanel(
    candidates: List<SearchSenderCandidate>,
    onPick: (SearchSenderCandidate) -> Unit,
) {
    val c = IMTheme.colors
    Column(Modifier.fillMaxWidth().heightIn(max = PANEL_MAX_HEIGHT).background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        if (candidates.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.chat_search_no_senders), color = c.textTertiary, fontSize = 13.sp)
            }
        } else {
            LazyColumn {
                items(candidates, key = { it.uid }) { cand ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(cand) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IMAvatar(displayName = cand.name, seed = cand.uid, avatarUrl = cand.avatarUrl, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            cand.name, color = c.textPrimary, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private val PANEL_MAX_HEIGHT = 280.dp
