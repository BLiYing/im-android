package com.libeyond.imandroid.ui

import android.net.Uri
import com.libeyond.imandroid.data.ExportSink
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.ExportDownloader
import com.libeyond.imandroid.data.ExportRules
import com.libeyond.imandroid.data.ExportView
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.sdk.api.ExportApi
import com.libeyond.imandroid.sdk.api.ExportJob
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.sdk.protocol.ErrCode
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.ExportDataScreen
import com.libeyond.imandroid.ui.screens.ExportForm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

private const val POLL_MS = 2000L

/**
 * 导出我的数据（`MePage.ExportData`，DATA_EXPORT_DESIGN §1/§5）。
 *
 * 进页读 `exports/latest`（退出重进即恢复进度）；任务进行中每 2 秒轮询，页面离开（协程随组合取消）即停。
 * 下载：先弹系统「另存为」选位置，**选定后**才换一次性下载地址（地址 10 分钟、一次有效，别让用户在选择器里耗掉），
 * 再流式写入所选文件。下载跑在 [IMClient.exportDownloads]（应用作用域），旋转 / 离开页面不中断，重进页面按其状态恢复「下载中」；
 * 只有用户点取消或退出登录才中断并删半截文件；失败 toast `export.download_failed`，按钮仍在可重试。
 */
@Composable
fun ExportDataHost(client: IMClient, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<ExportJob?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf("") }
    // 配置变更 / 进程重建后保留表单（密码不落 saved state）与「重试」「冷却」这类本地态
    var form by rememberSaveable(stateSaver = ExportFormSaver) { mutableStateOf(ExportForm()) }
    var error by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    val dlState by client.exportDownloads.state.collectAsState()
    val dlProgress by client.exportDownloads.progress.collectAsState()
    val downloading = dlState == ExportDownloader.State.Running // 重进页面 / 旋转后据此恢复「下载中」
    var retrying by rememberSaveable { mutableStateOf(false) }
    var cooldownUntil by rememberSaveable { mutableStateOf(0L) }
    var toast by remember { mutableStateOf<String?>(null) }

    /** 读最新任务；成功返回 true。 */
    suspend fun refresh(): Boolean =
        runCatchingCancellable { client.dataExport.latest() }
            .onSuccess { job = it; loaded = true; loadError = "" }
            .onFailure { if (!loaded) loadError = it.userMessage(Str.s(R.string.err_request_failed, "export_latest")) }
            .isSuccess

    LaunchedEffect(Unit) { refresh() }
    // 下载结果 toast：页面在场时消费；页面不在场时结果留着，下次进页补一次
    LaunchedEffect(dlState) {
        when (dlState) {
            ExportDownloader.State.Succeeded -> toast = Str.s(R.string.export_download_done)
            ExportDownloader.State.Failed -> toast = Str.s(R.string.export_download_failed)
            else -> return@LaunchedEffect
        }
        client.exportDownloads.consumeResult()
    }
    // 到点自动切视图：完成态 expires_at / 冷却结束；到点后重取 latest 让服务端给 expired 状态
    var clock by remember { mutableLongStateOf(0L) }
    val changeAt = ExportRules.nextViewChangeAt(job, System.currentTimeMillis(), cooldownUntil)
    LaunchedEffect(changeAt) {
        if (changeAt <= 0) return@LaunchedEffect
        delay((changeAt - System.currentTimeMillis()).coerceAtLeast(0) + 50)
        clock++
        if (job?.state?.isActive != true) refresh()
    }
    val active = job?.state?.isActive == true
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) { delay(POLL_MS); refresh() } // 单次失败静默，下一拍再试；协程随页面离开取消
    }

    fun start() {
        if (!ExportRules.canStart(form.password, form.html, form.json, submitting)) return
        submitting = true
        error = ""
        scope.launch {
            runCatchingCancellable { client.dataExport.start(form.password, form.includeMedia, ExportRules.formats(form.html, form.json)) }
                .onSuccess { job = it; loaded = true; retrying = false; form = form.copy(password = "") }
                .onFailure { e ->
                    val api = e as? ApiException
                    when (api?.code) {
                        ErrCode.WRONG_PASSWORD -> error = Str.s(R.string.err_200002) // 内联红字，输入保留
                        ErrCode.EXPORT_BUSY -> { toast = Str.s(R.string.err_200010); refresh() }
                        ErrCode.EXPORT_COOLDOWN -> {
                            val sec = ExportApi.retryAfterSec(api.data) ?: (ExportRules.COOLDOWN_MS / 1000)
                            cooldownUntil = System.currentTimeMillis() + sec * 1000
                        }
                        else -> error = e.userMessage(Str.s(R.string.err_request_failed, "export_start"))
                    }
                }
            submitting = false
        }
    }

    fun cancel() {
        val id = job?.id ?: return
        if (cancelling) return
        cancelling = true
        scope.launch {
            runCatchingCancellable { client.dataExport.cancel(id) }
                .onFailure { toast = it.userMessage(Str.s(R.string.err_request_failed, "export_cancel")) }
            refresh()
            retrying = false
            cancelling = false
        }
    }

    fun download(uri: Uri) {
        // 进程级下载：不挂在本组合上；已有进行中的（极端：选择器期间重复触发）则丢掉新建的空壳
        if (!client.exportDownloads.start(uri.toString(), job?.id)) scope.launch { ExportSink(ctx).discard(uri.toString()) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) download(uri)
    }

    val view: ExportView? = if (!loaded) null else ExportRules.viewOf(job, cooldownUntil, System.currentTimeMillis().also { clock }, retrying)
    BackHandler(onBack = onBack)
    ExportDataScreen(
        view = view, form = form,
        onFormChange = { form = it; error = "" },
        error = error, submitting = submitting, downloading = downloading, downloadProgress = dlProgress, cancelling = cancelling,
        onStart = ::start, onCancel = ::cancel,
        onDownload = {
            if (!downloading) {
                val c = Calendar.getInstance()
                picker.launch(ExportRules.fileName(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)))
            }
        },
        onCancelDownload = { client.exportDownloads.cancel() },
        onRetry = { retrying = true },
        loadError = loadError,
        onReload = { scope.launch { refresh() } },
        onBack = onBack,
    )
    toast?.let { IMToast(it) { toast = null } }
}

/** 表单的 saved-state 编码：不含密码。 */
private val ExportFormSaver = listSaver<ExportForm, Boolean>(
    save = { listOf(it.includeMedia, it.html, it.json) },
    restore = { ExportForm(includeMedia = it[0], html = it[1], json = it[2]) },
)
