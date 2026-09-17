package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.db.MessageEntity
import com.libeyond.imandroid.sdk.api.ConvMediaItem
import com.libeyond.imandroid.sdk.api.Favorite
import com.libeyond.imandroid.sdk.protocol.ContentType

/**
 * 收藏页的页签（对齐 iOS `IMFavoritesCategories` 的 B 方案：**无「全部」**、逐签浏览）。
 * 顺序即 iOS 的页签顺序：媒体 → 文件 → 链接 → 语音 → 文本 → 聊天记录 → 名片。
 */
enum class FavoriteCategory(val title: String) {
    Media("媒体"),
    Files("文件"),
    Links("链接"),
    Voice("语音"),
    Text("文本"),
    Record("聊天记录"),
    Contact("名片"),
}

/** 收藏页长按一项弹出的菜单项（iOS `contextMenuForFavorite:`，宫格格子与各行共用这一份）。 */
enum class FavoriteAction(val label: String, val destructive: Boolean = false) {
    Forward("转发"),
    Copy("复制"),

    /** 下载中 / 已暂停的文件或视频才有。本端无断点续传，「取消」= 停掉并回到未下载态。 */
    CancelDownload("取消下载"),

    /** 删的是**这条收藏**，不碰原消息。iOS 长按菜单里直接删、不二次确认，本端同。 */
    Delete("删除", destructive = true),
}

/**
 * 收藏页里**要被测试钉住的判据**：归哪个页签、默认停哪一签、翻页合并、删除后总数、
 * 以及「一条收藏 → 详情页那几种行认得的条目」的换算。
 *
 * ### 为什么要有 [toConvMediaItem]（本页复用聊天页 / 详情页状态逻辑的落点）
 * iOS 的做法是把每条收藏**合成一个 `IMMessageModel`**，喂给与聊天页、详情页共用的
 * `IMMediaDownloadCoordinator`，于是收藏里的宫格格子与文件行直接用详情页那几个 cell，
 * 下载任务、进度、缓存三处共享（`IMFavoritesViewController.modelForFavorite:`）。
 *
 * 本端的下载状态**本来就按 URL 全局共享**（[MediaDownloader.states]），所以对应物更轻：
 * 把收藏换算成详情页归档条目 [ConvMediaItem]，直接交给 `ArchiveRows.kt` 里的
 * `MediaTile` / `FileRow` / `VoiceRow` / `LinkRow`——**同一段代码画、同一份门控状态**，
 * 在聊天页下到一半的文件，收藏页里看到的是同一个进度环。
 */
object Favorites {

    /** 某条收藏属不属于某个页签。空内容不进任何一签（iOS 同）。 */
    fun matches(f: Favorite, kind: FavoriteCategory): Boolean {
        if (f.content.isEmpty()) return false
        val ct = f.contentType.ifBlank { ContentType.TEXT }
        return when (kind) {
            FavoriteCategory.Media -> ct == ContentType.IMAGE || ct == ContentType.VIDEO
            FavoriteCategory.Files -> ct == ContentType.FILE
            // 独立 link 类型，或**含 URL 的文本**（混排也算，同详情页「链接」签的 [LinkScan] 口径）
            FavoriteCategory.Links -> ct == LINK || (ct == ContentType.TEXT && !isRecord(f) && LinkScan.hasUrl(f.content))
            FavoriteCategory.Voice -> ct == ContentType.VOICE || ct == AUDIO
            FavoriteCategory.Text -> ct == ContentType.TEXT && !isRecord(f) && !LinkScan.hasUrl(f.content)
            FavoriteCategory.Record -> isRecord(f)
            // 解析不出 uid 的脏名片不计入：列表里不该出现点不动的空行（iOS 同）
            FavoriteCategory.Contact -> ct == ContentType.CONTACT && CardContent.parseContact(f.content) != null
        }
    }

    /**
     * 合并转发「聊天记录」：显式类型，或**老收藏把它存成了 text**（内容形如记录 JSON，iOS
     * `IMLooksLikeChatRecordJSON` 兜底同一件事）。只对以 `{` 开头的正文试解析——每条都解一遍 JSON 太贵。
     */
    private fun isRecord(f: Favorite): Boolean =
        f.contentType == ContentType.CHAT_RECORD ||
            (f.contentType.ifBlank { ContentType.TEXT } == ContentType.TEXT &&
                f.content.trimStart().startsWith("{") && CardContent.looksLikeRecord(f.content))

    /** 按收藏里**实际存在**的内容生成页签，只列存在者。全无可归类收藏时为空表。 */
    fun categoriesOf(favs: List<Favorite>): List<FavoriteCategory> =
        FavoriteCategory.entries.filter { k -> favs.any { matches(it, k) } }

