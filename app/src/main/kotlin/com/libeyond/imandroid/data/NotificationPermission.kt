package com.libeyond.imandroid.data

/**
 * 设置 ▸ 通知与提示音 ▸「通知权限」行的纯判据（M5，对应 iOS
 * `IMNotificationSettingsViewController.handlePermissionRowTap`）。接线在 `ui/NotificationSettingsHost.kt`。
 *
 * 与 iOS 的差异：Android 没有「未设置」这个能直接查到的状态——系统只告诉你"现在能不能发通知"，
 * 分不清"从没问过"和"问过、被拒了"，所以右值只有已开启/未开启两态，"该弹系统框还是该引导去
 * 系统设置"靠申请前后的 `shouldShowRequestPermissionRationale` 推断（见 [shouldExplainAfterRequest]）。
 */
object NotificationPermission {
    /** Android 13（`TIRAMISU`）起才有 `POST_NOTIFICATIONS` 运行时权限；以下版本只能去系统设置开关。 */
    const val RUNTIME_PERMISSION_SDK = 33

    enum class TapAction {
        /** 已开启：直接跳系统的应用通知设置页（同 iOS「已开启→直接跳系统设置」）。 */
        OpenSettings,

        /** 未开启且系统支持运行时申请：先试着弹系统授权框。 */
        Request,

        /** 未开启且系统没有运行时权限可申请：弹提示引导去系统设置。 */
        Explain,
    }

    fun onTap(enabled: Boolean, sdkInt: Int): TapAction = when {
        enabled -> TapAction.OpenSettings
        sdkInt >= RUNTIME_PERMISSION_SDK -> TapAction.Request
        else -> TapAction.Explain
    }

    /**
     * 申请结束后要不要补一个「去系统设置里打开」的提示框。
     *
     * 系统在用户拒绝过两次后就**不再弹框**、直接回"未授权"——这时点了这一行屏幕上什么都不发生，
     * 必须自己引导。判据是申请前后两次 rationale 都为 false：弹过框的话至少有一次是 true
     * （第一次拒绝后变 true；第二次拒绝前是 true）。
     *
     * 已知误判：第一次申请时用户点框外关掉（没做决定），前后也都是 false，会多弹一次提示——
     * 进主界面时已经申请过一次，走到这里再是"第一次"的情况很少，宁可多提示不可没反应。
     */
    fun shouldExplainAfterRequest(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean): Boolean =
        !granted && !rationaleBefore && !rationaleAfter
}
