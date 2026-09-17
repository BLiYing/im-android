package com.libeyond.imandroid.data

/**
 * 输入栏 ➕ 面板的条目（数据驱动，加入口 = 数组加一条）。
 *
 * **清单与顺序逐条对齐 iOS** `IMChatViewController+Media.m` 的 `attachItems`：
 * 照片 / 拍摄 / 音视频 / 收藏 / 个人名片 / 文件，2×3。
 * 顺序不是随意的——用户是靠**位置**记住「文件在右下角」的，两端顺序不同就等于两套肌肉记忆。
 * `AttachItemsTest` 钉死了这份清单。
 *
 * **没实现的项照样列出来**：删掉会让三端的面板长得不一样，用户在另一端找得到、在这端找不到，
 * 比点进去看到「还没做」更困惑。这与「我」页入口列表同一套做法。
 */
object AttachItems {

    enum class Kind {
        /** 相册多选（`:media-picker`）。 */
        Photo,

        /** 系统相机拍照。 */
        Camera,

        /** 音视频通话。**iOS 也是占位**——整个功能三端都没做。 */
        AudioVideo,

        /** 从收藏里选（收藏页的选择模式，iOS `initInPickModeWithDone:`），选中项发进当前会话。 */
        Favorite,

        /** 个人名片：选好友 → 发 `contact` 卡片。 */
        ContactCard,

        /** 任意文件。 */
        File,
    }

    data class Item(val kind: Kind, val title: String, val implemented: Boolean)

    /** 与 iOS `attachItems` 同序。 */
    val ALL: List<Item> = listOf(
        Item(Kind.Photo, "照片", implemented = true),
        Item(Kind.Camera, "拍摄", implemented = true),
        Item(Kind.AudioVideo, "音视频", implemented = false),
        Item(Kind.Favorite, "收藏", implemented = false),
        Item(Kind.ContactCard, "个人名片", implemented = true),
        Item(Kind.File, "文件", implemented = true),
    )

    /** 面板高度（顶起输入栏的量）。与 iOS `kIMAttachPanelHeight` 同值。 */
    const val PANEL_HEIGHT = 236

    /** 每格图标按钮边长。与 iOS 同值。 */
    const val ITEM_SIZE = 56

    const val COLUMNS = 3
}
