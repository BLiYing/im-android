# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **五个贴线文件按职责拆分 ✅（2026-09-16/17；纯重构，行为未改，后端零改动，不必真机回归）**：
> ①–④ 已提交 `93fff31`；⑤ 与注释订正待提交。test.sh 777 例绿。
> ① **`MessageRepository` 588→516**：两份**逐字同构**的预览文案合成一份 `data/MessagePreview.kt`（新判据 + 单测；
>    原先 HTTP 快照与落库行各走一份，分叉的表现是"刚发出去列表显示 A、重进 App 显示 B"）；`toEntity` →
>    `data/MessageMapping.kt`（「加一个随消息走的字段要动七处」的第一处，单独放好找）；`LocalSearchPage` 归到
>    它唯一的产地 `MessageWindowQueries.kt`。
> ② **`MessageService` 584→479**：按它自己 KDoc 那道缝切——**帧分派留下、同步编排搬走** → `data/MessageSync.kt`
>    （`onConnected` / `requestSync` / `applySync` / `resendInFlight` / `isLocalUri`）。**Android 补离线积压
>    （§4.11.1 的 `max_gap`、区间清单、sync 路径回 `delivered`）以后就动这个文件**。代价是 `repo` / `ownerProvider` /
>    `media` / `transmit` 放宽到 internal——**Kotlin 的 internal 是模块级，等于对整个 `:app` 敞开**，编译器守不住，
>    靠约定（实测目前没人绕过；review 该打回的信号是 `ui` 里冒出 `client.messages.repo` / `.transmit(`，
>    记在 `MessageService.repo` 的注释上）。`MessageRepository` 的 DAO 注释原先也写着"只为让同包的查询扩展够得着"，
>    **已一并改准**；`MessageSignals` 那句"调用点同包"讲的是扩展函数跨包要多一行 import，本来就对，没动。
> ③ **`ChatHost` 587→518**：相机/文件两个 launcher + **相机产物的跨进程落点 uri** → `ui/ChatMediaLaunchers.kt`；
>    三层整页覆盖层（资料 / 选联系人发名片 / 相册）→ `ui/ChatPickerLayers.kt`（同 `ChatViewerLayer` 套路，
>    **渲染顺序即层级**，返回键仍由宿主那个 `BackHandler` 一处派发）。
> ④ **`GroupInfoHost` 585→529**：选人页三种用途的名单/标题/空态判据 → `data/GroupPick.kt`（新判据 + 单测：
>    只列普通成员 / 剔掉自己 / 剔掉已在群的好友——**三条写反都不报错**，只在点下去之后被服务端拒）；画法 →
>    `ui/GroupPickPage.kt`；换群头像 → `ui/GroupAvatarPicker.kt`；转让确认框并入既有 `GroupInfoDialogs.kt`。
> 两个新判据**都变异验红过**。搬家逐条比对了返回键分层、`ForwardPickerLayer` 那处早退的相对位置、三层渲染顺序。
> ⑤ **`DetailArchive` 495→242**（2026-09-17）：四类归档项的**画法**（语音行 / 链接行 / 文件行 / 媒体格，
>    外加它们共用的 `archiveItemGestures`——长按要上报自己在窗口里的矩形，菜单贴着它弹）整组搬进
>    `ui/screens/ArchiveRows.kt`（286 行）。切口是「**画一条** vs **排布与调度**」：留下的是页签条与
>    `archiveTab` / `mediaGrid` / `archiveList`（谁先谁后、空态、分页）。**同包移动，零 import 变更**，
>    没有新判据也就没有新测试。同批把 `MessageRepository` 的 DAO 注释改准（理由见 ②）。
>
> 上一批：**与 iOS 的最后三条已知差异已收掉 ✅ 已改、未提交、待真机自测（2026-09-16；test.sh 764 例绿）**：
> ① **转发语音不再丢波形**：服务端一直收 `waveform`、iOS 一直带，只有本端的 `SendMsgData` 与待发行缺字段。
>    补齐七处（协议 → `Forward.attributesOf` → `createPending` → `transmit` → `resend` → `MIGRATION_8_9` → `AckCarryOver.CARRIED`）。
>    ⚠️ `AckCarryOverTest` 那张反射闸**按设计当场变红**，逼着在 CARRIED 里做决定——正是它防的第七次「只在发送者一侧坏」。
> ② **粘贴图的发送键归位到输入栏**（与 iOS 同）：为此**先拆 `ChatScreen.kt`**——把滚动时序整组平移进 `ChatScroll.kt`，
>    598→520，再加 `Composer.extraSendable`（正文有字**或**粘贴条挂着图就能发）。粘贴条自带的那颗「发送 n」已撤。
> ③ **长按菜单「复制」补齐 iOS 矩阵**：新判据 `copyKindOf`——caption 压过一切 / 图片 / 视频·文件复制链接 / 纯文本，
>    三档文案同 iOS；voice 与系统消息刻意不给。原先钉「复制只给文本」的老测试**按设计变红**，已改钉新矩阵。
>    **仅存 OS 级差异**：安卓没有位图剪贴板，复制图片放的是 `content://`，粘进纯文本框仍是 URI（粘回本 App 已能还原成图）。
>
> 上一批：**查看器 / 详情页六条用户报告 ✅ 已改、未提交、待真机自测（2026-09-16；test.sh 752 例绿，四个新判据均变异验红）**：
> ① **转发视频丢封面与尺寸**：`MessageService.forward` 只带 content/fileName/fileSize/caption，`poster`/`media_w`/`media_h`/`duration`/`thumb` 全没带
>    → 收端没封面 + 按「像素未知」走 `MediaDisplaySize` 方块兜底，**且事后补不回来**。判据 `data/Forward.kt` 的 `attributesOf`；
>    `ArchiveTarget` 同补五个字段（**归档查看器转发是第二个入口**，只补主路径仍会从那儿漏）；待发行也写（resend 从它读）。
>    **这是一条对称漏跟**：im-web `useForward.ts` 与 iOS `forwardEchoContent:` 一直带着，本端是最后补齐的一端，已登记 SYMMETRY。
> ② **详情页很卡、划不动、看不到全部照片**：宫格是 `LazyVerticalGrid` 塞在 `LazyColumn` 的 `item{}` 里 + 高度 `coerceAtMost(1200dp)`
>    ——纵向嵌套同向滚动（"卡"其实是竖向拖动被内层吃掉）+ 约 13 行之后的格子被裁在容器外。改成外层列表逐行渲染
>    （`data/MediaGrid.kt` + `DetailArchive.mediaGrid`）：无内层滚动容器、无高度上限、天然惰性。
> ③ **查看器「媒体」钮进了「聊天信息」设置页**：iOS 那颗开的是独立的 `IMConversationMediaViewController`（标题＝会话名）。
>    本端改为复用同一宿主的 `galleryOnly` 形态（收起头部与页签条），**不另起一页**——归档取数/长按菜单/查看器/转发选择页
>    整套接线都在宿主里，另写一份必然分叉（`ConvMediaScreen` 已因此被并掉过一次）。
> ④ **原视频下完胶囊不消失、也不切本地播放**：查看器从不因下载状态重组，`hasLocal` 停在下载开始前的答案。
>    新判据 `OriginalVideo.readyUrls` + `distinctUntilChanged`：只收「已就绪地址集合」，它只在下完那一刻变一次，
>    **每 64KB 一次的进度帧不触发重组**（直接 collect 整张状态表只会更卡）；`remember(readyUrls)` 是那根重算线的显式依赖。
> ⑤ **详情页打开的查看器比聊天页卡**：`archiveViewerPages` 摆在参数位上，每次重组都把整份归档 mapNotNull + sortedBy 一遍 → `remember`。
> ⑥ **本 App 内粘不上自己复制的图**：安卓剪贴板里是 `content://`，Compose `BasicTextField` 只收纯文本、把它 coerce 成一行 URI 插进正文。
>    新判据 `data/PasteImage.kt` 认出来摘掉、转成待发图挂在输入栏上方（`ui/PasteImageBar.kt`）；只认 `content://`，http/https 原样留着。
>    ⚠️ 这条判据**第一次跑就被自己的测试抓出真 bug**：`Char.isLetterOrDigit()` 是 Unicode 感知的、汉字也算字母，
>    「…3.jpg好看吗」会被整条当成 URI 吞掉。已改成逐字写 ASCII 区间。
>
> ⚠️ **交付前 `/code-review` 又抓出三条真问题（已修，755 例仍全绿）**，都在粘贴那条上：
>    ① **"一部分认领、一部分没认领"时，没认领的 URI 被拼到了正文末尾**——而代码上方的注释写着"留在原处"，
>       注释与实现自相矛盾。「看这个 content://weird 和这个 content://real.jpg」会变成「看这个 和这个 content://weird」，
>       用户没删没改、字却被搬了家。判据改成 `find` 带回**区间**、`removing` 只摘走认领的那几段（新测试钉着）；
>    ② **超过 9 张静默截断**——`add()` 自己的 KDoc 就写着「回报有没有被丢掉，调用方据此说一句，别静默」，
>       返回值却被调用方丢了。现在会吐司说一句；
>    ③ **非图片的 URI 留在正文里 → 每敲一个字都在主线程重跑一次 `ContentResolver.getType`+`query`**
>       （慢 provider 下有 ANR 风险）：加了按 URI 的否定缓存（`PasteImages.rejected`），问过一次就不再问。
>    另修两条规范项：就地写的十六进制颜色改成语义令牌 `overlayStrong`；媒体翻页续拉与归档取数的裸
>    `runCatching` 改成 `runCatchingCancellable`（裸的那个连 `CancellationException` 一起吞，页面关掉时
>    `onFailure` 还会去写状态）。**五个新判据现在都变异验红过。**
>
> 上一批：**查看器收口七条 ✅ 已改、未提交、待真机自测（2026-09-16；test.sh 734 例绿，三个新判据均变异验红，`/code-review` 抓出的翻页高危已修）**：
> ① **补左右翻页**：`data/MediaTimeline.kt`（谁进序列 / 定位 / 越界 / 续拉四组判据）+ `ui/screens/MediaViewerScreen.kt` 改 HorizontalPager，
>    **壳固定、内容翻页**（照 iOS `IMMediaPagerViewController` + `chromeless`），左上角补 i/N；聊天页入口序列 = 本地库打底
>    （新 Dao `convMedia`，上限 `MEDIA_TIMELINE_LIMIT`=300）+ 翻到最旧向 `GET /conversations/{id}/media` 续拉（`ui/ChatMediaTimelineState.kt`）；
>    归档入口 = 「媒体」页签已分页拉到的那几页，翻到头调同一个 `archive.loadMore()`。**续拉只能往更旧**（服务端游标 `conv_seq < cursor`），
>    更早一页拼到前面后按 `conv_seq` 重新定位当前那张（不挪下标会当场跳图）。
> ② **归档查看器补「更多」**（`ui/ArchiveViewer.kt`，单聊详情与群资料共用）：转发 / 定位 / 取消下载 / 两档删除，判据与执行复用归档
>    长按菜单那一份（`ArchiveActions` + 新抽出的 `runArchiveAction`）；转发选择页状态上提到宿主——**协程不能挂在会被关掉的那层**。
> ③ **长按菜单原位预览里点图片改成关菜单**（原先恒被吃掉，`MessageMenuItems.kt` 两处）。
> ④ **聊天记录（合并转发）页里的图也能翻页**：那份快照没有 conv_seq，用**它在 items 里的下标 +1** 当身份
>    （`RecordMedia.index` + `ChatRecordLayer.recordViewerPages`）；序列就是这份记录里的全部图/视频，不向服务端续拉。
> ⑤ **续拉失败/离线说一句**：查看器顶部「网络不通，只能翻已下载的部分」（`MediaViewerScreen.notice`；
>    聊天页来自 `ChatMediaTimeline.notice`，归档来自新加的 `ConvArchive.failed`）——§4.9 的口径是"可以少，不可以错；少了要说出来"。
> ⑥ **图片「复制」**（`ui/CopyImageAction.kt`）：复制的是 FileProvider 的 `content://`（先复制进 `cache/share`，白名单只开了它），
>    **不是位图字节**——粘进认 URI 的地方是同一张图，粘进纯文本框会是一段 URI。取字节与「存相册」共用 `MediaSaver.openSource`。
> ⑦ **「查看原视频」胶囊**（判据 `data/OriginalVideo.kt`）：没下到本地的视频是流式播的，给一枚胶囊主动拉原件，
>    文案带大小、下载中显百分比、失败可重试、下完切本地播放；**本端没有断点续传**（iOS 有），所以下载中再点不当暂停用。
> ⚠️ **翻页那条高危是 `/code-review` 抓出来的**：两个 `LaunchedEffect` 抢跑——续拉后 `pages` 变化先把锚点写成错的那条，
>    "回正"随即判定原地即可，画面静默跳图且连环自动续拉。修法：记锚点的那个 effect **key 里不能有 `pages`**（只认 `currentPage`）。
> 同期 iOS 修了「聊天页点图片不能翻页」+ 同根因的「预载图从未生效」，见 `../IMProgram/current_task.md`。
>
> 上一批：**聊天页四条用户报告 ✅ 已改、未提交、待真机自测（2026-09-16；708 例绿，code-reviewer 复查三条已处理）**，
> 逐条见 IMServer `docs/CLIENT_PARITY.md` 顶部与本仓 `docs/UI_PARITY_IOS.md` §3.5、§4.8 #8：
> ① **宫格视频格没有播放角标** → `AlbumBubble.kt` 补上，与单条视频气泡共用 `ui/components/VideoPlayBadge.kt`（判据 `AlbumLayout.showsPlayBadge`：就绪才画，上传 / 失败 / 门控期让位）；
> ② **查看器**：点画面即播放 / 暂停（`data/VideoTap.kt`，按 `playWhenReady` 判）；底部「时间 · 进度条 · 倍速」抬到按钮排上方（`VIDEO_BAR_BOTTOM`）；
>    右下角「更多 / 媒体 / 下载」（接线 `ui/ChatViewerLayer.kt`，判据 `data/ViewerActions.kt`，删除复用 `runMessageDelete`；「媒体」经 `MainScreen.infoTab` 直达详情媒体页签）；播完回到开头；
> ③ **转发页改卡片式 + 搜索**：新组件 `ui/components/CardSheet.kt`（`IMCardSheet`，iOS pageSheet，可下拉关）+ `SearchField.kt`；`ForwardPickerScreen` 默认单选确认 / 「多选」切换 / 剔除系统通知单聊（`Forward.pickable`）；
> ④ **链接**：根因是 `chatBodyText` 从不识别 URL、预览卡没点击、气泡轻点只认聊天记录卡、本端没有 WebView 页。正文链接蓝字下划线可点（`LinkDetect.urlRanges` 照抄 iOS 正则）、预览卡可点；
>    新增可复用应用内浏览器 `ui/screens/WebViewScreen.kt`，宿主 `ui/WebLinkHost.kt` 包在 AppRoot 外层，任何地方调 `LocalOpenLink.current` 打开；详情 / 群资料「链接」页签与聊天记录文件项也改走它。

