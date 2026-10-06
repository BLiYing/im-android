package com.libeyond.mediapicker

/**
 * 读相册权限的口径（纯逻辑，可单测）。
 *
 * 这套东西**在三个 API 版本上换过三次名字**，而且换的时候旧名字不报错、只是永远拿不到照片——
 * 典型的「编译过、测试绿、真机空白」。所以把判定抽出来按版本钉死：
 *
 * | 系统 | 要申请的 |
 * |---|---|
 * | ≤ Android 12L (API 32) | `READ_EXTERNAL_STORAGE`（一条管图片和视频） |
 * | Android 13 (API 33) | `READ_MEDIA_IMAGES`（要视频再加 `READ_MEDIA_VIDEO`） |
 * | Android 14+ (API 34) | 上面那些 + `READ_MEDIA_VISUAL_USER_SELECTED` |
 *
 * Android 14 起用户可以只授权**部分**照片（[Access.Partial]）：这时 MediaStore 只返回被选中的那几张，
 * **查询照样成功、只是少**——不识别这个状态就会表现成「相册里只有 3 张照片」，用户以为是 bug。
 */
object MediaPermission {

    const val READ_EXTERNAL = "android.permission.READ_EXTERNAL_STORAGE"
    const val READ_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    enum class Access {
        /** 能读全部相册 → 走自建宫格页。 */
        Full,

        /** Android 14+ 只授权了部分照片 → 走自建宫格页，但要给「管理选中的照片」入口。 */
        Partial,

        /** 一点都没有 → 同页空状态（说明 + 去设置，见 [MediaPickerDenied]），**不降级**到系统选择器。 */
        None,
    }

    /**
     * 该向系统申请哪几个权限。
     *
     * [includeVideo] = false 时**不申请** `READ_MEDIA_VIDEO`——申请了却用不上就是权限过度索取，
     * 商店审核会问，用户也会被吓到。
     */
    fun required(sdkInt: Int, includeVideo: Boolean = true): List<String> = when {
        sdkInt >= 34 -> buildList {
            add(READ_IMAGES)
            if (includeVideo) add(READ_VIDEO)
            add(READ_USER_SELECTED)
        }
        sdkInt >= 33 -> buildList {
            add(READ_IMAGES)
            if (includeVideo) add(READ_VIDEO)
        }
        // ≤32 只有一条老权限，它同时覆盖图片和视频，所以 includeVideo 在这段没有区别
        else -> listOf(READ_EXTERNAL)
    }

    /**
     * 已授予的权限集合 → 当前能力。
     *
     * **以图片权限为准**：视频权限是加项，用户单独拒了视频不该让整个选择器落到被拒空状态——
     * 那样他连图都发不了。视频能不能读由查询结果自己说话。
     */
    fun access(sdkInt: Int, granted: Set<String>): Access = when {
        sdkInt >= 34 -> when {
            granted.contains(READ_IMAGES) -> Access.Full
            granted.contains(READ_USER_SELECTED) -> Access.Partial
            else -> Access.None
        }
        sdkInt >= 33 -> if (granted.contains(READ_IMAGES)) Access.Full else Access.None
        else -> if (granted.contains(READ_EXTERNAL)) Access.Full else Access.None
    }

    /** 视频到底读不读得到（Android 13+ 是独立权限）。 */
    fun canReadVideo(sdkInt: Int, granted: Set<String>): Boolean = when {
        sdkInt >= 33 -> granted.contains(READ_VIDEO)
        else -> granted.contains(READ_EXTERNAL)
    }

    /** 能不能走自建宫格页（部分授权也能走，只是内容少）。 */
    fun canBrowse(access: Access): Boolean = access != Access.None
}
