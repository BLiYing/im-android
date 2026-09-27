package com.libeyond.imandroid.data

import androidx.annotation.StringRes
import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/**
 * 归档（详情页的媒体 / 文件 / 语音 / 链接页签）里长按一项弹出的菜单项。
 *
 * **逐条抄自 iOS `IMChatDetailViewController` 的 `contentMenuConfigForMessage:`**
 * ——它是那一侧**唯一**的菜单真源：四个逐行页签与媒体宫格共用它，所以本端也只有这一份判据。
 * 顺序也照抄（转发 → 定位 → 取消下载 → 删除），破坏性的排最后。
 *
 * 与聊天页长按菜单（[MessageAction]）**刻意是两套**：那边有复制/引用/撤回，这边没有；
 * 这边有「取消下载」「定位到聊天」，那边没有。合成一套只会得到一堆互相排斥的可见性判据。
 */
enum class ArchiveAction(@StringRes private val labelRes: Int, val destructive: Boolean = false) {
    Forward(R.string.common_forward),
    LocateInChat(R.string.chat_menu_locate),

    /** 下载中 / 已暂停时才有。本端无断点续传，「取消」= 停掉并回到未下载态。 */
    CancelDownload(R.string.file_menu_cancel_download),

    /** 仅本机隐藏（REST hide，多设备同步）。文案与 iOS 子菜单第一项一致。 */
    HideForMe(R.string.delete_sheet_only_me, destructive = true),

    /** 撤回式硬删（msg_op delete），全群消失。iOS 把破坏性重的排在子菜单最后。 */
    DeleteForEveryone(R.string.delete_sheet_everyone, destructive = true),
    ;

    val label: String get() = Str.s(labelRes)
}

object ArchiveActions {

    /**
     * 能不能「为所有人删除」——判据逐字对齐 iOS `canDeleteForEveryone:`：
     * **自己发的，或者（群聊里）我是群主/管理员**。
     *
     * 判错也不会越权（服务端独立校验，越权回 300006），最坏是多显/少显一项。
     */
    fun canDeleteForEveryone(mine: Boolean, isGroup: Boolean, iAmManager: Boolean): Boolean =
        mine || (isGroup && iAmManager)

    /**
     * 这一项该给哪些菜单项。
     *
     * @param convSeq     归档项的 conv_seq。**`<= 0` 一律回空表**——同 iOS 的前置门槛
     *                    （`m.convSeq <= 0` 直接返回 nil，长按无反应）：没有序号就既定位不了、
     *                    也删不掉，弹一个全是死项的菜单比不弹更糟。
     * @param downloading 下载中或已暂停（`DownloadPhase.Downloading` / `Paused`）。
     * @param mine        这条是我发的。
     */
    fun availableFor(
        convSeq: Long,
        downloading: Boolean,
        mine: Boolean,
        isGroup: Boolean,
        iAmManager: Boolean,
    ): List<ArchiveAction> {
        if (convSeq <= 0L) return emptyList()
        return buildList {
            add(ArchiveAction.Forward)
            add(ArchiveAction.LocateInChat)
            if (downloading) add(ArchiveAction.CancelDownload)
            add(ArchiveAction.HideForMe)
            if (canDeleteForEveryone(mine, isGroup, iAmManager)) add(ArchiveAction.DeleteForEveryone)
        }
    }
}

/**
 * 归档菜单作用的那一条消息。
 *
 * 为什么不直接用 [com.libeyond.imandroid.sdk.api.ConvMediaItem]：**链接页签的行不是它**——
 * 链接不是独立 `content_type`，服务端归档接口不覆盖，那一格扫的是本地
 * [com.libeyond.imandroid.data.db.MessageEntity]。两种来源取个交集，菜单那一层就只认这一种。
 */
data class ArchiveTarget(
    val convSeq: Long,
    val sender: String,
    val contentType: String,
    val content: String,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val caption: String? = null,
    val timestamp: Long = 0,
    /**
     * 封面 / 像素 / 时长 / 内嵌缩略。**转发要靠它们**（判据 [Forward.attributesOf]）——
     * 归档查看器的「更多 → 转发」走的就是这条路，不带的话转出去的视频在收端没有封面、
     * 按方块排版，而且事后补不回来（2026-09-16 用户报，与聊天页转发同一个根因）。
     */
    val poster: String? = null,
    val thumb: String? = null,
    val mediaW: Int? = null,
    val mediaH: Int? = null,
    val duration: Int? = null,
    /**
     * 语音振幅指纹（仅 voice）。**从服务端归档进来的那条路恒为 null**——
     * `ConvMediaItem` 没有这个字段（`internal/conversation/media.go` 不回带），
     * 所以从「语音」页签长按转发出去的语音在收端只有等高条纹。从本地消息进来的那条路有。
     */
    val waveform: String? = null,
) {
    /**
     * 转成一条可转发的消息。
     *
     * 只填 `MessageService.forward` 真正会读的那些字段（content / contentType /
     * fileName / fileSize / caption + [Forward.attributesOf] 读的那五个媒体元数据），
     * 其余留默认——**别顺手多填**：填错比缺字段更难查，而这条实体只活到转发发出为止，不落库。
     */
    fun toMessageEntity(owner: String, convId: String) = com.libeyond.imandroid.data.db.MessageEntity(
        ownerUid = owner,
        convId = convId,
        convSeq = convSeq,
        sender = sender,
        contentType = contentType,
        content = content,
        fileName = fileName,
        fileSize = fileSize,
        caption = caption,
        timestamp = timestamp,
        poster = poster,
        thumb = thumb,
        mediaW = mediaW,
        mediaH = mediaH,
        duration = duration,
        waveform = waveform,
    )
}

/** 服务端归档项 → 菜单目标。 */
fun com.libeyond.imandroid.sdk.api.ConvMediaItem.toArchiveTarget() = ArchiveTarget(
    convSeq = convSeq,
    sender = sender,
    contentType = contentType,
    content = content,
    fileName = fileName.takeIf { it.isNotBlank() },
    fileSize = fileSize.takeIf { it > 0 },
    caption = caption.takeIf { it.isNotBlank() },
    timestamp = timestamp,
    poster = poster.takeIf { it.isNotBlank() },
    thumb = thumb.takeIf { it.isNotBlank() },
    mediaW = mediaW.takeIf { it > 0 },
    mediaH = mediaH.takeIf { it > 0 },
    duration = duration.takeIf { it > 0 },
)

/** 本地消息（链接页签那一格）→ 菜单目标。 */
fun com.libeyond.imandroid.data.db.MessageEntity.toArchiveTarget() = ArchiveTarget(
    convSeq = convSeq,
    sender = sender,
    contentType = contentType,
    content = content,
    fileName = fileName,
    fileSize = fileSize,
    caption = caption,
    timestamp = timestamp,
    poster = poster,
    thumb = thumb,
    mediaW = mediaW,
    mediaH = mediaH,
    duration = duration,
    waveform = waveform,
)
