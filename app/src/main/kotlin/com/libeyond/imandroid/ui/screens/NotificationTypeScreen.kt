package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.NotifTypeSettings
import com.libeyond.imandroid.data.db.ConversationEntity
import com.libeyond.imandroid.ui.components.IMAvatar
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSectionHeader
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSettingsRow
import com.libeyond.imandroid.ui.components.IMSwitchRow
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

private val EXCEPTION_AVATAR = 40.dp
private val ADD_EXCEPTION_ICON = 26.dp

/**
 * 私聊 / 群聊通知子页（NOTIFICATIONS_DESIGN §2.3 + NOTIFICATIONS_P1_DESIGN §2），两种类型共用同一份结构
 * （`group` 只决定标题、选择页脚注与例外过滤，其余一模一样，故不拆成两个文件）。
 *
 * 「显示通知」关掉时「消息预览」「提示音」两行**灰置但保留**（不藏）——藏掉的话开关下面整块
 * 会往上跳，同 `IMActionRow`/`PRIVACY_SECURITY_DESIGN §2.5` 的既有口径。「消息预览」是真开关，
 * 关掉「显示通知」时它本身也没有意义可言，故顺手把它 disable 掉（而不是留着可点但不生效）——
 * 「提示音」是导航行，禁用会让用户没法提前选好，保留可点、只降饱和度。
 *
 * 「例外」组**常驻不藏**（P1 已拍板 ②，反悔 P0 commit `82c1687` 的「没有例外就整组不显示」）：
 * 加了「添加例外」这一行后整组永远不为空。
 */
@Composable
internal fun NotificationTypeScreen(
    group: Boolean,
    settings: NotifTypeSettings,
    /** 该类型下 muted=true 的会话（`data/NotificationExceptions.kt`），按最后消息时间倒序。 */
    exceptions: List<ConversationEntity>,
    onToggleEnabled: (Boolean) -> Unit,
    onTogglePreview: (Boolean) -> Unit,
    onOpenSound: () -> Unit,
    /** 「添加例外」行——打开会话选择页（`ui/NotificationSettingsHost.kt` 复用 `ForwardPickerScreen`）。 */
    onAddException: () -> Unit,
    /** 左滑「取消免打扰」（复用现有 `PUT /conversations/{id}/settings`）。 */
    onUnmute: (ConversationEntity) -> Unit,
    /** 点行进入该会话。 */
    onOpenChat: (ConversationEntity) -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    // 当前敞着的那一行（同 BlockedUsersScreen 的受控约定：滑开第二行时第一行自动收起）
    var openedId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(
            title = stringResource(if (group) R.string.notif_type_group_title else R.string.notif_type_private_title),
            onLeft = onBack,
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(d.sectionGap))
            IMSettingsGroup {
                IMSwitchRow(
                    title = stringResource(R.string.notif_type_enabled),
                    checked = settings.enabled,
                    onCheckedChange = onToggleEnabled,
                )
                IMRowDivider(insetStart = d.space4)
                IMSwitchRow(
                    title = stringResource(R.string.notif_type_preview),
                    checked = settings.preview,
                    onCheckedChange = onTogglePreview,
                    enabled = settings.enabled,
                )
            }
            IMSectionFooter(stringResource(R.string.notif_type_preview_footer))

            Spacer(Modifier.height(d.cardGap))
            IMSectionHeader(stringResource(R.string.notif_sound_title))
            IMSettingsGroup {
                IMSettingsRow(
                    title = stringResource(R.string.notif_type_sound),
                    onClick = onOpenSound,
                    rightValue = soundLabel(settings.sound),
                    muted = !settings.enabled,
                )
            }

            // 例外组常驻（P1 已拍板 ②）：「添加例外」恒在最上面，没有免打扰会话时这一组只剩这一行
            Spacer(Modifier.height(d.cardGap))
            IMSectionHeader(stringResource(R.string.notif_section_exceptions))
            IMSettingsGroup {
                AddExceptionRow(onClick = onAddException)
                exceptions.forEach { conv ->
                    IMRowDivider(insetStart = d.space4 + EXCEPTION_AVATAR + d.space3)
                    val opened = openedId == conv.convId
                    SwipeActionRow(
                        actions = listOf(
                            SwipeAction(stringResource(R.string.conv_menu_unmute), c.danger) {
                                openedId = null
                                onUnmute(conv)
                            },
                        ),
                        opened = opened,
                        onOpenedChange = { open -> openedId = if (open) conv.convId else null },
                    ) {
                        // 敞着的行点内容只收起；合着时点了才进会话（同 BlockedUsersScreen）
                        ExceptionRow(conv, onClick = if (opened) ({ openedId = null }) else ({ onOpenChat(conv) }))
                    }
                }
            }
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

/**
 * 「添加例外」行（NOTIFICATIONS_P1_DESIGN §2 + 草图 02A）：绿色文字 + 圆形 ＋ 号，恒排在例外组最上面。
 * 「绿色」= 本 App 的强调色（`IMTheme.colors.accent` 本就是绿，`ui/theme/Tokens.kt`），不是另起一个颜色。
 */
@Composable
private fun AddExceptionRow(onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = d.settingsRowHeight)
            .padding(horizontal = d.space4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(ADD_EXCEPTION_ICON).clip(CircleShape).background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                imageVector = Lucide.Plus,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                colorFilter = ColorFilter.tint(c.onAccent),
            )
        }
        Spacer(Modifier.width(d.space3))
        Text(
            text = stringResource(R.string.notif_exceptions_add),
            color = c.accent,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ExceptionRow(conv: ConversationEntity, onClick: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val title = conv.peerRemark.ifBlank { conv.title }.ifBlank { conv.convId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 不透明底色不能省：左滑时底下的红格要被内容层盖住
            .background(c.cardBackground)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 60.dp)
            .padding(horizontal = d.space4, vertical = d.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IMAvatar(displayName = title, seed = conv.convId, avatarUrl = conv.avatarUrl, size = EXCEPTION_AVATAR)
        Spacer(Modifier.width(d.space3))
        Text(
            text = title,
            color = c.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(d.space2))
        Text(
            text = stringResource(
                if (conv.mentionUnread) R.string.notif_exceptions_muted_mention else R.string.notif_exceptions_muted,
            ),
            color = c.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