    /**
     * 该停在哪一签：当前签还在就不动；否则默认「媒体」，没有媒体停首个存在签（iOS 已拍板②）。
     * 删掉某签最后一条时也走这里——别停在一个已经消失的页签上显「暂无」。
     */
    fun settle(categories: List<FavoriteCategory>, current: FavoriteCategory?): FavoriteCategory? = when {
        current != null && current in categories -> current
        FavoriteCategory.Media in categories -> FavoriteCategory.Media
        else -> categories.firstOrNull()
    }

    /** 当前签 ∩ 关键词。关键词匹配正文 / 图说 / 文件名（同 iOS `favorite:matchesQuery:`），大小写不敏感。 */
    fun filter(favs: List<Favorite>, kind: FavoriteCategory, query: String = ""): List<Favorite> {
        val q = query.trim()
        return favs.filter { f -> matches(f, kind) && (q.isEmpty() || matchesQuery(f, q)) }
    }

    private fun matchesQuery(f: Favorite, q: String): Boolean {
        val name = if (f.contentType == ContentType.FILE) MediaUrl.displayFileName(f.content, f.fileName) else f.fileName
        return listOf(f.content, f.caption, name).any { it.contains(q, ignoreCase = true) }
    }

    fun emptyText(kind: FavoriteCategory): String = "暂无${kind.title}"

    /**
     * 追加下一页：**按 id 去重**。删过收藏之后 offset 整体前移，再翻页会把同一条读两次（iOS 同一处注释）。
     */
    fun merge(loaded: List<Favorite>, page: List<Favorite>): List<Favorite> {
        val seen = loaded.mapTo(HashSet()) { it.id }
        return loaded + page.filter { seen.add(it.id) }
    }

    /**
     * 还有没有下一页：**看服务端总数，不看"上一页装没装满"**——后者在总数恰好是页大小整数倍时
     * 会多发一次空请求才知道到底了。
     */
    fun hasMore(loadedCount: Int, total: Int): Boolean = loadedCount < total

    /**
     * 删掉一条之后的（列表, 总数）。**总数跟着减**：不减的话 `已加载 < 总数` 恒成立，
     * 滚到底会一直去拉一页已经不存在的数据（iOS `deleteFavorite:` 同一句注释）。
     */
    fun afterDelete(loaded: List<Favorite>, total: Int, id: Long): Pair<List<Favorite>, Int> {
        val left = loaded.filterNot { it.id == id }
        return left to if (left.size < loaded.size) maxOf(0, total - 1) else total
    }

    /** 长按「复制」复制什么；null = 不给（同 iOS：只有文本与链接两签给）。 */
    fun copyText(f: Favorite): String? =
        f.content.takeIf { matches(f, FavoriteCategory.Text) || matches(f, FavoriteCategory.Links) }

    /**
     * 长按菜单项。**顺序照抄 iOS**：转发 → 复制 → 取消下载 → 删除（破坏性的排最后）。
     * 转发恒给（失效媒体在执行时拦，同聊天页那一道）；复制只给文本与链接；
     * 取消下载只看文件与视频（图片没有分片进度，无从"取消"）。
     */
    fun actionsFor(f: Favorite, downloading: Boolean): List<FavoriteAction> = buildList {
        add(FavoriteAction.Forward)
        if (copyText(f) != null) add(FavoriteAction.Copy)
        if (downloading && (f.contentType == ContentType.FILE || f.contentType == ContentType.VIDEO)) {
            add(FavoriteAction.CancelDownload)
        }
        add(FavoriteAction.Delete)
    }

    /**
     * 这条收藏来自群聊——自动下载策略按单聊/群聊分档（[DownloadPolicy.shouldAutoDownload]），收藏页的宫格与文件行
     * 要按**来源会话**选档（2026-09-17 复查抓出：起初写死单聊，给群聊单独关了图片自动下载的账号在收藏页照样直出原图）。
     * 群会话 id 恒以 `g_` 开头（同 `MessageRepository` 建会话行时的判据）。
     *
     * 与 iOS 的刻意差异：iOS 收藏页编排器 `isGroup:NO` 写死单聊、`myUserID` 传哨兵（自己发的也门控）；
     * 本端按来源分档、自己发的不门控，与本端聊天页 / 详情页同一口径。
     */
    fun fromGroup(f: Favorite): Boolean = f.sourceConvId.startsWith("g_")

    /** 链接签那一行要打开的地址：独立 link 类型整段就是，混排文本取第一个 URL。 */
    fun linkUrl(f: Favorite): String = if (f.contentType == LINK) f.content else LinkScan.firstUrl(f.content) ?: f.content

    /**
     * 收藏 → 详情页归档条目（见类注释）。
     *
     * `convSeq` 填的是**收藏 id**：这里它只是列表 key 与查看器翻页的定位键，收藏之间唯一即可；
     * 各会话的 conv_seq 会互相撞车（两个会话各有一条 seq=10），拿它当 key 列表会串行。
     * `sender` 填原发送者——宫格判"自己发的不门控"要用。
     */
    fun toConvMediaItem(f: Favorite): ConvMediaItem = ConvMediaItem(
        convSeq = f.id,
        sender = f.sourceFrom,
        contentType = f.contentType,
        content = f.content,
        caption = f.caption,
        timestamp = f.createdAt,
        fileName = f.fileName,
        fileSize = f.fileSize,
        poster = f.poster,
        mediaW = f.mediaW,
        mediaH = f.mediaH,
        duration = f.duration,
        thumb = f.thumb,
    )

