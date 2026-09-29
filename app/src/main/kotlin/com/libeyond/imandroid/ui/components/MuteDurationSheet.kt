package com.libeyond.imandroid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.MuteDuration

/**
 * 定时免打扰时长菜单（NOTIFICATIONS_P1_DESIGN §4.1/§4.2，草图 04）。**样式沿用既有长按/左滑菜单
 * 的底部弹层 [ActionSheet]**，不另起一套——三个入口共用（会话列表、聊天信息页、「添加例外」选择页）。
 *
 * @param showUnmute 已在免打扰中时最上面多一项红色「取消免打扰」（§4.1：用于「把 8 小时改成永久」
 *   这类调整）。会话列表左滑/长按那条路**不传 true**——已免打扰时那条入口本身就直接是
 *   「取消免打扰」，不弹本菜单（§4.2 入口表）。「添加例外」选择页同样不传：选出来的会话本就还没
 *   免打扰（`Forward.exceptionPickable` 已经把已免打扰的过滤掉了）。
 */
@Composable
fun MuteDurationSheet(
    convTitle: String,
    showUnmute: Boolean,
    onUnmute: () -> Unit,
    onSelect: (MuteDuration) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = buildList {
        if (showUnmute) {
            add(SheetItem(stringResource(R.string.conv_menu_unmute), destructive = true) { onUnmute() })
        }
        MuteDuration.entries.forEach { d -> add(SheetItem(d.label) { onSelect(d) }) }
    }
    ActionSheet(
        title = stringResource(R.string.notif_mute_sheet_title, convTitle),
        items = items,
        onDismiss = onDismiss,
    )
}
