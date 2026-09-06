package com.libeyond.imandroid.sdk.session

import android.content.Context
import android.os.Build
import com.libeyond.imandroid.BuildConfig
import java.util.UUID

/**
 * 本机设备身份，随 `POST /login` 上报。
 *
 * ## 为什么必须有、且必须稳定
 * 服务端按 `(uid, device_id)` upsert **顶替去重**：同一台设备再次登录会吊销旧会话。
 * - **生产环境（未开 `-dev-login`）缺 `device_id` 直接 400**。
 * - 不稳定（每次启动换一个）会让「已登录设备」列表每启动一次堆一行，
 *   且「踢下线某设备」永远踢不到当前这台——iOS 早期就是这个毛病。
 *
 * 故 `deviceId` 首次生成后**持久化，永不重生成**（除非用户清数据）。
 */
class DeviceIdentity(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 稳定的设备 id（UUID，首次生成后持久化）。 */
    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    /** 与 iOS 的 `platform:ios`、Web 的 `platform:web` 对齐。 */
    val platform: String = "android"

    /** 「已登录设备」列表里显示给用户看的名字。 */
    val deviceName: String = listOfNotNull(
        Build.MANUFACTURER?.replaceFirstChar { it.uppercase() },
        Build.MODEL,
    ).joinToString(" ").ifBlank { "Android 设备" }

    val appVersion: String = BuildConfig.VERSION_NAME

    private companion object {
        const val PREFS = "im_device"
        const val KEY_DEVICE_ID = "device_id"
    }
}
