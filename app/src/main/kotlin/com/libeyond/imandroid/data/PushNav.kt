package com.libeyond.imandroid.data

/** 页面切换的方向：决定新页从哪边进来、谁盖在谁上面。 */
enum class PushDirection { Forward, Back }

/**
 * push / pop 转场与底部 Tab 栏的判据。
 *
 * **深度（depth）是唯一输入**：0 = Tab 根页，往里每 push 一层 +1。由它同时推出两件事，
 * 两件事就不会各说各的：
 *  ① 转场方向——变深是前进（新页从右边盖进来），变浅是后退（当前页向右滑走）；
 *  ② 底部 Tab 栏——只有根页画（iOS `hidesBottomBarWhenPushed` 同义）。
 *     此前「通讯录」「我」的二级页是在 Tab 内容区里原地切换的，底栏一直挂在下面（2026-09-15 用户报）。
 */
object PushNav {

    const val ROOT_DEPTH = 0

    /**
     * **同深度按前进**：同一层换内容只发生在「群资料里点成员『发消息』换会话」这类场景，
     * iOS 那里是 push 一个新聊天页——新页该从右边进来、盖在旧页上面。
     */
    fun directionOf(fromDepth: Int, toDepth: Int): PushDirection =
        if (toDepth < fromDepth) PushDirection.Back else PushDirection.Forward

    fun showsTabBar(depth: Int): Boolean = depth == ROOT_DEPTH
}

/**
 * 「消息」Tab 里的页面：会话列表右上角 ＋ 推出去的两页（对齐 iOS `IMConversationListViewController` 的
 * `plusTapped:`）。从这里建群是**一层**——与通讯录那条「群聊 → 建群」的两层不同，返回直接回会话列表。
 */
enum class ChatsPage(val depth: Int) {
    List(PushNav.ROOT_DEPTH),
    AddFriend(1),
    CreateGroup(1),
    /** 首页全局搜索；其「搜索用户」下钻 [AddFriend]，比它再深一层不需要——回退直接回列表即可（同 iOS 点取消/返回）。 */
    Search(1),
}

/** 「通讯录」Tab 里的页面。建群是从「群聊」列表点进去的，比它深一层。 */
enum class ContactsPage(val depth: Int) {
    List(PushNav.ROOT_DEPTH),
    NewFriends(1),
    Search(1),
    Groups(1),
    Profile(1),
    CreateGroup(2),
}

/** 「我」Tab 里的页面（对齐 iOS `IMSettingsViewController` push 出去的几页）。 */
enum class MePage(val depth: Int) {
    List(PushNav.ROOT_DEPTH),
    Profile(1),
    Qr(1),
    Devices(1),
    DataStorage(1),
    Privacy(1),
    Favorites(1),
    Language(1),
    CallHistory(1),
    Appearance(1),
    Notifications(1),
    ShareCard(1),
    PowerSaving(1),
}
