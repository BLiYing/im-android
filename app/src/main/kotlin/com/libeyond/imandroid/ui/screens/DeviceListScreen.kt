package com.libeyond.imandroid.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.libeyond.imandroid.data.DeviceDisplay
import com.libeyond.imandroid.sdk.api.DeviceSession
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSectionHeader
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme

/**
 * 已登录设备列表（对齐 iOS `IMDeviceListViewController`）。
 *
 * 以**登录会话(session)**为行，不是「在线连接」：会话持久、可逐台吊销，
 * 「在线」只是它当前有活 WS 连接的展示子态（那颗圆点）。
 *
 * 纯展示：分区、文案由 [DeviceDisplay] 算好传进来，动作由调用方给。
 */
@Composable
fun DeviceListScreen(
    devices: List<DeviceSession>?,   // null = 加载中；空表 = 拉到了但没有
    error: String,
    /** 正在踢的 sid；[REVOKE_ALL] 表示正在「退出其他所有设备」。空串 = 空闲。 */
    revoking: String,
    now: Long,
    onRefresh: () -> Unit,
    onOpenDetail: (DeviceSession) -> Unit,
    onRevokeOthers: () -> Unit,
    onBack: () -> Unit,
) {
    val c = IMTheme.colors
    val d = IMTheme.dimens

    Column(Modifier.fillMaxSize().background(c.groupedBackground).statusBarsPadding()) {
        IMTopBar(
            title = "已登录设备",
            onLeft = onBack,
            actionText = if (devices == null) "刷新中" else "刷新",
            actionEnabled = devices != null,
            onAction = onRefresh,
        )

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when {
                devices == null -> Hint("加载中…")
                error.isNotEmpty() -> Hint(error, danger = true)
                devices.isEmpty() -> Hint("没有已登录的设备")
                else -> {
                    DeviceDisplay.sections(devices).forEach { section ->
                        IMSectionHeader(section.title)
                        IMSettingsGroup {
                            section.devices.forEachIndexed { i, dev ->
                                if (i > 0) IMRowDivider(insetStart = 14.dp + d.deviceRowIcon + 11.dp)
                                DeviceRow(
                                    device = dev,
                                    now = now,
                                    // 本机行不进详情：退出本机 = 退出登录，走「我」页那一行。
                                    onClick = if (dev.current) null else ({ onOpenDetail(dev) }),
                                )
                            }
                        }
                    }
                    if (DeviceDisplay.canRevokeOthers(devices)) {
                        Spacer(Modifier.height(d.sectionGap))
                        IMSettingsGroup {
                            Text(
                                text = if (revoking == REVOKE_ALL) "退出中…" else "退出其他所有设备",
                                color = if (revoking.isEmpty()) c.danger else c.textTertiary,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = revoking.isEmpty(), onClick = onRevokeOthers)
                                    .padding(vertical = 14.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                        IMSectionFooter("退出后该设备需重新登录。若你不认识某台设备，请退出它并尽快修改密码。")
                    }
                }
            }
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

/** 「退出其他所有设备」进行中的哨兵值（不是任何真实 session_id）。 */
const val REVOKE_ALL = "__others__"

@Composable
private fun Hint(text: String, danger: Boolean = false) {
    Text(
        text = text,
        color = if (danger) IMTheme.colors.danger else IMTheme.colors.textSecondary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(IMTheme.dimens.space4 * 2),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** 一行设备：emoji 图标 + 名字 +（本机才有的）「当前」标 + 在线圆点 + 状态行。 */
@Composable
private fun DeviceRow(device: DeviceSession, now: Long, onClick: (() -> Unit)?) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(d.deviceRowIcon).clip(RoundedCornerShape(d.radiusDeviceIcon))
                .background(c.groupedBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(DeviceDisplay.platformEmoji(device.platform), fontSize = 19.sp)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = DeviceDisplay.deviceName(device),
                    color = c.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (device.current) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "当前",
                        color = c.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(c.accentSoft)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (device.online) c.online else c.textTertiary),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = DeviceDisplay.statusLine(device, now),
                    color = c.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(d.space2))
            Image(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                colorFilter = ColorFilter.tint(c.textTertiary),
            )
        }
    }
}
