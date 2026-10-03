package com.libeyond.imandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R

/**
 * 备注名编辑弹窗——**页内弹窗，不跳页**，对齐 iOS `IMChatDetailViewController+Actions.m`
 * 的 `editRemark`：当场 present 一个带输入框的 `UIAlertController`。
 *
 * 单聊详情页（[ChatDetailHost]）与用户资料页（[UserProfileHost]）共用同一个弹窗：
 * 此前只有资料页接了它，详情页那行「备注名」靠先跳整页资料页、再在那一页里点第二次
 * 才弹得出来——比 iOS 多绕一层。抽成共享组件而不是两边各写一套，两处状态各自持有
 * （谁弹、草稿是什么），这里只管画。
 */
@Composable
fun RemarkEditDialog(
    current: String,
    /** 占位符用对方真实昵称，让用户知道不设备注时会显示什么（同 iOS）。 */
    placeholderNickname: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_edit_remark_placeholder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chat_detail_remark_alert_message))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = { Text(placeholderNickname) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(draft.trim()) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
