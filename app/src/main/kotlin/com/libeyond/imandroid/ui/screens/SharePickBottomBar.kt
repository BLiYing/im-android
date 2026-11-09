package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.Forward
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.ui.components.SelectedAvatarStrip
import com.libeyond.imandroid.ui.components.StripItem
import com.libeyond.imandroid.ui.theme.IMTheme

/** 底栏高度固定：空态（提示文案）与有选中（头像条）同高，列表不跳（草图 ①「已选栏常驻」）。 */
private val BAR_MIN_HEIGHT = 72.dp
private val SEND_HEIGHT = 34.dp

/** 选择页一行对应的头像条条目（主页、子页、确认面板同一口径）。 */
internal fun ConversationEntity.toStripItem(): StripItem =
    StripItem(convId, Forward.titleOf(this), if (isGroup) convId else peerUid.ifBlank { convId }, avatarUrl)

/**
 * 分享选择页多选态的底部已选栏（SHARE_PICKER_UX_SKETCH ①④）：
 * 按勾选顺序横排头像（复用建群页的 [SelectedAvatarStrip]），点头像或 ✕ 取消勾选；右侧「发送(N)」。
 * 什么都没选时显示「选择最多 9 个好友或群聊」+ 置灰的「发送」。主页与两个子页显示同一条。
 */
@Composable
internal fun SharePickBottomBar(
    selected: List<ConversationEntity>,
    /** 一次最多选几个目标（server-config `forward_max_targets`），只用于空态提示文案。 */
    maxTargets: Int,
    onRemove: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Column(modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        Row(
            Modifier.fillMaxWidth().heightIn(min = BAR_MIN_HEIGHT).padding(vertical = d.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected.isEmpty()) {
                Text(
                    pluralStringResource(R.plurals.share_picker_bar_hint, maxTargets, maxTargets),
                    color = c.textTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = d.space4),
                )
            } else {
                SelectedAvatarStrip(
                    items = selected.map { it.toStripItem() },
                    onRemove = onRemove,
                    removeDescription = { name -> Str.s(R.string.group_create_remove_member, name) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = d.space3),
                    followTail = true,
                    tapToRemove = true,
                )
            }
            SharePickSendButton(
                text = if (selected.isEmpty()) stringResource(R.string.common_send)
                else pluralStringResource(R.plurals.forward_picker_send_count, selected.size, selected.size),
                enabled = selected.isNotEmpty(),
                onClick = onSend,
                modifier = Modifier.padding(end = d.space4),
            )
        }
    }
}

@Composable
private fun SharePickSendButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Box(
        modifier
            .height(SEND_HEIGHT)
            .clip(RoundedCornerShape(d.radiusMenu))
            .background(if (enabled) c.accent else c.neutralControl)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = d.space4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) c.onAccent else c.textTertiary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
