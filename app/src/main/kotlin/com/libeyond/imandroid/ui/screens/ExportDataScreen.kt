package com.libeyond.imandroid.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.DownloadProgress
import com.libeyond.imandroid.data.ExportRules
import com.libeyond.imandroid.data.ExportView
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.ui.components.IMPrimaryButton
import com.libeyond.imandroid.ui.components.IMRowDivider
import com.libeyond.imandroid.ui.components.IMSectionFooter
import com.libeyond.imandroid.ui.components.IMSectionHeader
import com.libeyond.imandroid.ui.components.IMSettingsGroup
import com.libeyond.imandroid.ui.components.IMSwitchRow
import com.libeyond.imandroid.ui.components.IMTopBar
import com.libeyond.imandroid.ui.theme.IMTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** 配置态的表单值（密码 / 开关 / 格式勾选），由 `ExportDataHost` 持有。 */
data class ExportForm(
    val password: String = "",
    val includeMedia: Boolean = false,
    val html: Boolean = true,
    val json: Boolean = true,
)

/**
 * 导出我的数据（DATA_EXPORT_DESIGN §1 / 草图 Android 栏）：纯展示，状态在 `ExportDataHost`。
 * 视觉沿用隐私与安全页同款分组卡 / 行高 / 页脚；密码框同修改密码页 / 注销账号页。
 */
@Composable
fun ExportDataScreen(
    view: ExportView?,
    form: ExportForm,
    onFormChange: (ExportForm) -> Unit,
    /** 内联错误（密码错等）；空 = 不显示。 */
    error: String,
    submitting: Boolean,
    downloading: Boolean,
    /** 下载字节进度；null = 刚开始还没有数据（按未知总长度的不定长显示）。 */
    downloadProgress: DownloadProgress?,
    cancelling: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
    /** 下载中的「取消」：中断下载并删半截文件。 */
    onCancelDownload: () -> Unit,
    onRetry: () -> Unit,
    /** 首次读取失败的原因（[view] 为 null 时显示）与重试。 */
    loadError: String,
    onReload: () -> Unit,
    onBack: () -> Unit,
) {
    val d = IMTheme.dimens
    Column(Modifier.fillMaxSize().background(IMTheme.colors.groupedBackground).statusBarsPadding()) {
        IMTopBar(title = stringResource(R.string.ps_row_export_data), onLeft = onBack)
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            when (view) {
                null -> LoadingOrError(loadError, onReload)
                is ExportView.Config -> ConfigBody(view, form, onFormChange, error, submitting, onStart)
                is ExportView.Running -> RunningBody(view, cancelling, onCancel)
                is ExportView.Done -> DoneBody(view, downloading, downloadProgress, onDownload, onCancelDownload)
                is ExportView.Cooldown -> CooldownBody(view)
                ExportView.Failed -> FailedBody(onRetry)
            }
            Spacer(Modifier.height(d.sectionGap))
        }
    }
}

@Composable
private fun LoadingOrError(loadError: String, onReload: () -> Unit) {
    if (loadError.isEmpty()) return
    val d = IMTheme.dimens
    Spacer(Modifier.height(d.sectionGap))
    ErrorLine(loadError)
    Spacer(Modifier.height(d.space4))
    IMPrimaryButton(stringResource(R.string.export_retry), onReload, Modifier.padding(horizontal = d.space4))
}

@Composable
private fun ConfigBody(
    view: ExportView.Config,
    form: ExportForm,
    onFormChange: (ExportForm) -> Unit,
    error: String,
    submitting: Boolean,
    onStart: () -> Unit,
) {
    val d = IMTheme.dimens
    val canStart = ExportRules.canStart(form.password, form.html, form.json, submitting)
    val focus = remember { FocusRequester() }
    if (view.expiredNote) IMSectionFooter(stringResource(R.string.export_expired), Modifier.padding(top = d.space3))
    IMSectionFooter(stringResource(R.string.export_intro), Modifier.padding(top = d.space3))
    Spacer(Modifier.height(d.space3))
    IMSettingsGroup {
        IMSwitchRow(
            title = stringResource(R.string.export_media_title),
            checked = form.includeMedia,
            onCheckedChange = { onFormChange(form.copy(includeMedia = it)) },
            enabled = !submitting,
        )
    }
    IMSectionFooter(stringResource(R.string.export_media_footer))
    IMSectionHeader(stringResource(R.string.export_format_header))
    IMSettingsGroup {
        CheckRow(stringResource(R.string.export_format_html), form.html, !submitting) { onFormChange(form.copy(html = it)) }
        IMRowDivider(d.space4)
        CheckRow(stringResource(R.string.export_format_json), form.json, !submitting) { onFormChange(form.copy(json = it)) }
    }
    Spacer(Modifier.height(d.sectionGap))
    IMSettingsGroup {
        PasswordRow(
            value = form.password, onValueChange = { onFormChange(form.copy(password = it)) },
            placeholder = stringResource(R.string.export_password_placeholder),
            isError = false, enabled = !submitting, focusRequester = focus,
            imeAction = ImeAction.Done, onImeAction = { if (canStart) onStart() },
        )
    }
    if (error.isNotEmpty()) ErrorLine(error)
    Spacer(Modifier.height(d.space4 + d.space1))
    IMPrimaryButton(
        text = stringResource(R.string.export_start), onClick = onStart,
        modifier = Modifier.padding(horizontal = d.space4), enabled = canStart, loading = submitting,
    )
}

