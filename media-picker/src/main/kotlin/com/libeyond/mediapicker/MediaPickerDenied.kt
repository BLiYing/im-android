package com.libeyond.mediapicker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp

/**
 * 相册权限被拒时的**同页空状态**（取代原先降级回系统选择器）。
 *
 * 顶栏与宫格页同一个（[PickerTopBar]），所以用户感觉不到「换了一个页面」，只是相册里没东西、
 * 并被告知为什么。系统对被拒的权限不会再弹窗（iOS 一次、Android 连拒两次后同理），
 * 所以唯一能做的是指路去设置——这里**不再重复请求权限**。
 */
@Composable
internal fun MediaPickerDenied(onCancel: () -> Unit, onOpenSettings: () -> Unit) {
    val s = LocalMediaPickerSkin.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(s.pageBackground)
            .systemBarsPadding(),
    ) {
        PickerTopBar(
            title = stringResource(R.string.mp_album),
            canSwitch = false,
            expanded = false,
            onToggleBuckets = {},
            onCancel = onCancel,
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(s.space4),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Text0 默认 maxLines = 1 + 省略号：说明文字（英文近百字符）会被截成「Photo access is off, so photos…」，
            // 用户看不到「去系统设置允许」这半句。放开到 4 行。
            Text0(stringResource(R.string.mp_denied_message), s.textSecondary, 15.sp, maxLines = 4)
            Spacer(Modifier.height(s.space3))
            Text0(stringResource(R.string.mp_go_settings), s.accent, 15.sp, Modifier.clickable(onClick = onOpenSettings))
        }
    }
}