## 下一步

0. **真机自测本批（2026-09-16）**：
   ① 宫格里下载完的视频格中间有播放钮、没下完的只显门控图标；
   ② 查看器：点画面开播 / 暂停、进度条与右下角三枚钮不重叠、拖进度条、倍速切档；「更多」五项（下载 / 定位到聊天位置 / 收藏 / 转发 / 删除，删除在可为所有人删时弹两档）；「媒体」进详情媒体页签，返回后再点标题进详情落在默认页签；
   ③ 转发：卡片滑上来、下拉 / 点遮罩 / 返回键关、列表滚到顶继续下拉跟手；单选弹确认、「多选」后发送(n)、超 9 个吐司、搜索名字与 uid；**点了发送紧接着按返回，转发照样发出**；名片分享与详情归档长按转发两处也走一遍；
   ④ 链接：正文里的链接蓝字下划线、点开是 App 内浏览器（标题 / 进度 / 返回键先网页后退 / ⋯ 菜单）、预览卡可点、**长按带链接的消息弹菜单后点预览里的链接只关菜单不开网页**、详情「链接」页签与聊天记录文件项点开。
1. **真机手测第三批（2026-09-15）**：冷启动不闪「还没有会话」、索引尺、标题居中与 ＋ 菜单、iOS 改密 ≈1–2s 下线、底栏蓝点（清单见 `current_task.archive.md` 顶部）。
2. **真机手测 09-15 三条 + 隐私与安全 + 数据和存储**（清单同上，均在归档顶部）；确认后分批提交（文件交叠，分开提交要按 hunk 挑）。
3. **转场没接的几处**（UI_PARITY_IOS §4 那行）：群资料 / 聊天信息内部子页等，内容读已置空的状态，要先改成按转场状态渲染。
4. **卡片弹层推广**：iOS 用 pageSheet 的还有 @提及、选文件、已读详情、日期跳转、选联系人发名片——本端对应页仍是整屏或底部面板，逐个换成 `IMCardSheet`（选好友建群 / 邀请顺带接 `ListSearch` 搜索框）。
5. **查看器**：功能面已收口（翻页 / 「更多」/ 复制 / 查看原视频 / 降级提示）。剩的是**能力差**而不是欠账：
   本端无断点续传（原视频下载中断＝从头来）、无 instrumented 测试（翻页与手势只能真机核对）。
