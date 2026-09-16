package com.libeyond.imandroid.data

/** 查看器里点一下视频画面（或中间的播放钮）做什么。 */
enum class VideoTapAction { Start, Pause, Resume }

/**
 * 对齐 iOS `IMMediaViewerViewController togglePlayback`：独立打开的查看器里，**点画面就播放 / 暂停**，
 * 不必瞄准中间那枚按钮（2026-09-16 用户报：本端只有按钮能点，点画面没反应）。
 */
object VideoTap {

    /**
     * @param wantsPlay 播放器「想不想播」（ExoPlayer `playWhenReady`，对应 iOS `rate > 0`）。
     *   **不能用 isPlaying**：缓冲中 isPlaying=false，按它判会把「缓冲时点一下想暂停」当成「继续播」，
     *   那一下就白点了——正是 `VideoPlayer` 注释里 2026-09-08 撞见过的「看着像没点上」。
     */
    fun actionFor(started: Boolean, wantsPlay: Boolean): VideoTapAction = when {
        !started -> VideoTapAction.Start
        wantsPlay -> VideoTapAction.Pause
        else -> VideoTapAction.Resume
    }
}
