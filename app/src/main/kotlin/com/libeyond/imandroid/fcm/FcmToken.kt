package com.libeyond.imandroid.fcm

import com.google.firebase.messaging.FirebaseMessaging
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 取当前 FCM 注册 token 的唯一出口（对齐 [com.libeyond.imandroid.sdk.api.PushTokenApi] 之于
 * `PUT/DELETE /api/v1/push/token` 的角色——那边管上报协议，这里管从 Firebase SDK 取值）。
 *
 * **没有 `google-services.json` 时优雅失败、不崩**（父任务前提）：本地没有接入真实 Firebase 项目时，
 * `FirebaseMessaging.getInstance()` 会抛 `IllegalStateException`（"Default FirebaseApp is not
 * initialized"）——因为 `google-services` 插件没跑（见 `app/build.gradle.kts` 的
 * `hasGoogleServicesConfig` 判断），生成不出 Firebase 初始化要读的那份 `values.xml`。
 * 这里统一吞掉任何异常并回 `null`；调用方（登录后补报、设置页重新打开开关）按"这次拿不到"处理，
 * 不当崩溃，也不重试到底——下次连接成功 / 下次开关操作会自然再试一次。
 */
object FcmToken {
    private val log = IMLog.tag("IM.Fcm")

    suspend fun current(): String? = suspendCancellableCoroutine { cont ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    cont.resume(task.result, null)
                } else {
                    log.w("fcm_token_fetch_failed", "err" to (task.exception?.javaClass?.simpleName ?: "unknown"))
                    cont.resume(null, null)
                }
            }
        } catch (e: Throwable) {
            log.w("fcm_token_unavailable", "err" to e.javaClass.simpleName)
            cont.resume(null, null)
        }
    }
}
