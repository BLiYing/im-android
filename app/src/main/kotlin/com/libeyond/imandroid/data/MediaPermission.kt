package com.libeyond.imandroid.data

/**
 * 读相册权限的口径（纯逻辑，可单测）。
 *
 * 这套东西**在三个 API 版本上换过三次名字**，而且换的时候旧名字不报错、只是永远拿不到照片——
 * 典型的「编译过、测试绿、真机空白」。所以把判定抽出来按版本钉死：
 *
 * | 系统 | 要申请的 |
 * |---|---|
 * | ≤ Android 12L (API 32) | `READ_EXTERNAL_STORAGE` |
 * | Android 13 (API 33) | `READ_MEDIA_IMAGES` |
 * | Android 14+ (API 34) | `READ_MEDIA_IMAGES` + `READ_MEDIA_VISUAL_USER_SELECTED` |
 *
 * Android 14 起用户可以只授权**部分**照片（[Access.Partial]）：这时 MediaStore 只返回被选中的那几张，
 * **查询照样成功、只是少**——不识别这个状态就会表现成「相册里只有 3 张照片」，用户以为是 bug。
 */
object MediaPermission {

    const val READ_EXTERNAL = "android.permission.READ_EXTERNAL_STORAGE"
    const val READ_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    enum class Access {
        /** 能读全部相册 → 走自建宫格页。 */
        Full,

        /** Android 14+ 只授权了部分照片 → 走自建宫格页，但要给「管理选中的照片」入口。 */
        Partial,

        /** 一点都没有 → **降级回系统选择器**，功能不消失（哪怕 Android 11 上是 DocumentsUI）。 */
        None,
    }

    /** 该向系统申请哪几个权限。 */
    fun required(sdkInt: Int): List<String> = when {
        sdkInt >= 34 -> listOf(READ_IMAGES, READ_USER_SELECTED)
        sdkInt >= 33 -> listOf(READ_IMAGES)
        else -> listOf(READ_EXTERNAL)
    }

    /** 已授予的权限集合 → 当前能力。 */
    fun access(sdkInt: Int, granted: Set<String>): Access = when {
        sdkInt >= 34 -> when {
            granted.contains(READ_IMAGES) -> Access.Full
            granted.contains(READ_USER_SELECTED) -> Access.Partial
            else -> Access.None
        }
        sdkInt >= 33 -> if (granted.contains(READ_IMAGES)) Access.Full else Access.None
        else -> if (granted.contains(READ_EXTERNAL)) Access.Full else Access.None
    }

    /** 能不能走自建宫格页（部分授权也能走，只是内容少）。 */
    fun canBrowse(access: Access): Boolean = access != Access.None
}
