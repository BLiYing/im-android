package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.SelectedAvatarChip
import com.libeyond.imandroid.ui.theme.IMTheme
import kotlinx.coroutines.launch

private val CELL_WIDTH = 64.dp
/** 目标多时网格自己滚，面板不顶满屏（9 个约三行）。 */
private val GRID_MAX_HEIGHT = 260.dp

/**
 * 多选「发送(N)」后的确认面板（SHARE_PICKER_UX_SKETCH ④，Android 用 ModalBottomSheet）：
 * 标题「分别发送给（N）」+ 取消；头像 + 名字网格，每个带 ✕（删到 0 个由调用方关面板）；
 * 一行内容预览（[preview] 空串不画）；「发送」。这一步是为了防止误发到群。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SharePickConfirmSheet(
    targets: List<ConversationEntity>,
    preview: String,
    onRemove: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun hideThen(after: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion { after() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.pageBackground) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = d.space4).padding(bottom = d.space4)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.share_picker_confirm_title, targets.size, targets.size),
                    color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.common_cancel), color = c.accent, fontSize = 16.sp,
                    modifier = Modifier.clickable { hideThen(onDismiss) }.padding(d.space2),
                )
            }
            Spacer(Modifier.height(d.space3))
            FlowRow(Modifier.fillMaxWidth().heightIn(max = GRID_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                targets.forEach { t ->
                    val item = t.toStripItem()
                    SelectedAvatarChip(
                        item, stringResource(R.string.group_create_remove_member, item.name),
                        modifier = Modifier.padding(vertical = 6.dp), cellWidth = CELL_WIDTH,
                    ) { onRemove(t.convId) }
                }
            }
            if (preview.isNotBlank()) {
                Spacer(Modifier.height(d.space3))
                Text(
                    preview,
                    color = c.textSecondary, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusMenu))
                        .background(c.surface).padding(horizontal = d.space3, vertical = 10.dp),
                )
            }
            Spacer(Modifier.height(d.space4))
            IMPrimaryButton(
                text = stringResource(R.string.common_send),
                onClick = { hideThen(onSend) },
                modifier = Modifier.fillMaxWidth(),
                enabled = targets.isNotEmpty(),
            )
        }
    }
}
