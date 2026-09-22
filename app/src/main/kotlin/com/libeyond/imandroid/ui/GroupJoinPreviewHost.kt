package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.libeyond.imandroid.data.qrGroupActionFor
import com.libeyond.imandroid.sdk.api.QrGroupCard
import com.libeyond.imandroid.ui.screens.GroupJoinPreviewScreen

/**
 * 加群预览页接线层。纯展示 + 转发，**不在这里发 join 请求**——[onSubmit] 交给 [QrRouteHost]：
 * 它要先把本页从组合里摘掉、再异步发请求（对齐 iOS `submitTapped`：先 `popViewControllerAnimated:NO`
 * 再 `dispatch_async` 回调），本页自己的 `rememberCoroutineScope()` 在摘掉的那一刻就会被取消，
 * 拿来发请求会被半路打断。
 */
@Composable
internal fun GroupJoinPreviewHost(
    card: QrGroupCard,
    onSubmit: (hello: String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val action = remember(card) { qrGroupActionFor(card) }
    GroupJoinPreviewScreen(card = card, action = action, onSubmit = onSubmit, onBack = onBack)
}
