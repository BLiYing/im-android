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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
        IMTopBar(title = "加入群聊", onLeft = onBack, showDivider = false)

        Column(
            Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IMAvatar(
                card.name.ifBlank { "群聊" }, seed = card.groupId, avatarUrl = card.avatarUrl, size = 72.dp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                card.name.ifBlank { "群聊" },
                style = MaterialTheme.typography.titleLarge,
                color = c.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (card.inviterNickname.isNotEmpty()) "${card.memberCount} 人 · ${card.inviterNickname}邀请你加入"
                else "${card.memberCount} 人",
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
                    label = "附言（选填）",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "该群需管理员审批",
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
