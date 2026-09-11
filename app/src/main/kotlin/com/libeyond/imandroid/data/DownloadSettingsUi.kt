package com.libeyond.imandroid.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/** 自动下载设置里的两个网络（iOS `IMDownloadNetworkKind`）。 */
enum class DownloadNetwork(val title: String) {
    Cellular("使用移动数据"),
    Wifi("使用 Wi-Fi"),
}

/** 自动下载设置里的三类媒体（iOS `IMDownloadCategoryKind`）。 */
enum class DownloadCategory(val title: String) {
    Image("图片"),
    Video("视频"),
    File("文件"),
    ;

    /**
     * 图片没有大小上限：它的 `maxBytes` 恒 0，含义是「无门槛恒自动」（服务端 `normalized` 也强制归 0）。
     * 给图片画一根上限滑杆，用户一拖就等于把"恒自动"改成"关"——而服务端又会悄悄改回来。
     */
    val hasSizeLimit: Boolean get() = this != Image
}

/**
 * 「数据和存储」三层设置页的**纯逻辑**（对齐 iOS `IMDownloadSettingsUI` 与两个设置 VC 里散着的判据）。
 *
 * 页面只负责画；改哪个字段、滑杆松手怎么吸附、文案怎么写都收在这里。
 * 这几条与 iOS 分叉的表现是「同一个账号在两台手机上看到的设置写法不同」，没有任何自动手段能发现。
 */
object DownloadSettingsUi {

    private const val KB = 1L shl 10
    private const val MB = 1L shl 20

    /**
     * 大小上限滑杆的 13 个档位，**逐个抄 iOS `IMDownloadSizeStops`**。
     * 0 档 = 「关」（该类完全手动）；右端 1.5 GiB = 服务端 `MaxAutoBytes`。
     */
    val SIZE_STOPS: List<Long> = listOf(
        0L, 512 * KB, 1 * MB, 3 * MB, 5 * MB, 10 * MB, 15 * MB,
        30 * MB, 50 * MB, 100 * MB, 500 * MB, 1024 * MB, 1536 * MB,
    )

    fun policyOf(s: DownloadSettings, net: DownloadNetwork): NetworkPolicy =
        if (net == DownloadNetwork.Cellular) s.cellular else s.wifi

    fun withPolicy(s: DownloadSettings, net: DownloadNetwork, p: NetworkPolicy): DownloadSettings =
        if (net == DownloadNetwork.Cellular) s.copy(cellular = p) else s.copy(wifi = p)

    fun ruleOf(p: NetworkPolicy, cat: DownloadCategory): CategoryRule = when (cat) {
        DownloadCategory.Image -> p.image
        DownloadCategory.Video -> p.video
        DownloadCategory.File -> p.file
    }

    fun withRule(p: NetworkPolicy, cat: DownloadCategory, r: CategoryRule): NetworkPolicy = when (cat) {
        DownloadCategory.Image -> p.copy(image = r)
        DownloadCategory.Video -> p.copy(video = r)
        DownloadCategory.File -> p.copy(file = r)
    }

    /**
     * 服务端值落在滑杆哪一档：取**最近**的一档，等距取靠左（iOS 同为严格小于才换）。
     * 服务端值不一定正好在档位上（别的端或老版本存过别的数），不能用 indexOf。
     */
    fun sizeStopIndex(bytes: Long): Int {
        var best = 0
        var bestDelta = Long.MAX_VALUE
        SIZE_STOPS.forEachIndexed { i, stop ->
            val delta = abs(stop - bytes)
            if (delta < bestDelta) {
                bestDelta = delta
                best = i
            }
        }
        return best
    }

    /** 上限文案：0 = 「关」，其余按 [formatBytes]（iOS `IMDownloadSizeLabel`）。 */
    fun sizeLabel(bytes: Long): String = if (bytes <= 0) "关" else formatBytes(bytes)

