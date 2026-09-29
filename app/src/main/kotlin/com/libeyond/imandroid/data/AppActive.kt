package com.libeyond.imandroid.data

/**
 * App 是否前台（[AlertContext.appActive] 的判据来源）。
 *
 * 由 [com.libeyond.imandroid.MainActivity] 的 `repeatOnLifecycle(RESUMED)` 维护——本 App 只有一个
 * Activity，用它的生命周期当"前台"的代理足够准确，不必额外引入 `androidx.lifecycle.ProcessLifecycleOwner`
 * 依赖（本仓 `libs.versions.toml` 目前没有 `lifecycle-process`，P0 不为这一项加新依赖）。
 */
object AppActive {
    @Volatile var current: Boolean = false
}
