package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str
import com.libeyond.imandroid.sdk.protocol.ErrCode

/**
 * 聊天媒体上传的**发送前预检**（后台「系统设置 ▸ 上传与媒体」S3a，服务端 `cmd/imserver/upload_policy.go`；
 * 对端 im-web `src/sdk/uploadPrecheck.ts`，口径逐条一致）。
 *
 * 纯函数：所有聊天上传入口（[com.libeyond.imandroid.ui.MediaSendFlow] 的图片/视频/文件/语音，
 * [MediaSendPipeline] 的整包 / 流式 / 续传 / 语音重传）在**落待发行或发起上传之前**调它一次。
 * 服务端仍是权威（`complete` 时按当前策略复检，在途分片也可能在收尾被 500001/500002/500003 拒），
 * 这里只是省掉一次注定失败的上传。头像 / 群头像不走这里（`/api/v1/avatar` 不受此策略约束）。
 */
object UploadPrecheck {

    sealed interface Failure {
        /** 对应的服务端业务码：预检挡在待发行已落库之后时（流水线里），据此把行标失败并显同一行说明。 */
        val code: Int

        /** 总开关关（语音也拦）。 */
        data object Disabled : Failure { override val code = ErrCode.UPLOAD_DISABLED }

        /** 扩展名在禁用表里。 */
        data object BlockedType : Failure { override val code = ErrCode.FILE_TYPE_INVALID }

        /** 超过单文件上限；[limitLabel] 是上限的展示文案（"2 GB" / "500 MB"）。 */
        data class TooLarge(val limitLabel: String) : Failure { override val code = ErrCode.FILE_TOO_LARGE }
    }

    private const val GIB = 1L shl 30
    private const val MIB = 1L shl 20

    /**
     * 判据顺序（与服务端同序）：① 总开关关 → [Failure.Disabled]（语音也拦）；
     * ② 非语音且扩展名（大小写不敏感）在禁用表里 → [Failure.BlockedType]；
     * ③ 非语音且字节数超过单文件上限 → [Failure.TooLarge]。返回 null = 放行。
     *
     * 语音：服务端固定 16MB 上限、不查扩展名，所以 ②③ 不适用。
     */
    fun check(fileName: String, byteCount: Long, isVoice: Boolean, limits: ServerLimits): Failure? {
        if (!limits.uploadEnabled) return Failure.Disabled
        if (isVoice) return null
        val ext = lowerExtension(fileName)
        if (ext.isNotEmpty() && ext in limits.uploadBlockedExtensions) return Failure.BlockedType
        if (byteCount > limits.uploadMaxFileBytes) return Failure.TooLarge(sizeLabel(limits.uploadMaxFileBytes))
        return null
    }

    /**
     * 按**当前缓存**的服务端配置预检（发送入口与流水线都走这个）。拦下时顺手请求重拉一次配置：
     * 缓存可能是旧的（管理员关了上传又打开），否则要等下次重连 / 回前台才放行。重拉有节流与在途去重。
     */
    fun checkCurrent(fileName: String, byteCount: Long, isVoice: Boolean): Failure? =
        check(fileName, byteCount, isVoice, ServerConfigStore.current)?.also {
            ServerConfigStore.requestRefresh("upload_precheck_rejected")
        }

    /** 上限的展示文案：恰为 GiB 整数倍 → "N GB"；整 MiB → "N MB"；否则保留一位小数 "1.5 MB"（同服务端 uploadLimitLabel）。 */
    fun sizeLabel(bytes: Long): String = when {
        bytes >= GIB && bytes % GIB == 0L -> "${bytes / GIB} GB"
        bytes % MIB == 0L -> "${bytes / MIB} MB"
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes.toDouble() / MIB)
    }

    /** 文件名的小写扩展名（带点）；没有扩展名 / 点在开头（".bashrc"）/ 以点结尾 → ""。 */
    fun lowerExtension(fileName: String): String {
        val base = fileName.substring(maxOf(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1)
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.length - 1) return ""
        return base.substring(dot).lowercase()
    }

    /** 吐司文案（调用时刻取，跟随当前语言）。 */
    fun message(f: Failure): String = when (f) {
        Failure.Disabled -> Str.s(R.string.err_500003)
        Failure.BlockedType -> Str.s(R.string.upload_precheck_blocked_type)
        is Failure.TooLarge -> Str.s(R.string.upload_precheck_too_large, f.limitLabel)
    }

    /** 日志里的稳定原因名（不打文件名：可能含个人信息，只记类型与大小）。 */
    fun reason(f: Failure): String = when (f) {
        Failure.Disabled -> "disabled"
        Failure.BlockedType -> "blocked_type"
        is Failure.TooLarge -> "too_large"
    }
}