6. **收藏的另一半**：收藏列表页、删除、长按菜单「收藏」、附件面板「从收藏发送」（`AttachItems.Kind.Favorite` 仍占位）。
7. **长按菜单缺项**：单条举报 / 收藏 / 翻译（`CLIENT_PARITY` 消息长按菜单那行）。
8. **宫格**：按 `IMAlbumRowPattern` 重写布局（每行列数决定格宽、1 列行高固定 150）+ 五道防跳版闸；另欠相册宫格逐格勾选。
9. 按 `docs/UI_PARITY_IOS.md` 剩下的 🔴：会话内搜索的📅日历与👤发件人过滤、水滴头部形变、语音页签内播放、「名片」页签、隐私页无障碍提示。
10. 按 `CLIENT_PARITY` 追 iOS：消息编辑（M4-5）→ 设置页其余 6 项 → 扫码读码半边（含本站邀请链接在 App 内走原生加群，iOS `routeInviteLinkIfOwn`）+ 头像裁切页 → 推送（M5）。
11. **群成员头像图**：首字母色块对，但无头像缓存；要先做 `POST /users/batch` 解析器。

## 已知坑 / 限制

- **日志里没有「当前页面」这个字段，本端也没有 Activity＝页面这回事**（2026-09-16 问答留档）：全 App 只有一个
  `MainActivity`，其余全是 Compose 可组合函数 + 自写状态机（`AppRoot`/`TabRoot`/`MainScreen`），**也没用
  Navigation-Compose 的 route**——页面切换就是改一个状态变量，运行时不存在现成的「页面名」可塞进日志。
  要按页面排障得先加一层屏幕栈（`LocalScreen` 之类）再让 `IMLog` 带上 `screen` 字段。现在只能靠 tag 猜。