@Composable
private fun RunningBody(view: ExportView.Running, cancelling: Boolean, onCancel: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    Spacer(Modifier.height(d.sectionGap))
    IMSettingsGroup {
        Column(Modifier.fillMaxWidth().padding(d.space4), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.export_running), color = c.textPrimary, style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(
                progress = { ExportRules.progressFraction(view.done, view.total) },
                modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.separator,
            )
            Text(
                Str.s(R.string.export_progress, view.done, view.total),
                color = c.textSecondary, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    IMSectionFooter(stringResource(R.string.export_running_footer))
    Spacer(Modifier.height(d.space4))
    DangerOutlineButton(stringResource(R.string.export_cancel), enabled = !cancelling, onClick = onCancel)
}

@Composable
private fun DoneBody(view: ExportView.Done, downloading: Boolean, progress: DownloadProgress?, onDownload: () -> Unit, onCancelDownload: () -> Unit) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val ctx = LocalContext.current
    val summary = Str.s(R.string.export_done_summary, Formatter.formatFileSize(ctx, view.sizeBytes), view.convs, view.msgs)
    Spacer(Modifier.height(d.sectionGap))
    // 下载中只留下载卡（草图「下载中」）：完成卡与「文件将于…删除」脚注隐藏，与 iOS 一致。
    if (!downloading) IMSettingsGroup {
        Column(Modifier.fillMaxWidth().padding(d.space4), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.export_done_title), color = c.textPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(summary, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (!downloading && view.expiresAt > 0) {
        val at = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(view.expiresAt))
        IMSectionFooter(Str.s(R.string.export_done_expires, at))
    }
    Spacer(Modifier.height(d.space4))
    if (downloading) {
        DownloadingBlock(progress)
        Spacer(Modifier.height(d.space3))
        DangerOutlineButton(stringResource(R.string.common_cancel), enabled = true, onClick = onCancelDownload)
    } else {
        IMPrimaryButton(stringResource(R.string.export_download), onDownload, Modifier.padding(horizontal = d.space4))
    }
}

/** 下载中（草图「下载中」）：标题 + 进度条（总长度未知则不定长）+ 体积文案 + 醒目提醒；「下载」按钮此时隐藏。 */
@Composable
private fun DownloadingBlock(progress: DownloadProgress?) {
    val c = IMTheme.colors
    val d = IMTheme.dimens
    val ctx = LocalContext.current
    IMSettingsGroup {
        Column(Modifier.fillMaxWidth().padding(d.space4), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.export_downloading), color = c.textPrimary, style = MaterialTheme.typography.bodyLarge)
            if (progress != null && progress.known) {
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.separator)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.separator)
            }
            val done = Formatter.formatFileSize(ctx, progress?.done ?: 0L)
            Text(
                if (progress != null && progress.known) Str.s(R.string.export_download_progress, done, Formatter.formatFileSize(ctx, progress.total))
                else Str.s(R.string.export_download_progress_unknown, done),
                color = c.textSecondary, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Text(
        stringResource(R.string.export_download_keep_open),
        color = c.danger, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = d.space4, vertical = d.space3),
    )
}

@Composable
private fun CooldownBody(view: ExportView.Cooldown) {
    val d = IMTheme.dimens
    Spacer(Modifier.height(d.space3))
    IMSectionFooter(Str.s(R.string.export_cooldown, view.hours))
    Spacer(Modifier.height(d.sectionGap))
    IMPrimaryButton(
        stringResource(R.string.export_start), {}, Modifier.padding(horizontal = d.space4), enabled = false,
    )
}

@Composable
private fun FailedBody(onRetry: () -> Unit) {
    val d = IMTheme.dimens
    Spacer(Modifier.height(d.sectionGap))
    ErrorLine(stringResource(R.string.export_failed))
    Spacer(Modifier.height(d.space3))
    IMPrimaryButton(stringResource(R.string.export_retry), onRetry, Modifier.padding(horizontal = d.space4))
}

@Composable
private fun ErrorLine(text: String) {
    val d = IMTheme.dimens
    Text(
        text = text, color = IMTheme.colors.danger, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = d.space4 * 2, end = d.space4 * 2, top = d.space2),
    )
}

/** 勾选行（草图「☑ 网页」）：整行可点，Material Checkbox。 */
@Composable
private fun CheckRow(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val d = IMTheme.dimens
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .defaultMinSize(minHeight = d.settingsRowHeight)
            .padding(start = d.space4, end = d.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = IMTheme.colors.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.padding(end = d.space2))
    }
}

/** 草图「取消导出」：白底 + 危险色描边 / 文字，高 48 与主按钮同。 */
@Composable
private fun DangerOutlineButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val c = IMTheme.colors
    val shape = RoundedCornerShape(IMTheme.dimens.radiusCard)
    Box(
        Modifier.padding(horizontal = IMTheme.dimens.space4).fillMaxWidth().height(48.dp)
            .background(c.cardBackground, shape).border(1.dp, c.separator, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) c.danger else c.textTertiary)
    }
}
