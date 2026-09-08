package com.libeyond.imandroid.ui

/**
 * 聊天页之上的覆盖层，**按渲染顺序从上到下排列**。
 *
 * 存在的理由：`ChatHost` 原本只有一个无条件的 `BackHandler(onBack)`，
 * 于是任何覆盖层开着时按返回键都会**直接退出整个聊天页**回到会话列表——
 * 用户以为只是关掉大图，结果连会话都退了，再进来还得重新滚到刚才的位置。
 * 覆盖层是逐个加上去的（转发 → 选图 → 选联系人 → 媒体查看器），
 * 每加一个都没人想起返回键，这正是「靠记性」失效的典型。
 *
 * 枚举顺序 = 视觉层叠顺序 = 返回键的关闭顺序，`ChatOverlaysTest` 钉住它。
 */
internal object ChatOverlays {

    /** 越靠前越在上层。 */
    enum class Layer {
        /** 全屏媒体查看器：最"临时"，永远盖在最上面。 */
        Viewer,

        /** 点系统消息里的名字进的用户资料页。 */
        UserProfile,

        /** 选联系人发名片。 */
        FriendPicker,

        /** 自建相册选图页。 */
        MediaPicker,

        /** 转发目标选择页。 */
        Forward,

        /** 长按上下文菜单。 */
        ContextMenu,
    }

    /**
     * 当前最上面那一层；没有任何覆盖层时返回 null（此时返回键才回会话列表）。
     */
    fun topmost(open: Set<Layer>): Layer? = Layer.entries.firstOrNull { it in open }
}