- **宫格绝不能再用「LazyColumn 的 item 里塞 LazyVerticalGrid」**（2026-09-16 用户报，两条症状一个根因）：
  纵向嵌套同向滚动会把外层的竖向拖动吃掉（表现是"页面很卡、划不动"，不是性能问题），而为了给内层定高
  必然要算一个固定高度，一封顶就把超出的格子裁在容器外（表现是"看不到全部照片"）。
  正解是由外层列表逐行渲染（`data/MediaGrid.kt`）。
- **转发必须带 `poster`/`media_w`/`media_h`/`duration`/`thumb`**（判据 `Forward.attributesOf`，SYMMETRY 已登记）：
  漏带全程静默，只有收件人看得出来，且事后补不回来。**两个入口**（聊天页长按/多选、详情页归档查看器）都要走到，
  **待发行也要写**（resend 从它读）。`waveform` 仍带不走——`SendMsgData` 没这个字段，转发语音会丢波形（iOS 有）。
- **`ChatScreen.kt` 的滚动时序已整组搬到 `ui/screens/ChatScroll.kt`**（2026-09-16，598→520）：首屏定位 / 贴底收敛 /
  行变后跟底 / 翻页保位 / 可见即读这四条 effect **读写同一份 `ChatScrollMarks`、彼此有先后与互斥，必须待在一起**，
  别再往回挪、也别拆散——拆散的表现是"一条 effect 把另一条刚设的待办覆盖掉"。