    /**
     * 字节数 → 「512 KB / 1 MB / 1.5 GB」，**逐条对齐 iOS `IMFormatFileSize`**：
     * 0 显示「0 KB」、最小单位是 KB、接近整数时去掉小数。
     *
     * 不复用 `MediaUrl.formatSize`：那是气泡上的大小标签（恒一位小数、有 B 单位），
     * 设置页文案要与 iOS 同一套，否则「上限 1 MB」在 Android 上会写成「上限 1.0 MB」。
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return ""
        if (bytes == 0L) return "0 KB"
        val (value, unit) = when {
            bytes >= 1024 * MB -> bytes / (1024.0 * MB) to "GB"
            bytes >= MB -> bytes / MB.toDouble() to "MB"
            else -> bytes / KB.toDouble() to "KB"
        }
        val v = maxOf(0.1, value)
        // Locale.US：默认 Locale 在部分语言下小数点是逗号（「1,5 GB」）
        val number = if (abs(v - round(v)) < 0.05) {
            String.format(Locale.US, "%.0f", v)
        } else {
            String.format(Locale.US, "%.1f", v)
        }
        return "$number $unit"
    }

    /** 网络行的副标题（iOS `IMNetworkSummary`）。 */
    fun networkSummary(p: NetworkPolicy): String =
        if (!p.enabled) "已停用" else "视频 ${sizeLabel(p.video.maxBytes)} · 文件 ${sizeLabel(p.file.maxBytes)}"

    /**
     * 网络页三类媒体行的右值：图片恒「对所有聊天启用」，视频/文件「最大 X」。
     *
     * **照抄 iOS，连它的一处不准也照抄**：图片那一行不看单聊/群聊开关，关掉群聊后仍写「对所有聊天启用」。
     * 要改应三端一起改，不在这里单端悄悄修正（会让两台手机上同一行写得不一样）。
     */
    fun categoryValue(p: NetworkPolicy, cat: DownloadCategory): String =
        if (cat == DownloadCategory.Image) "对所有聊天启用" else "最大 ${sizeLabel(ruleOf(p, cat).maxBytes)}"

    /** 档位滑杆的刻度名。**只有当前对不上任何预设时才出现第四档**（iOS `presetTickNames`）。 */
    fun tierNames(custom: Boolean): List<String> =
        if (custom) listOf("低", "中", "高", "自定义") else listOf("低", "中", "高")

    /**
     * 档位滑杆松手停在 [index]，策略该变成什么（iOS `presetSliderCommitted:`）。返回 null = 不保存。
     *
     * - 自定义态停在第四档：那一档只是「当前对不上预设」的**指示**，没有值可套用；
     * - 其余夹到低/中/高并套用该档的视频/文件上限，**不动单聊/群聊开关**；
     * - 套用后与原策略相同也返回 null——没变就别发一次整份 PUT（iOS 在这里照发，是多余的一次写）。
     */
    fun commitTier(p: NetworkPolicy, index: Int): NetworkPolicy? {
        val custom = DownloadPolicy.tierOf(p) == DownloadPolicy.Tier.Custom
        if (custom && index >= DownloadPolicy.Tier.Custom.ordinal) return null
        val tier = DownloadPolicy.Tier.entries[index.coerceIn(0, DownloadPolicy.Tier.High.ordinal)]
        return DownloadPolicy.applyTier(p, tier).takeIf { it != p }
    }

    /** 大小滑杆松手停在 [index] → 新规则；没变返回 null（iOS `sizeSliderCommitted:`）。 */
    fun commitSize(r: CategoryRule, index: Int): CategoryRule? {
        val bytes = SIZE_STOPS[index.coerceIn(0, SIZE_STOPS.lastIndex)]
        return r.copy(maxBytes = bytes).takeIf { it != r }
    }

    /** 「存储用量」右值：还没量出来与量出来是 0 都显「0 KB」（iOS 同）。 */
    fun usageLabel(bytes: Long?): String = if (bytes == null || bytes <= 0) "0 KB" else formatBytes(bytes)

    /** 清除缓存确认框正文（iOS `confirmClearCache`）。 */
    fun clearCacheMessage(bytes: Long): String =
        if (bytes > 0) "将删除本机缓存的 ${formatBytes(bytes)} 下载文件，云端保留可重新下载。" else "暂无可清除的缓存。"
}
