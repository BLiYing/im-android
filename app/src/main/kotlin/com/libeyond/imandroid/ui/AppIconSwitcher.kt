package com.libeyond.imandroid.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.libeyond.imandroid.R
import com.libeyond.imandroid.sdk.logging.IMLog

/**
 * 外观页「应用图标」的四个选项（对齐 iOS `AppIcon` / `AppIconOcean` / `AppIconViolet` / `AppIconMidnight`）。
 * [alias] 对应 `AndroidManifest.xml` 里的 activity-alias 名，**改名会让用户已选的图标失效**。
 */
enum class AppIconChoice(val alias: String, @StringRes val title: Int, @DrawableRes val thumb: Int) {
    DEFAULT(".LauncherDefault", R.string.appearance_icon_default, R.drawable.appearance_icon_default),
    OCEAN(".LauncherOcean", R.string.appearance_icon_blue, R.drawable.appearance_icon_ocean),
    VIOLET(".LauncherViolet", R.string.appearance_icon_purple, R.drawable.appearance_icon_violet),
    MIDNIGHT(".LauncherMidnight", R.string.general_theme_dark, R.drawable.appearance_icon_midnight),
}

/**
 * 切换桌面图标 = 启用一个 activity-alias、停用其余三个（Android 没有 iOS `setAlternateIconName` 这种 API，
 * 业界通行做法就是这个）。**真相源是系统的组件启用状态**，不另存一份偏好——与 iOS 读
 * `alternateIconName` 同理，免得两边不一致。
 *
 * **选中时只记下，退到后台（`MainActivity.onStop`）才真正切**：停用「当前任务是从它启动的那个
 * alias」会让系统当场结束任务——2026-09-29 OPPO PKD130 真机实测，点一下图标 App 直接退回桌面。
 * 所以 [select] 只改内存里的待切值，[current] 优先读它，页面上立刻显示选中；[commitPending] 在后台落地。
 *
 * 已知限制（登记在 `docs/UI_PARITY_IOS.md` §4.11）：部分桌面要过几秒才刷新图标、原图标位置可能被挪走；
 * iOS 会弹系统提示，本端没有等价提示。
 */
object AppIconSwitcher {

    private val log = IMLog.tag("IM.Appearance")

    @Volatile
    private var pending: AppIconChoice? = null

    /** 页面显示用：有待切值就是它，否则读系统。 */
    fun current(context: Context): AppIconChoice = pending ?: applied(context)

    fun select(context: Context, choice: AppIconChoice) {
        pending = if (choice == applied(context)) null else choice
    }

    /** `MainActivity.onStop` 调：把待切值落到系统组件状态上。 */
    fun commitPending(context: Context) {
        val target = pending ?: return
        pending = null
        val pm = context.packageManager
        try {
            // 先启用目标、再停用其余：反过来的话中间有一瞬桌面一个入口都没有
            pm.setComponentEnabledSetting(
                component(context, target),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            AppIconChoice.entries.filter { it != target }.forEach {
                pm.setComponentEnabledSetting(
                    component(context, it),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            log.i("app_icon_changed", "icon" to target.name)
        } catch (e: RuntimeException) {
            log.e("app_icon_change_failed", e, "icon" to target.name)
        }
    }

    private fun applied(context: Context): AppIconChoice {
        val pm = context.packageManager
        return AppIconChoice.entries.firstOrNull { choice ->
            val state = pm.getComponentEnabledSetting(component(context, choice))
            // DEFAULT 状态 = 以清单为准：清单里只有「默认」那个 enabled=true
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && choice == AppIconChoice.DEFAULT)
        } ?: AppIconChoice.DEFAULT
    }

    private fun component(context: Context, choice: AppIconChoice) =
        ComponentName(context.packageName, context.packageName + choice.alias)
}