- **加一个"随消息走"的新字段要动七处**（`waveform` 是第七次踩这一族）：协议负载 → `Forward.attributesOf` →
  `createPending` → `transmit` → `resend` → Room 迁移 → **`AckCarryOver.CARRIED`**。最后那处有反射闸
  （`AckCarryOverTest`）会当场变红逼你做决定；漏了的表现是**只在发送者自己那一侧坏**、对端全正常。
- **归档「语音」页签转发出去的语音仍没有波形**：`ConvMediaItem` 不回带 `waveform`（服务端 media 接口没有这一列），
  所以 `ArchiveTarget.waveform` 在那条路上恒为 null；从聊天页本地消息转发的那条有。
- **粘贴判据的三条纪律**（`data/PasteImage.kt`，都是 `/code-review` 抓出来才补齐的）：① 只摘走**自己认领的区间**，
  系统说不是图片的那段要**原地留着**——统一拼到末尾就是把用户打的字搬了家；② 超过 `MAX_PENDING` 要**说一句**，不许静默截断；
  ③ 问过系统确认不是图片的 URI 要**记住**（`PasteImages.rejected`）——它会留在正文里，而 `onInputChange` 每按一次键就重跑一遍判据，
  不记的话每次按键都在主线程同步查一次 `ContentResolver`。

