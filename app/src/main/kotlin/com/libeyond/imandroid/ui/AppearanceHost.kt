package com.libeyond.imandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.libeyond.imandroid.R
import com.libeyond.imandroid.data.AppearanceStore
import com.libeyond.imandroid.data.ChatThemeId
import com.libeyond.imandroid.data.ChatWallpaper
import com.libeyond.imandroid.ui.components.PushTransition
import com.libeyond.imandroid.ui.screens.AppearanceGridItem
import com.libeyond.imandroid.ui.screens.AppearanceGridScreen
import com.libeyond.imandroid.ui.screens.AppearanceModeScreen
import com.libeyond.imandroid.ui.screens.AppearanceScreen
import com.libeyond.imandroid.ui.screens.AppearanceSliderKind
import com.libeyond.imandroid.ui.screens.AppearanceSliderScreen
import com.libeyond.imandroid.ui.screens.themeName
import com.libeyond.imandroid.ui.screens.wallpaperName
import com.libeyond.imandroid.ui.theme.IMThemeMode

/** 外观里的几层页面（主页 → 主题网格 / 壁纸网格 / 显示模式 / 滑块）。 */
private sealed interface AppearancePage {
    data object Main : AppearancePage
    data object Themes : AppearancePage
    data object Wallpapers : AppearancePage
    data object Mode : AppearancePage
    /** [original] = 进页时的值，「取消」据此还原。 */
    data class Slider(val kind: AppearanceSliderKind, val original: Int) : AppearancePage
}

/**
 * 外观页的状态桥（对齐 iOS `IMAppearanceViewController` 及其四个子页）。
 * 偏好的读写全走 [AppearanceStore]，图标走 [AppIconSwitcher]；Screen 只管画。
 */
@Composable
fun AppearanceHost(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs by AppearanceStore.state.collectAsState()
    var page by remember { mutableStateOf<AppearancePage>(AppearancePage.Main) }
    var appIcon by remember { mutableStateOf(AppIconSwitcher.current(context)) }

    fun pickIcon(choice: AppIconChoice) {
        AppIconSwitcher.select(context, choice)
        appIcon = choice
    }

    fun setSlider(kind: AppearanceSliderKind, v: Int) = AppearanceStore.update {
        if (kind == AppearanceSliderKind.FontSize) it.copy(chatFontSize = v) else it.copy(bubbleRadius = v)
    }

    val toMain = { page = AppearancePage.Main }
    PushTransition(targetState = page, depthOf = { if (it == AppearancePage.Main) 0 else 1 }) { p ->
        when (p) {
            AppearancePage.Main -> {
                BackHandler(onBack = onBack)
                AppearanceScreen(
                    prefs = prefs,
                    appIcon = appIcon,
                    onBack = onBack,
                    onReset = {
                        AppearanceStore.reset()
                        pickIcon(AppIconChoice.DEFAULT)
                    },
                    onPickTheme = { t -> AppearanceStore.update { it.copy(theme = t) } },
                    onOpenThemes = { page = AppearancePage.Themes },
                    onOpenWallpapers = { page = AppearancePage.Wallpapers },
                    onNightModeChange = { on ->
                        AppearanceStore.update { it.copy(mode = if (on) IMThemeMode.Dark else IMThemeMode.System) }
                    },
                    onOpenMode = { page = AppearancePage.Mode },
                    onOpenFontSize = { page = AppearancePage.Slider(AppearanceSliderKind.FontSize, prefs.chatFontSize) },
                    onOpenBubbleRadius = { page = AppearancePage.Slider(AppearanceSliderKind.BubbleRadius, prefs.bubbleRadius) },
                    onAnimationsChange = { on -> AppearanceStore.update { it.copy(animationsEnabled = on) } },
                    onPickIcon = ::pickIcon,
                )
            }
            AppearancePage.Themes -> {
                BackHandler(onBack = toMain)
                AppearanceGridScreen(
                    title = stringResource(R.string.appearance_chat_theme),
                    items = ChatThemeId.entries.map { t ->
                        AppearanceGridItem(t.wire, t, prefs.wallpaper, themeName(t), t == prefs.theme) {
                            AppearanceStore.update { it.copy(theme = t) }
                        }
                    },
                    onBack = toMain,
                )
            }
            AppearancePage.Wallpapers -> {
                BackHandler(onBack = toMain)
                AppearanceGridScreen(
                    title = stringResource(R.string.general_wallpaper),
                    items = ChatWallpaper.entries.map { w ->
                        AppearanceGridItem(w.wire, prefs.theme, w, wallpaperName(w), w == prefs.wallpaper) {
                            AppearanceStore.update { it.copy(wallpaper = w) }
                        }
                    },
                    onBack = toMain,
                )
            }
            AppearancePage.Mode -> {
                BackHandler(onBack = toMain)
                AppearanceModeScreen(
                    current = prefs.mode,
                    onSelect = { m -> AppearanceStore.update { it.copy(mode = m) } },
                    onBack = toMain,
                )
            }
            is AppearancePage.Slider -> {
                // 系统返回 = 取消（与左上「取消」同义：没按「设置」就不算数）
                val cancel = {
                    setSlider(p.kind, p.original)
                    toMain()
                }
                BackHandler(onBack = cancel)
                AppearanceSliderScreen(
                    kind = p.kind,
                    prefs = prefs,
                    onValue = { setSlider(p.kind, it) },
                    onCancel = cancel,
                    onDone = toMain,
                )
            }
        }
    }

}
