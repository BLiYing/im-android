package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.libeyond.imandroid.data.LanguageStore
import com.libeyond.imandroid.rtc.RtcCall
import com.libeyond.imandroid.ui.screens.LanguageScreen

/** 语言设置页的状态桥（对齐 `PrivacySecurityHost` 等其它 Me 二级页的分层：状态在 Host，Screen 纯展示）。 */
@Composable
fun LanguageHost(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val pref by LanguageStore.pref.collectAsState()
    LanguageScreen(
        current = pref,
        onSelect = {
            LanguageStore.setPref(it)
            // 通话 Kit 挂着时（正在通话或悬浮窗常驻）立即生效，不用等下次登录重建。
            RtcCall.updateLocale()
        },
        onBack = onBack,
    )
}