- **长按菜单的原位预览里链接必须不可点**（2026-09-16）：`ChatMessageMenu` 画预览时把 `LocalOpenLink` 置空。**别改成在 `Bubble` 里按 `onLongPress != null` 判**——`ChatRowView` 传进来的长按回调恒非空，那条判据永远不生效（既有的 `onOpenMedia` 门控是同一个洞：预览里点图片被吃掉而不是关菜单，**2026-09-16 已修**——`ChatRowView` 那路传 `onOpenMedia = { onDismiss() }`，宫格那一格另补 clickable，两处都只关菜单、不开查看器）。
- **`IMCardSheet` 关闭途中也拦着返回键**：放行的话外层宿主直接把卡片状态置空，卡片离开组合、动画协程被取消，`dismiss(after)` 的回调不再执行——「点发送紧接着按返回」那次转发会丢。卡片的 `onConfirm` / `onCancel` 一律在滑出**之后**才回调。
- **应用内浏览器**：release 包的网络安全配置不放行明文 http，这类网页在 App 内必然打不开（失败页给「用浏览器打开」）；debug 变体整个放开明文，所以 debug 真机上看不出来。拉起应用的 scheme 只在用户点出来时交给系统。**本站邀请链接不走原生加群**。
- **OkHttp 收到服务端关闭帧不会自己回帧**（2026-09-15）：不在 `onClosing` 里 `close(1000, null)`，`onClosed` 永远不来、连接停在假「已连接」，
  踢下线 / 改密下线要等 25s ping 写失败才发现。回帧码不能照抄回调里的 code（服务端空负载 → 1005 保留码，会抛）。护栏 `ServerCloseKickTest`。
- **会话列表离线首登是空白**：服务端一次都没拉成功时 `ConversationListPhase` 停在 Loading（不下「没有会话」的结论），连上后自动补拉。
- **改密后的新续期凭据只出现一次**：任何新的「会让服务端轮换凭据」的调用都要照 `IMClient.changePassword` 那样
  `NonCancellable` 接住，**并带上发起时的 uid**（判据 `TokenSession.shouldAdoptRotated`，`RotatedRefreshGuardTest`）。
- **`TokenSession.logout` 的清空拿 `refreshLock`**：保证清空是最后一次写；代价是撞上在途续期时退出要等那个请求回来。
- **iOS 改密「参数错」码写错（100002）未修**——要修去 IMProgram `IMChangePasswordViewController.m` 顶部常量。
- **整份替换的 PUT body 不能用 `ProtocolJson` 直接编**（`encodeDefaults=false`，Go 端缺字段 = false）：照 `DownloadSettingsApi.putBody` 另起一个 Json 并配线上形状单测。
- **`ONLY=X ./scripts/test.sh` 跑不了**（`:media-picker` 报 "No tests found"）：改用 `./gradlew :app:testDebugUnitTest --tests '*A*' --tests '*B*'`。
- **JVM 单测里 `android.util.Log` 是桩、一调就抛**：先 `IMLog.useSinksForTest()`；本机 HTTP 服务用 `MediaDownloaderTest` 的 `TinyHttpServer`。
- **批量收藏 / 多目标转发的循环挂在聊天页的协程作用域上**：中途离开聊天页，后面还没发的不再发、回执吐司也不弹。
- **合并转发的「我」名是 `@句柄`**（`myPublicName()`），不是昵称；单聊引用块不显名（同 iOS）。
- **失效媒体判据只认本次进程里下载器登记过的 404/410**，没有 iOS 那种持久登记——没点过的失效图照样会被转出去。
- **六个贴线文件已全部拆开**（2026-09-16/17）：`GroupInfoHost.kt` 529、`ChatScreen.kt` 520、`MessageRepository.kt` 519、
  `ChatHost.kt` 518、`MessageService.kt` 484、`DetailArchive.kt` 242（上限 600，**WARN 线 480**——门禁里还 WARN 的
  就是前五个）。搬去哪见「当前焦点」。**别把新东西再往这五个里加**：它们离红线只剩 70–115 行，
  而本仓每次贴线都是"合并两条线的改动"那一下触顶的。真要再拆时优先照 `DetailArchive` 那一刀切
  （**画一条 vs 排布与调度**），比按行数硬切干净；`ui/screens/ArchiveRows.kt` 286 是拆出来的新文件，别再往它堆。
