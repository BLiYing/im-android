package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.QrGroupAction
import com.libeyond.imandroid.data.qrGroupActionLabel
import com.libeyond.imandroid.data.qrGroupActionNote
import com.libeyond.imandroid.sdk.api.QrGroupCard
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMTextField
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 加群预览页（G3，对齐 iOS `IMGroupJoinPreviewViewController`）：群头像/名/人数/邀请人 + 简介 +
 * 附言输入（需审批时）+ 主按钮（按 [action] 变文案/可用态）。替换旧的兜底 alert——弹窗装不下头像、
 * 也不是「页」。
 */
@Composable
fun GroupJoinPreviewScreen(
    card: QrGroupCard,
    action: QrGroupAction,
    onSubmit: (hello: String) -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    var hello by remember(card.groupId) { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(c.groupedBackground)
            .systemBarsPadding().verticalScroll(rememberScrollState()),
    ) {
        IMTopBar(title = stringResource(R.string.qr_action_join), onLeft = onBack, showDivider = false)

        Column(
            Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val fallbackName = stringResource(R.string.common_group_chat)
            IMAvatar(
                card.name.ifBlank { fallbackName }, seed = card.groupId, avatarUrl = card.avatarUrl, size = 72.dp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                card.name.ifBlank { fallbackName },
                style = MaterialTheme.typography.titleLarge,
                color = c.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (card.inviterNickname.isNotEmpty()) {
                    pluralStringResource(
                        R.plurals.qr_preview_meta_invited, card.memberCount, card.memberCount, card.inviterNickname,
                    )
                } else {
                    pluralStringResource(R.plurals.common_people_count, card.memberCount, card.memberCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
            )

            if (card.intro.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    card.intro,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            if (action == QrGroupAction.APPLY) {
                Spacer(Modifier.height(24.dp))
                IMTextField(
                    value = hello,
                    onValueChange = { hello = it },
                    label = stringResource(R.string.qr_preview_hello_header),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.qr_action_apply_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            } else {
                qrGroupActionNote(card)?.let { note ->
                    Spacer(Modifier.height(18.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            IMPrimaryButton(
                qrGroupActionLabel(action),
                onClick = { onSubmit(hello) },
                enabled = action != QrGroupAction.DISABLED,
            )
        }
    }
}