    /**
     * 收藏 → 一条可转发的消息（交给与聊天页同一个 `forwardMessages`）。
     *
     * **媒体元数据必须全带上**（`poster/thumb/media_w/media_h/duration/waveform`，判据
     * [Forward.attributesOf]）：这是转发的**第三个入口**（聊天页 / 详情页归档 / 收藏），
     * 漏带全程静默，只有收件人看得出来（SYMMETRY 登记的那条）。iOS 收藏转发曾漏过一次同样的东西。
     *
     * @param originName 「转发自」要写的**公开名**（不是备注——这串字会原样发给收件人）。
     *   空串时：我自己发的交给 [Forward.originOf] 写我的公开名；**别人发的写「未命名用户」**——
     *   不能留空，留空 [Forward.originOf] 会落到末级兜底 `msg.sender`，把对方的内部 uid 发出去
     *   （2026-09-17 复查抓出：刚进收藏页、名片还没补拉回来就点发送，就是这条路）。
     */
    fun toMessageEntity(f: Favorite, owner: String, originName: String): MessageEntity = MessageEntity(
        ownerUid = owner,
        convId = f.sourceConvId,
        convSeq = f.id,
        sender = f.sourceFrom,
        fromNickname = originName.ifBlank { if (f.sourceFrom == owner) null else UNNAMED },
        contentType = f.contentType.ifBlank { ContentType.TEXT },
        content = f.content,
        caption = f.caption.ifBlank { null },
        timestamp = f.createdAt,
        fileName = f.fileName.ifBlank { null },
        fileSize = f.fileSize.takeIf { it > 0 },
        mediaW = f.mediaW.takeIf { it > 0 },
        mediaH = f.mediaH.takeIf { it > 0 },
        duration = f.duration.takeIf { it > 0 },
        poster = f.poster.ifBlank { null },
        thumb = f.thumb.ifBlank { null },
        waveform = f.waveform.ifBlank { null },
    )

    /**
     * 行上「来自X」的 X（对齐 iOS `sourceNameForFavorite:`）：**我 → 「我」；单聊会话里的对端 → 会话显示名；
     * 好友 → 备注 > 昵称；补拉到的名片 → 其显示名**。
     *
     * **解析不出就回空串，不落内部 uid**——iOS 那一侧此时显 10 位内部 ID 占位再去补拉，本端的约定是
     * 界面上绝不出现内部 uid（[DisplayName]），空串 = 这一行不画，补拉到了再出现。
     * 这里用到的都是**只在本机渲染**的名字（含备注），绝不能拿去写进发出去的字节——那条走 [originName]。
     */
    fun sourceName(
        f: Favorite,
        myUid: String,
        sourceConv: com.libeyond.imandroid.data.db.ConversationEntity?,
        friend: com.libeyond.imandroid.sdk.api.FriendEntry?,
        card: com.libeyond.imandroid.sdk.api.UserCard?,
    ): String {
        val from = f.sourceFrom
        if (from.isBlank()) return ""
        if (myUid.isNotEmpty() && from == myUid) return "我"
        if (sourceConv != null && !sourceConv.isGroup && sourceConv.peerUid == from) {
            sourceConv.peerRemark.ifBlank { sourceConv.title }.takeIf { it.isNotBlank() }?.let { return it }
        }
        friend?.let { fr -> fr.remark.ifBlank { fr.nickname }.ifBlank { fr.handle }.takeIf { it.isNotBlank() }?.let { return it } }
        card?.let { cd -> cd.remark.ifBlank { cd.nickname }.ifBlank { cd.handle }.takeIf { it.isNotBlank() }?.let { return it } }
        return ""
    }

    /**
     * 转发时「转发自」写的**公开名**：昵称 > @句柄，**绝不含备注**（IMServer `docs/UI.md` 隐私红线——
     * 这串字会原样发给收件人）。我自己发的交给 [Forward.originOf] 用我的公开名；解析不出回空串。
     */
    fun originName(
        f: Favorite,
        friend: com.libeyond.imandroid.sdk.api.FriendEntry?,
        card: com.libeyond.imandroid.sdk.api.UserCard?,
    ): String = when {
        friend != null -> friend.nickname.ifBlank { friend.handle }
        card != null -> card.nickname.ifBlank { card.handle }
        else -> ""
    }

    /** 解析不出公开名时「转发自」写的字（同全端显示名回退链的末级，绝不是 uid）。 */
    private const val UNNAMED = "未命名用户"

    /** iOS 收藏归类表里的两个老类型（本端协议常量里没有）。 */
    private const val LINK = "link"
    private const val AUDIO = "audio"
}