- 新消息自动贴底与 ↓ 按钮按**行数**判，键盘跟随按**像素**判，两套口径并存；发送后抑制窗 1s（iOS 0.5s）。
- **视频不转码**、**分片上传不跨进程续传**、**视频没有本地缓存**、**图片不压缩**（只挡 20MB）。
- **查看器翻页只能往更旧续拉**（服务端媒体接口是 `conv_seq < cursor` 的倒序分页，没有"往更新翻"的方向）：更新那一端以本地已有的为准，本地一次最多取 300 条（`MEDIA_TIMELINE_LIMIT`）。续拉失败即停、**不反复重试**，但会在查看器顶部说一句。归档里语音不能播。
- **查看器的「复制」复制的是 `content://` 不是位图字节**（安卓没有通用的"把位图放进剪贴板"做法）：粘进相册/聊天/邮件是同一张图，粘进纯文本框会得到一段 URI。iOS 复制的是 UIImage。
- **记录页（合并转发）里的翻页身份是下标**，不是 conv_seq——那份 JSON 快照不是本会话的消息。**同一份记录里若有两张一模一样的图**，点第二张仍从第一张的位置开始（下标身份天然唯一，不会错位；这里说的是内容重复，不影响定位）。
- **没有 instrumented 测试**：布局/滚动/手势/输入法全靠真机截图核对（像素测量用 `adb exec-out screencap` + PIL）。
- **「我」页 11 个入口接通 5 个**；外观令牌层在（`IMAppearance`），无持久化无界面；`values-night` 只跟系统深色。
- **明文 HTTP 只对 `10.0.2.2`/`localhost`/`127.0.0.1` 放行**（release）；真机连开发机走 debug 变体那份配置，别留到生产。
- **本机 `JAVA_HOME` 是坏的**：`scripts/test.sh` 已自愈；手拼 `./gradlew` 要先 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`。
- **`SessionStore` 用普通 SharedPreferences**，没有额外加密。

## 关联工程 / 常用命令

- 后端 `../IMServer`；iOS `../IMProgram`；Web `../im-web`。
- **参考实现**：判据类纯函数可照搬 Web（`entryWindow.ts`/`unreadBelow.ts`/`wake.ts`），
  但守 `../IMServer/docs/SYMMETRY.md`：**对称的是不变式，不是代码形状**。
- 本仓：
  ```bash
  ./scripts/test.sh                # 唯一测试入口（门禁 + 编译 + 单测）
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ./scripts/install-hooks.sh       # 每个 clone 一次
  python3 temp_verify.py           # 两轮交付前静态自检（先跑 test.sh）
  python3 temp_verify.py --e2e-privacy --bin <imserver> --workdir <scratch>   # 隔离实例实测改密轮换
  ```
- 后端（改了后端代码**必须重启**）：`cd ../IMServer && ./scripts/dev.sh --no-tail`
- 真机：`GMGY7XF6LBJB6PFU`，adb 在 `~/Library/Android/sdk/platform-tools/adb`。
- 模拟器：AVD `im_test`（API 36 / pixel_5），
  `~/Library/Android/sdk/emulator/emulator -avd im_test -no-snapshot-load -gpu swiftshader_indirect`；
  连宿主机后端用 `10.0.2.2:8080`。
- 本地测试账号：`user1001` / `user1002` / `e2etest1`，密码统一 `123456`。
