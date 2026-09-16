package com.libeyond.imandroid.data

/**
 * 卡片式弹层（`IMCardSheet`，对齐 iOS `UIModalPresentationPageSheet`）被往下拖、松手之后，
 * 是**收回原位**还是**关掉**。
 *
 * 判据先看速度、再看距离：用力往下一甩，哪怕只拖了一点点也该关（iOS 同）；
 * 往上甩则一定收回——拖下去一大截又反悔往回推，是「不想关」最明确的信号。
 */
object SheetDrag {

    /** 慢慢拖、松手时已拖下来超过卡片高度的这个比例，就关。 */
    const val DISMISS_FRACTION = 0.25f

    enum class Settle { Dismiss, Restore }

    fun settle(offsetPx: Float, sheetHeightPx: Float, velocityPxPerSec: Float, flingPxPerSec: Float): Settle = when {
        offsetPx <= 0f -> Settle.Restore
        velocityPxPerSec >= flingPxPerSec -> Settle.Dismiss
        velocityPxPerSec <= -flingPxPerSec -> Settle.Restore
        offsetPx >= sheetHeightPx * DISMISS_FRACTION -> Settle.Dismiss
        else -> Settle.Restore
    }
}
