package com.libeyond.imandroid.fcm

import com.google.firebase.messaging.FirebaseMessaging
import com.libeyond.imandroid.sdk.logging.IMLog
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean

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
        deleteArmed.set(false) // 登录后才会取：下次登出能再作废一次
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

    /**
     * 「本机已登出、推送还在来」时作废本机 FCM token：服务端下次往它推会收到 UNREGISTERED，随即删掉那条登记
     * （`push/service.go` outcomeDeleteToken）。下次登录连上时 [current] 会取一枚新的再上报。
     *
     * 同一段登出期只作废一次（推送连发时别每条都打一次 Firebase）；**失败就放开重来**，下一条推送再试——
     * 此前在结果出来前就置位、且永不复位，失败一次（断网）服务端就会一直往这台登出的设备推（/code-review 2026-10-08）。
     * 登录后 [current] 被调用（连上即取）时复位，下次登出能再作废。没有 Firebase 配置时只记日志、不崩。
     */
    fun deleteWhileLoggedOut() {
        if (!deleteArmed.compareAndSet(false, true)) return
        try {
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    log.i("fcm_token_deleted")
                } else {
                    deleteArmed.set(false)
                    log.w("fcm_token_delete_failed", "err" to (task.exception?.javaClass?.simpleName ?: "unknown"))
                }
            }
        } catch (e: Throwable) {
            deleteArmed.set(false)
            log.w("fcm_token_unavailable", "err" to e.javaClass.simpleName)
        }
    }

    /** true = 本段登出期已作废过（或正在作废）。 */
    private val deleteArmed = AtomicBoolean(false)
}
