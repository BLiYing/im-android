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
import com.libeyond.imandroid.sdk.http.ApiException
import com.libeyond.imandroid.ui.components.IMConfirmDialog
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.screens.AutoDeleteScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 本会话当前的自动删除档位（单聊资料页 / 群资料页的行右值）。读本地库那一列：`conv_update`（别的设备改的）、
 * 会话列表刷新、本机保存后的就地写入都落在这一列，所以右值随之**就地刷新**，不用各页自己订阅帧。
 */
@Composable
fun rememberConvAutoDelete(client: IMClient, convId: String, initial: Int): Int {
    val flow = remember(convId) { client.repo.observeAutoDelete(client.uid.orEmpty(), convId).map { it.sec }.distinctUntilChanged() }
    return flow.collectAsState(initial = initial).value
}

/**
 * 会话级档位选择页（AUTO_DELETE_DESIGN §3.3 C），单聊资料页与群资料页共用：选中新档位（>0 且 ≠ 当前）先弹说明性确认
 * （单聊 / 群两套正文，主按钮「开启」，**非 destructive**——v2 不追溯，不会立即删历史；取消不改任何东西）
 * → `PUT …/settings`（带 `auto_delete_sec`）→ 成功后返回。关闭直接保存。
 * 失败：`300204` → toast `autodel.group_admin_only`，其余 `autodel.save_failed`；选中态回滚、留在本页。
 *
 * @param canSet 能不能改（[AutoDelete.canSet]）。群里非群主 / 管理员为 false：整页只读，点任意行 toast
 *   `autodel.group_admin_only`，**不发 PUT**。
 */
@Composable
fun ConvAutoDeletePage(client: IMClient, convId: String, current: Int, isGroup: Boolean, canSet: Boolean, onBack: () -> Unit) {
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
                .onFailure { shown = current; toast = Str.s(AutoDelete.saveFailedRes((it as? ApiException)?.code)) }
            busy = false
        }
    }

    BackHandler(onBack = onBack)
    AutoDeleteScreen(
        current = shown,
        footer = stringResource(AutoDelete.footerRes(isGroup)),
        onSelect = { sec ->
            when {
                busy -> Unit
                sec == current -> onBack()
                AutoDelete.needsConfirm(current, sec) -> confirming = sec
                else -> save(sec)
            }
        },
        onBack = onBack,
        onLockedTap = if (canSet) null else ({ toast = Str.s(R.string.autodel_group_admin_only) }),
    )
    confirming?.let { sec ->
        IMConfirmDialog(
            title = stringResource(R.string.autodel_confirm_title),
            message = Str.s(AutoDelete.confirmMessageRes(isGroup), AutoDelete.period(sec)),
            confirmText = stringResource(R.string.autodel_confirm_action),
            destructive = false,
            onConfirm = { confirming = null; save(sec) },
            onDismiss = { confirming = null },
        )
    }
    toast?.let { IMToast(it) { toast = null } }
}
