package com.libeyond.imandroid.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 账号级**自动下载策略**（M4-7）——两网络 × 图片/视频/文件 × 单聊/群聊 + 大小上限。
 *
 * 服务端只存原始 JSON + 单调版本（`internal/downloadsettings`），**语义由各端按同一契约解释**。
 * 所以这里每一条判据都必须与 iOS `IMShouldAutoDownload` / Web `shouldAutoDownload` 逐字一致——
 * 不一致的表现是「同一条视频在这端自动下了、在那端要手点」，而没有任何自动手段能发现。
 *
 * **`maxBytes` 的语义按类别不同，别统一比较**：
 * - 图片：恒 0，含义是「无大小门槛、恒自动」（**不是**"关闭"）；
 * - 视频/文件：0 = 不自动下（手动），>0 = 仅当媒体 ≤ maxBytes 时自动下。
 */
@Serializable
data class CategoryRule(
    val single: Boolean = true,
    val group: Boolean = true,
    @SerialName("max_bytes") val maxBytes: Long = 0,
)

@Serializable
data class NetworkPolicy(
    /** 该网络下的自动下载**总开关**（关 = 全部手动）。 */
    val enabled: Boolean = true,
    val image: CategoryRule = CategoryRule(),
    val video: CategoryRule = CategoryRule(),
    val file: CategoryRule = CategoryRule(),
)

@Serializable
data class DownloadSettings(
    val cellular: NetworkPolicy = NetworkPolicy(),
    val wifi: NetworkPolicy = NetworkPolicy(),
)

object DownloadPolicy {

    private const val MB = 1L shl 20

    /** 上限的最大值（1.5 GiB，对齐服务端 `MaxAutoBytes` 与 Telegram 滑块右端）。 */
    const val MAX_AUTO_BYTES = 3L shl 29

    /**
     * 出厂默认：两网络自动下载均开；移动数据中档（视频 10MB / 文件 1MB），
     * Wi-Fi 高档（视频 15MB / 文件 3MB）；图片恒自动。「重置」即回到此。
     * **逐字对齐服务端 `Defaults()`**——端上默认与服务端默认不一致时，
     * 新账号在拉到设置之前那一小段会按错的策略下载。
     */
    fun defaults(): DownloadSettings = DownloadSettings(
        cellular = NetworkPolicy(
            enabled = true,
            image = CategoryRule(maxBytes = 0),
            video = CategoryRule(maxBytes = 10 * MB),
            file = CategoryRule(maxBytes = 1 * MB),
        ),
        wifi = NetworkPolicy(
            enabled = true,
            image = CategoryRule(maxBytes = 0),
            video = CategoryRule(maxBytes = 15 * MB),
            file = CategoryRule(maxBytes = 3 * MB),
        ),
    )

    /**
     * 该不该自动下载。与 iOS `IMShouldAutoDownload` / Web `shouldAutoDownload` 同一决策矩阵：
     *
     * 1. 网络总开关关 → 否；
     * 2. 该类别的单聊/群聊开关关 → 否；
     * 3. 图片 → 是（无大小闸）；
     * 4. 视频/文件 → 仅当 `0 < size ≤ maxBytes`。
     *    **大小未知（≤0）也判否**——让用户自己点 ↓，别赌一把去拉一个可能几百 MB 的东西；
     * 5. 其余类型（语音等体积极小的）→ 恒自动，不进阈值体系。
     *
     * @param onWifi 本端**能区分网络类型**（Web 分不清所以恒用 Wi-Fi 档，那是它的限制不是契约）。
     */
    fun shouldAutoDownload(
        settings: DownloadSettings?,
        contentType: String,
        sizeBytes: Long,
        isGroup: Boolean,
        onWifi: Boolean,
    ): Boolean {
        val s = settings ?: defaults()
        val policy = if (onWifi) s.wifi else s.cellular
        if (!policy.enabled) return false
        val rule = when (contentType) {
            "image" -> policy.image
            "video" -> policy.video
            "file" -> policy.file
            else -> return true
        }
        if (!(if (isGroup) rule.group else rule.single)) return false
        if (contentType == "image") return true
        if (rule.maxBytes <= 0) return false
        return sizeBytes in 1..rule.maxBytes
    }

    /** 快捷档位（草图 §06）：滑块是快捷入口，单类页才是最终真相；对不上预设即「自定义」。 */
    enum class Tier { Low, Medium, High, Custom }

    /** 档位 → 该档的视频/文件上限（图片恒自动）。低档 = 视频/文件都手动。 */
    fun tierLimits(tier: Tier): Pair<Long, Long> = when (tier) {
        Tier.Low -> 0L to 0L
        Tier.Medium -> 10 * MB to 1 * MB
        Tier.High, Tier.Custom -> 15 * MB to 3 * MB
    }

    /** 反推当前策略落在哪个快捷档。 */
    fun tierOf(p: NetworkPolicy): Tier {
        for (t in listOf(Tier.Low, Tier.Medium, Tier.High)) {
            val (v, f) = tierLimits(t)
            if (p.video.maxBytes == v && p.file.maxBytes == f) return t
        }
        return Tier.Custom
    }

    /** 把整档套用到策略（写的是**大小上限**，与 iOS/Telegram 一致——不动单/群开关）。 */
    fun applyTier(p: NetworkPolicy, tier: Tier): NetworkPolicy {
        val (v, f) = tierLimits(tier)
        return p.copy(video = p.video.copy(maxBytes = v), file = p.file.copy(maxBytes = f))
    }

    /**
     * 是否等于出厂默认（决定「重置」可不可点）。
     * **逐字段比较**，不靠 JSON 序列化——键序与字段增减都会让那种比法静默误判。
     */
    fun isDefault(s: DownloadSettings): Boolean = s == defaults()

    /** 规整：负数上限视为 0（手动），超上限截断。与服务端 `clampBytes` 同。 */
    fun clampBytes(n: Long): Long = n.coerceIn(0, MAX_AUTO_BYTES)
}
