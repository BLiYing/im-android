package com.libeyond.imandroid.data

/**
 * 查看器「下拉关闭」的判据（iOS `IMMediaPagerViewController` 挂的 `UISwipeGestureRecognizer` 向下滑即关）。
 *
 * 本端做成跟手拖：拖过 [DISTANCE_DP] 松手就关，或者向下**甩**得够快（不用拖那么远，对应 iOS 的轻扫）；
 * 都不够就弹回原位。只认向下——向上拖不动画面。
 */
object ViewerDismiss {
    /** 松手即关的拖动距离。 */
    const val DISTANCE_DP = 96f

    /** 松手时向下的速度够这个值也关（轻扫）。 */
    const val FLING_DP_PER_S = 900f

    /** 视觉随拖动变化的满程距离：拖到这么远时缩到最小、背景最透。 */
    const val FULL_TRAVEL_DP = 320f

    /** 拖动到多远时内容缩到多少（满程 0.8）。 */
    fun scaleFor(offsetDp: Float): Float = 1f - 0.2f * progress(offsetDp)

    /** 背景黑底的不透明度（满程 0.3），露出下面的聊天页。 */
    fun backdropAlphaFor(offsetDp: Float): Float = 1f - 0.7f * progress(offsetDp)

    fun shouldClose(offsetDp: Float, velocityDpPerS: Float): Boolean =
        offsetDp >= DISTANCE_DP || (offsetDp > 0f && velocityDpPerS >= FLING_DP_PER_S)

    private fun progress(offsetDp: Float): Float = (offsetDp / FULL_TRAVEL_DP).coerceIn(0f, 1f)
}
