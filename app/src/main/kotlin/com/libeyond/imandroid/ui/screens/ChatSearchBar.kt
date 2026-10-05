package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Calendar
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleUserRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.components.topBarChrome
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 会话内搜索的**顶部搜索栏**（替换聊天页标题栏，SEARCH_DESIGN §4）。
 *
 * 结构照 iOS `IMChatViewController+Search.m` 的 `buildSearchTopBar`：🔍 + 输入框 + 「取消」。
 * **不做液态玻璃**（Android 上手搓只会得到形似神不似的半透明模糊，`docs/UI_PARITY_IOS.md §4`），
 * 外框与 `IMTopBar` 共用 `topBarChrome`（跟随页面底色、同最小高），切换时列表不跳。
 */
@Composable
internal fun ChatSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val focus = remember { FocusRequester() }
    // 进搜索态就把键盘叫出来——多一步「再点一下输入框」是纯粹的浪费。
    // **等一帧再要焦点**：焦点节点要等这一帧挂上，太早要会抛 IllegalStateException
    // 而 runCatching 会把它吞掉——表现成"搜索框出来了但键盘不弹"，不报错、只是别扭。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        // 失败**不静默**：吞掉的话表现只是"搜索框出来了但键盘不弹"，日志里一个字都没有，
        // 下次排查要从头猜（CONVENTIONS §4.2：忽略错误要写明理由，这里的理由是"不该拦住搜索本身"）。
        runCatching { focus.requestFocus() }
            .onFailure { IMLog.tag("IM.Search").w("search_focus_failed", "err" to it.javaClass.simpleName) }
    }
    Column {
        Row(
            modifier = Modifier.topBarChrome(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = d.inputControl)
                    .clip(RoundedCornerShape(IMTheme.appearance.bubbleRadius))
                    .background(c.surfaceElevated) // 栏已跟页面同色，输入框要比栏亮一档才分得出
                    .padding(horizontal = d.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    imageVector = Lucide.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    colorFilter = ColorFilter.tint(c.textTertiary),
                )
                Spacer(Modifier.width(d.space2))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.chat_search_placeholder), color = c.textTertiary, fontSize = 15.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(color = c.textPrimary, fontSize = 15.sp),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        // 搜索是**边打边搜**的（防抖在 ChatSearchController 里），
                        // 回车只用来收键盘，不重复触发一次查询
                        keyboardActions = KeyboardActions(onSearch = {}),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) {
                    Image(
                        imageVector = Lucide.X,
                        contentDescription = stringResource(R.string.chat_clear_ok),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onQueryChange("") },
                        colorFilter = ColorFilter.tint(c.textTertiary),
                    )
                }
            }
            Spacer(Modifier.width(d.space3))
            Text(
                text = stringResource(R.string.common_cancel),
                color = c.accent,
                fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onCancel),
            )
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
    }
}

/**
 * **底部命中导航条**（替换输入栏）：计数 + 📅 日历 + 👤 来自（仅群聊）+ ▲ 更旧 / ▼ 更新。
 *
 * 位置照 iOS（`buildSearchNavBar` + `buildCountPill`）放底部而不是跟着搜索框——
 * 手指在底部，翻命中是高频动作。无命中时按钮置灰（`UI.md` 要求每个列表都有空态）。
 * 📅/👤 两枚钮 iOS 也摆在这一条（不在顶部搜索框里），逐字对齐。
 *
 * @param notice 需要如实说的一句话（离线降级 / 搜索失败），空串则不占位。
 * @param showsFromFilter 「来自」筛选是否可用——仅群聊（单聊没有多个发送者，筛选没有意义）。
 * @param fromLabel 当前筛选的发件人显示名；`null` = 没在筛选，此时画 👤 入口而不是"来自: X ✕"胶囊。
 */
@Composable
internal fun ChatSearchNavBar(
    label: String,
    notice: String,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    showsFromFilter: Boolean,
    fromLabel: String?,
    onOpenFrom: () -> Unit,
    onClearFrom: () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        if (notice.isNotEmpty()) {
            Text(
                text = notice,
                color = c.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.surface)
                    .padding(start = d.space4, end = d.space4, top = 6.dp),
            )
        }
        if (fromLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(c.surface).padding(start = d.space4, end = d.space4, top = 6.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.accentSoft)
                        .clickable(onClick = onClearFrom)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.chat_search_from_token, fromLabel),
                        color = c.accent,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(4.dp))
                    Image(
                        imageVector = Lucide.X,
                        contentDescription = stringResource(R.string.chat_search_clear_sender),
                        modifier = Modifier.size(12.dp),
                        colorFilter = ColorFilter.tint(c.accent),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = d.inputBarHeight)
                .background(c.surface)
                .padding(horizontal = d.space4, vertical = d.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label, color = c.textSecondary, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                NavArrow(Lucide.Calendar, stringResource(R.string.chat_search_date_jump_dialog_title), enabled = true, onOpenCalendar)
                if (showsFromFilter && fromLabel == null) {
                    Spacer(Modifier.width(d.space2))
                    NavArrow(Lucide.CircleUserRound, stringResource(R.string.chat_search_by_member), enabled = true, onOpenFrom)
                }
                Spacer(Modifier.width(d.space2))
                NavArrow(Lucide.ChevronUp, stringResource(R.string.chat_search_prev), canPrev, onPrev)
                Spacer(Modifier.width(d.space2))
                NavArrow(Lucide.ChevronDown, stringResource(R.string.chat_search_next), canNext, onNext)
            }
        }
    }
}

@Composable
private fun NavArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Box(
        modifier = Modifier
            .size(d.inputControl)
            .clip(CircleShape)
            .background(c.pageBackground)
            // 置灰用透明度，不换一套颜色令牌（同 iOS `actionEnabled=NO` 自动降透明）
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(c.accent),
        )
    }
}
