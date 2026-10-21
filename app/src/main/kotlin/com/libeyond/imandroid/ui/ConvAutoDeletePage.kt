package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.AutoDelete
import com.libeyond.imandroid.data.setConvAutoDelete
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.IMClient
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.AutoDeleteScreen
import kotlinx.coroutines.launch

/**
 * 本会话当前的自动删除档位（单聊资料页 / 群资料页的行右值）。读本地库那一列：`conv_update`（别的设备改的）、
 * 会话列表刷新、本机保存后的就地写入都落在这一列，所以右值随之**就地刷新**，不用各页自己订阅帧。
 */
@Composable
fun rememberConvAutoDelete(client: IMClient, convId: String, initial: Int): Int {
    val flow = remember(convId) { client.repo.observeAutoDelete(client.uid.orEmpty(), convId) }
    return flow.collectAsState(initial = initial).value
}

/**
 * 会话级档位选择页（AUTO_DELETE_DESIGN §3.3 C），单聊资料页与群资料页共用：选中 → 开启 / 缩短先弹 destructive 确认
 * （取消不改任何东西）→ `PUT …/settings`（带 `auto_delete_sec`）→ 成功后本地立即清理并返回。
 * 关闭 / 放宽直接保存。失败 toast `autodel.save_failed`，选中态回滚、留在本页。
 */
@Composable
fun ConvAutoDeletePage(client: IMClient, convId: String, current: Int, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var shown by remember(convId, current) { mutableStateOf(current) } // 随最新档位重置（conv_update 到达时刷新勾选）
    var confirming by remember(convId) { mutableStateOf<Int?>(null) }
    var busy by remember(convId) { mutableStateOf(false) }
    var toast by remember(convId) { mutableStateOf<String?>(null) }

    fun save(sec: Int) {
        if (busy) return
        busy = true
        shown = sec // 乐观打勾；失败回滚
        scope.launch {
            runCatchingCancellable { client.messages.setConvAutoDelete(convId, sec) }
                .onSuccess { onBack() }
                .onFailure { shown = current; toast = Str.s(R.string.autodel_save_failed) }
            busy = false
        }
    }

    BackHandler(onBack = onBack)
    AutoDeleteScreen(
        current = shown,
        footer = stringResource(R.string.autodel_conv_footer),
        onSelect = { sec ->
            when {
                busy || sec == current -> if (sec == current) onBack()
                AutoDelete.needsConfirm(current, sec) -> confirming = sec
                else -> save(sec)
            }
        },
        onBack = onBack,
    )
    confirming?.let { sec ->
        IMConfirmDialog(
            title = stringResource(R.string.autodel_confirm_title),
            message = Str.s(R.string.autodel_confirm_message, AutoDelete.period(sec)),
            confirmText = stringResource(R.string.autodel_confirm_action),
            onConfirm = { save(sec) },
            onDismiss = { confirming = null },
        )
    }
    toast?.let { IMToast(it) { toast = null } }
}
