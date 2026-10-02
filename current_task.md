# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **2026-10-02 会话列表保位置 + 会话壳不露 convId + 我页资料断网兜底（真机验证通过）**：列表 `LazyListState` 提升到 `ui/MainScreen.kt`（`rememberSaveable`），进会话 Tab 层离开组合不再回顶部；`MessageRepository.bumpConversation` 造壳走 `data/ConversationStub.newConversationStub`（带 `peerUid`），`onIncoming` 返回是否新造壳 → `MessageService` NEW_MSG 分支后台 `refreshConversations()`；标题回退统一 `Forward.titleOf(conv, localNameOf)`；`keepNewerLocalTail` 防旧快照回退刚到的消息；本人资料副本 `sdk/session/MyProfileCodec` + `SessionStore.myProfileJson`（不存手机号，`clear()` 擦除）。已知限制：补名刷新失败不重试（下次连接/刷新自愈）；`resolveKind` 对畸形 convId 当群聊。状态表见 CLIENT_PARITY「陌生人首条消息的会话壳…」行。

> **2026-10-01 通知显示发送人头像**（PUSH_M5_DESIGN §3.6/§3.7）：通知大图标取发送人/群头像；`fcm/ConversationLines` + `FcmNotifications` 改成一个会话一条 `MessagingStyle` 通知、锁屏 `publicVersion`「N 条新消息」、撤回/已读去掉对应行（原 `shouldCancel`/`shouldClearOnRead` 已删）。
> **2026-10-01 别的端已读后清手机通知/角标**（PUSH_M5_DESIGN §3.5，真机验证通过）：`fcm/FcmNotifications.clearReadThrough`：收 `type=clear`、本人 receipt 帧（`applyPeerReceipt`）、本机已读（`MessageRepository.markRead`）时取消该会话已读段的通知。

> **2026-09-30 多选删除两档·改批量接口（2026-10-01 OPPO PKD130 + Pixel 2 XL 真机实测通过，已提交并推送 `a72e864`）**：`ui/ChatSelectionState.kt` 的 `BatchDeleteConfirm` 两档都改为
> 一次请求（`ConversationsApi.hideMessages` / `deleteMessagesForEveryone`，PROTOCOL §6.7.1/§6.7.2），成功项本地移除
> （hide → `applyMsgHidden`；everyone → `applyMsgOp(DELETE)` 顺带收回通知栏），失败汇总一句「N 条删除失败」；
> 第二档走 REST，断线也能删（不再先拦）。结果对回请求 `sdk/api/BatchDelete.okSeqs`，`msg_hidden` 批量帧 `MsgHiddenData.seqs()`；批量删除广播帧（一帧 `targets`，2026-10-01）`MsgOpData.batchDeleteSeqs` → `repo.removeMessages`（一条 `DELETE … IN`，Room 只失效一次）；
> `BatchDeleteWireTest` 3 例（先看红）。test.sh 1105 绿。本端**没有置顶横幅**，任务 3 不适用。
> **2026-10-01 真机已验**（`adb reverse` 走 USB，登录页服务器地址临时改成 127.0.0.1:18080，用完请改回；Pixel 2 XL 同样走此法，只验了「仅删除自己」+ 宫格逐格选，步骤见 IMServer docs/ops/DEPLOY.md §2.E）：两档批量、收/发整批一帧（日志 `messages_removed count=3`）、混选只一档、断服务「2 条删除失败」。群成员侧收「群主删他人」整批帧已验。**宫格逐格勾选已补齐（2026-10-01 真机验证）**：每格右上角一个选择圈（`AlbumTile.mark` ← `ChatSelection.tileMark`），多选态点一格=勾这一格（不开查看器、不触发门控下载），长按不再弹菜单；此前只能选中长按的那一格。`ChatSelectionTest` 新增 1 例（先看红）。

## 下一步

0g. **通知与提示音 P1 第二批（定时免打扰）的真机验证 + 合并到 main**（2026-09-29 已实现，
   `feature/notif-p1b` 分支）：三个入口（会话列表/聊天信息页/添加例外）的时长菜单样式与红色
   「取消免打扰」、到期后铃铛/角标/例外列表自动刷新（拨系统时间或设极短时长验）、另一端
   `conv_update` 同步显示「永久」、定时免打扰期间改置顶/标未读/群备注 `mute_until` 不变——
   需要两台设备/两个账号才能测全；建议与 `feature/notif-p1a`（第一批）一起验完再合并。
0f. **通知与提示音 P1 第一批的真机验证 + 合并到 main**（2026-09-29 已实现，见「当前焦点」，
   分支 `feature/notif-p1a` 尚未合并）：横幅出现/点击/上滑/自动收/按住暂停/连发替换/进入会话收起/
   预览关文案/动画关无位移/通话中不出，「添加例外」选中后另一端同步与置顶/标记未读原样带回——
   需要两台设备/两个账号互相发消息才能测全；验完再合并。
0e. **设置 ▸ 最近通话页面的剩余验证**（基础展示/筛选/回拨已于 2026-09-29 真机验证过，见「当前焦点」）：
   滚动分页时序、「未接」tab 过滤后自动连续翻页的实际观感、`callEnded` 到达时重拉首页的画面表现、
   群聊行点击跳转会话是否正确、空状态/401/网络错误三态的文案与重试——需要至少两个测试账号互相拨打
   几通电话（含群通话）攒出足够多真实通话记录再验。
0d. **群资料页「成员」tab 搜索入口的真机验证**（2026-09-28 已实现，见「当前焦点」，未测）：入口显隐
   门槛（>50 人）、搜索/去抖/翻页、选中后跳资料页整条链路，需要一个 50+ 人的测试群。
0b. **上几批仍欠的真机回归**（这两轮没动到、也没回归）：归档查看器「更多」五项、合并转发记录页内翻页、
   长按预览里点图/点链接只关菜单、以及 2026-09-15/16 那两批的清单（见 `current_task.archive.md` 顶部）。
0c. **本批（2026-09-23）真机回归**——📅/👤/撤回实时刷新/非 UTC+8 时区换算均已测过（见 archive），
   仍欠：**转发带 @ 图片到群里，"别人视角"点开被 @ 的名字、有没有收到强提醒**——真机 UI 这条链路没跑通
   （系统图片选择器多级页面盲点坐标屡次踩偏），也没有第二台设备/账号可以扮演"收端"；只验证到
   "提及渲染可点 + 点了跳资料页"这条基础设施是通的，`mentions` 随转发存活只有单测覆盖。
   另外**"对方撤回"的两种文案**（"XX/对方撤回了一条消息"）同样因为单设备单账号测不出来，只测了"自己撤回"。
1. **Android 离线积压（`../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §4.11.1 / §5 B3a）**：
   C2（sync 带 `max_gap`）最小切片已做，见「当前焦点」；范围明显比单条任务大，剩下按设计文档原有
   C1/C3/C4/C5/C6 分期，一次做一块：
   - **C2 收尾**（本轮的直接延伸，比新开一个 C 项小）：`too_long` 时的真机验证（见「当前焦点」）；
     超级群 `max_gap=0` 需要先给 `ConversationEntity` 加 `isSuper` 列（迁移）并在拉群资料时回填。
   - **C1 区间清单**：新 Room 表 `conv_range_local(owner_uid, conv_id, lo, hi)` + 纯函数（合并相邻区间、
     查询"某段是否齐全"，参照 Web `ranges.ts` 的判据，有单测）；写消息与扩区间同一事务（I1 不变量）。
     C1 是后面几项的地基，建议下一块就做它。
   - **C3 进会话 / 滚动**：`ui/ChatHost.kt` 目前固定本地 `ChatWindow.Tail`，`window_req` 只用于引用跳转/
     搜索命中——要接锚点开窗（未读首条不在本地尾部时定位不到）与上滚时查区间清单再决定问本地还是服务端。
   - **C4 ↓ / 跳号 / `conv_bump`**：`conv_bump` 目前在 `MessageService.kt` 落进忽略分支，超级群会话行
     收不到刷新；↓ 只换本地 Tail，没有"最新一页齐不齐"的判据。
   - **C5 `delivered` 回执**：`MessageService.kt` 只在 `NEW_MSG` 分支回，sync/window 路径一条都不回——
     离线补拉回来的消息，对端看不到「已送达」直到被已读覆盖。
   - **C6 八项分流剩余**：查看器翻页（已接）缺"离线降级提示"；日历/置顶判定未接；其余几项有缺口时
     静默给本地残缺答案。
   **别照抄 Web/iOS 的现成补丁**——设计文档 §4.11.1 原话："Android 进会话根本不走锚点开窗，那条调用点
   不存在"，得从头按 §4 的设计走，不是抄一个 diff。
2. **转场没接的几处**（`docs/UI_PARITY_IOS.md` §4 第 316 行；2026-09-28 勘察 + 拆分，范围超出单条
   任务，按下面顺序分块推进——第 1 块已落地，见「当前焦点」）：
   - **根因**：`ui/components/PushTransition.kt` 要求退场页在 ~300ms 滑出动画期间，内容仍按
     `AnimatedContent` **冻结住的 `state` 参数**渲染；但「没接」的几处目前是"关闭即把数据变量置空"
     （如 `ChatDetailHost` 的 `onClose = { viewing = null }`），退场那一刻数据已经没了，分支渲染出
     空白——套上去就是文档说的"半路变白"，不是简单包一层就行。
   - **实际是三种不同形态，不是一次改法通吃**：
     ① **互斥枚举页 + 关闭置空数据**的 host（`GroupInfoHost` 9 个分支；`ChatDetailHost` 的 `Media`/
     `viewing` 那支）：改法 = 把"是否打开"和"显示什么数据"拆成两个变量，关闭只翻布尔、数据留着
     （下次打开才覆盖），`when` 改成吃 `PushTransition` 传入的冻结 `state`，不直接读外部活变量。
     ② `ChatHost` 的 `Set<Layer>` 覆盖层栈（`ChatOverlays.kt`：Viewer/UserProfile/**ChatRecord
     可嵌套压栈**/FriendPicker/MediaPicker/FavoritePicker/Forward/ContextMenu）：连接口都对不上——
     `PushTransition` 吃单一 `S` + `depthOf`，`ChatRecord` 是任意深度的栈，要么扩展 `PushTransition`
     支持栈式深度、要么给它单独一套转场，工作量明显更大，且"要不要真做 iOS 式滑动"更像产品判断，
     不是纯技术判断——动手前应该先问用户，不能假定文档写着就该做。
     ③ **"用户资料内部"不是独立第三类**：核实过 `UserProfileHost`/`UserProfileScreen` 自己没有内部
     子页导航（`RemarkEditDialog` 是弹窗，不需要滑动转场），这一条实际指的是 `ContactsHost`/
     `ChatDetailHost`/`GroupInfoHost` 各自"进用户资料页"那条边，并入①。
   - **建议顺序（从最小最安全开始）**：
     1. ✅ **已做（2026-09-28）**：`ChatDetailHost` 的 `Detail↔Profile`——没有数据置空问题，不改
        数据模型，只重排渲染结构。
     2. ✅ **已做（2026-09-28，见「当前焦点」）**：`ChatDetailHost` 的 `Media`——按①的"open 标志/
        data 分离、关闭不清 data"模型改造（`viewingOpen`/`viewingData`），三分支合并进同一个
        `PushTransition`。`ChatDetailHost` 这个 host 的转场至此**全部接完**。
     3. `GroupInfoHost` 的 9 个分支（Pick/Bans/Admins/JoinRequests/MemberProfile/MemberSearch/
        Media/Manage/Qr，注：`GroupInfoPage` 目前 9 个值，`Detail` 不算转场目标）：同样是①的模型
        改造，但分支数是 `ChatDetailHost` 的 3 倍——一个分支一个分支来，别一次性全改。
        - ✅ **已做（2026-09-28，见「当前焦点」）**：`Media`——照抄 `ChatDetailHost` 的
          `viewingOpen`/`viewingData` 写法，只把这一支单独套了一层 `PushTransition`（没有把 9 个
          分支统一进一个大 `when`，那是下面第 4 步的活）。`GroupInfoHost.kt` 574→**587 行**，
          离 600 硬闸只剩 13 行——下一个分支动手前先看这个数字，必要时先拆文件再接。
        - 剩下 8 个（Pick/Bans/Admins/JoinRequests/MemberProfile/MemberSearch/Manage/Qr）未动，
          都是「关闭即整页替换 + 置空数据」的同一种坑，逐支来；哪支先做没有强制顺序，挑数据置空
          最明显、改动最小的那支（参照这轮 `Media` 与上两轮 `ChatDetailHost` 的判据）。
     4. 把 `GroupInfoHost` 全部 9 个分支真正统一进**一个** `PushTransition(targetState = page,
        depthOf = { it.depth })`（`page: GroupInfoPage` 走完整枚举、加 `depth` 字段，同
        `ChatDetailPage` 的写法）——**在第 3 步的 9 个分支逐个改完「开/关不清数据」之前，不要
        提前做这步**，否则等于把还没改好的分支也塞进转场，退场画面照样半路变白。
     5. `ChatHost` 覆盖层栈（②）：动手前先跟用户确认要不要做，范围最大且是产品判断。
3. **卡片弹层推广**：@提及、选文件、已读详情、日期跳转、选联系人发名片仍是整屏/底部面板，逐个换 `IMCardSheet`。
4. **收藏的剩余项**：「以聊天模式查看」（按来源会话分组下钻）、来源名到群昵称级（现只到好友备注/昵称/补拉名片）；**长按菜单缺项**：举报、翻译。
5. **宫格**按 `IMAlbumRowPattern` 重写布局 + 五道防跳版闸；相册宫格逐格勾选。
6. 按 `docs/UI_PARITY_IOS.md` 剩下的 🔴（📅/👤 已于 2026-09-23 收口）：水滴头部形变、「名片」页签、隐私页无障碍（语音页签内播放已于 2026-09-28 随语音 P1 完成，见 CLIENT_PARITY）。
7. 按 `CLIENT_PARITY` 追 iOS：消息编辑（M4-5）→ 设置页其余 6 项 → 头像裁切页 → 推送（M5，
   **批次 1「账号级通知设置」已实现，见「当前焦点」；批次 2「推送令牌 + 前台保活服务 + 本机通知」
   未动**——`../IMServer/docs/design/PUSH_M5_DESIGN.md` §5/§6，需要后端 `im_push_token` 表/
   `app_state` 帧/APNs 落地后本仓才能接令牌上报，Android 侧另需前台服务与 `NotificationCompat` 渲染）。
8. **群成员头像图**：首字母色块对，但无头像缓存；要先做 `POST /users/batch` 解析器。

## 已知坑 / 限制

- **`FavoritesHost`/`ChatPickerLayers`/`QrRouteHost` 三处进 `UserProfileHost` 没接 `onRemarkChanged`**
  （2026-09-28，与 `ContactsHost`/`GroupInfoHost` 同一个坑，本次只补了后两处）：这三处进资料页都是
  「选人即用」场景（收藏来源名片、转发选目标、扫码加人），资料页本身没有像 ChatDetailHost/ContactsHost
  那样退回后立刻可见的常驻展示位，复现路径没那么直给，暂缓；要补的话按同一套模式（本机状态覆盖）接上即可。
- **语音转文字 code review 跳过的两条小优化（2026-09-28，均不影响行为，留作后续）**：
  ① `VoiceTranscriptStore.putText` 每写一条都把整份 FIFO 顺序表（最多 2000 条）`joinToString` 后重写
  SharedPreferences——填满缓存近似 O(n²)，量小暂不改；要改可只在淘汰时写顺序表，或改落 Room。
  ② `PendingBubbles.kt` 的 `PendingVoiceBubble` 里 `bubbleWidthDp(...).dp.coerceAtLeast(160.dp)` 是多余的
  （`VoiceRules.bubbleWidthDp` 已保证 ≥160），可删。
- **`applySync` 每页只要 `c.messages` 非空就调用 `bumpConversationFromLatest`，哪怕这批全是 `msg_op`/
  墓碑这类非内容行**（2026-09-28，会话不上移/聊天页不跟底/补滚动条那批修复的 code review 发现，未改，
  留作后续优化）：这批消息会被 `onIncomingBatch` 内部过滤成空，白白多打一次 `latestWindow` 查询——
  影响可忽略（一次索引 SELECT），但要干净修得改 `onIncomingBatch` 的返回契约（让它顺带报"这批里有没有
  真消息"），而它还被 `WINDOW_RESP`（锚点开窗，翻历史用）共用，改动会超出当时那次 diff 的范围。
  见 `data/MessageSync.kt` 的 `applySync` / `bumpConversationFromLatest`。

- **`MessageService.resend`（点红❗重试）此前对 `Failed` 状态的行完全无效**（2026-09-28 随语音录制一起修）：
  它查 `repo.inFlight()`，而那个方法只挑 `state=Sending` 的行——`Failed` 的行永远查不到，点了跟没点一样，
  过去没人发现是因为没人真的手测过这条路径。已改成 `pendingByClientId` 按 `clientMsgId` 直查、不按状态过滤。
  顺带把「正文仍是本地 uri」的处理也理顺了：语音走 `MediaSendPipeline.reuploadVoice`（本地文件在应用私有
  目录，进程重启后仍读得到，重新上传即可）；图片/视频的本地 uri 是系统相册 `content://`，读权限随发起进程
  一起没了，读不回来，改成直接标失败（`chat_resend_upload_incomplete`），**不再有原来"会把 `content://`
  当正文发给对端"的风险**（原风险仅存在于"点了对失败行确实生效"的前提下，而那个前提本身就不成立，
  相当于一次修复顺带堵上一个从未真正暴露过的隐患）。
- **语音发送成功后本地 `voice_pending/` 缓存文件不清理**（2026-09-28，TODO）：字节已经在服务端、也已经靠
  `MediaCache.adopt` 进了本地缓存，理论上可以删源文件，但要等到**确认 ack**（不是仅仅"上传成功"）才安全删
  ——现在没做，量小暂不影响使用，是独立的小任务，别顺手在别的改动里夹带。
- **语音布局以 iOS/Web 现行实现为准，不以草图 v2.5 字面为准**（2026-09-28 核对）：草图写「时长 · HH:mm ✓✓」同一行、红点在 meta 行左，
  但 2026-08-27 用户拍板「时间独立一行」、设计文档 §7（08-30）定「红点右上角」，iOS `IMVoiceBubbleCell` / Web `VoiceBubble` 均已照此。
  **播放标识用 `seq:<conv_seq>`**（iOS 用 serverMsgID）：资料页归档条目只有 conv_seq，这样两处红点 / 播放态同步。
  Web 录的分片 MP4 `MediaPlayer.duration` 读出 11ms——分母一律回落消息 `duration`（`VoiceRules.effectiveDurationMs`）。

- **合并转发记录里的名片 →「发消息」不能真正换会话**（2026-09-25 真机回归发现，未修）：`ChatHost.kt`
  把 `ChatRecordLayer` 内名片卡片的「发消息」接到同一份 `openUser` 状态，点了只 `onCloseUser()`
  关资料层、退回记录页，不会直接落回聊天——因为 `ChatHost` 绑死单一 `convId`，没法就地换会话。
  名片指向的人恰好是当前会话对方时不明显（多按一次返回就到了），指向别人时发消息形同无效。
- **App Links 系统级深链接：明确暂不做**（2026-09-22 用户拍板）。指从系统任意来源（短信/微信/邮件/
  系统相机扫码）点开邀请链接直接拉起 App、跳过浏览器——区别于已接的"App 内已打开的链接里拦截"
  （`QrRouteHost`/`isOwnInviteLink`）。卡点是基础设施而非代码量：Android 侧需在清单声明
  `autoVerify` + 域名，同时**该域名根目录**要放一份 `assetlinks.json` 供系统联网校验，这要求一个
  真实可访问、带 HTTPS 证书的**固定公网域名**；本项目开发/联调目前全用 `10.0.2.2`/局域网 IP
  （`-public-url` 只是开发占位），没有这样的域名就无法验证功能是否真的生效。iOS 对应的 Universal
  Links、Web 落地页「在网页版中打开」按钮同理都还没做——是三端联动的活。**等项目有固定部署域名后
  再重新评估**，别在没有域名前先写代码——写了也测不出有没有生效。
- **要不要改原生 Material Design 风格：暂缓，未决策**（2026-09-18 讨论，见
  [`docs/design/MATERIAL_DESIGN_EVAL.md`](docs/design/MATERIAL_DESIGN_EVAL.md)）。触发条件：想清楚
  「这个 Android 端主要给谁用」再重新评估；别在没想清楚前顺手改配色/图标/弹层。
- **浮层里的协程必须由宿主传 `scope`**（2026-09-17 真机抓到）：长按菜单/归档菜单点完就 `onDismiss()`，
  `rememberCoroutineScope()` 绑的是那一层，挂上去的活会**当场被取消**且只在日志里留一句
  `LeftCompositionCancellationException`。已收口的有 `ChatMessageMenu`、`ArchiveActionsHost`、
  `ArchiveViewer.kt` 整族；`ChatViewerLayer` 恒在组合里，自建是安全的。
- **注释里别写「斜杠 + 星号」的通配 MIME 字面量**：Kotlin 块注释**可嵌套**，它会当场开一层内层注释，
  本段的结束符只关掉里层、外层一路吞到文件尾，而编译器报的是「Missing '}'」（2026-09-17 栽过一次）。
- **门控的边界**：聊天气泡（别人发的）守门控；**归档/收藏宫格按 `DownloadPolicy.archiveTileUngated`**（自己发的、图片且策略放行才直接显示）；**自己发的（`mine`）不门控**。
  改任一处都要回头看另两处，否则又会出现"某个入口看不到图"。
  ⚠️ **三条边界条件**：① **「已失效」不在豁免之列**——自己发的媒体一样会被服务端清理，豁免掉的话
  它会装作正常、点进去是空查看器（2026-09-17 `/code-review` 抓出，判据 `ungated`）；
  ② 宫格里放行的图片**按原图地址加载**（服务端没有缩略图接口，`thumb` 只是 ~20px 磨砂占位），翻历史会拉原件流量；
  与 iOS 一致（图片策略放行即按 URL 加载），账号把图片设成手动时宫格只显磨砂 + ↓。
  ③ **门控外观只准复用 `GateOverlays.kt` 的组件**（聊天页、详情页、收藏页三处同一套），别再另画徽标。
- **归档与收藏里语音仍不能播**（见「下一步 1」）；**波形已能显示**（本地兜底），但**只覆盖本地已加载的那一段**，
  拿不到就是等高条纹（协议允许的合法状态）。发送者名同理：成员表 → 本地昵称快照 → 空（**不落内部 uid**）。
- **宫格列数 = 3**（`MediaGrid.COLUMNS`，与 iOS 两处宫格逐字一致）。改它先改 `../IMServer/docs/UI_SPEC.md`。
- **转发必须带 `poster`/`media_w`/`media_h`/`duration`/`thumb`/`waveform`/`mentions`/`mentionSpans`**（判据
  `Forward.attributesOf`，SYMMETRY 已登记）：漏带全程静默，只有收件人看得出来，且事后补不回来。
  **两个入口**（聊天页长按/多选、详情页归档查看器）都要走到，**待发行也要写**（resend 从它读）；
  ⚠️ `mentions`/`mentionSpans` 只在图片/视频上带（文本消息 iOS 本就不转发提及）；归档/收藏两个转发入口
  服务端不回带 `mentionSpans`，恒为空，是结构性限制不是漏改（2026-09-23）。
- **加一个"随消息走"的新字段要动七处**：协议负载 → `Forward.attributesOf` → `createPending` → `transmit` →
  `resend` → Room 迁移 → **`AckCarryOver.CARRIED`**。最后那处有反射闸会当场变红逼你做决定。
- **`ChatScreen.kt` 的滚动时序已整组搬到 `ui/screens/ChatScroll.kt`**：四条 effect 读写同一份 `ChatScrollMarks`、
  彼此有先后与互斥，**必须待在一起**，别再往回挪、也别拆散。
- **宫格绝不能再用「LazyColumn 的 item 里塞 LazyVerticalGrid」**：纵向嵌套同向滚动 + 固定高度封顶，
  两条症状一个根因。正解是由外层列表逐行渲染（`data/MediaGrid.kt`）。
- **粘贴判据的三条纪律**（`data/PasteImage.kt`）：只摘走自己认领的区间；超 `MAX_PENDING` 要说一句；
  问过系统确认不是图片的 URI 要记住（`PasteImages.rejected`），否则每次按键都在主线程查一次 `ContentResolver`。
- **长按菜单的原位预览里链接与图片都不可点**（点了只关菜单）：`ChatMessageMenu` 画预览时把 `LocalOpenLink`
  置空、`onOpenMedia` 传 `{ onDismiss() }`。别改成在 `Bubble` 里按 `onLongPress != null` 判（那条判据永远不生效）。
- **`IMCardSheet` 关闭途中也拦着返回键**：放行会让「点发送紧接着按返回」那次转发丢。
- **OkHttp 收到服务端关闭帧不会自己回帧**：不在 `onClosing` 里 `close(1000, null)`，踢下线/改密下线要等 25s。
- **会话列表离线首登是空白**；**改密后的新续期凭据只出现一次**（`NonCancellable` + 带发起时 uid）。
- **应用内浏览器**：release 包不放行明文 http，debug 变体整个放开，所以 debug 真机看不出来。
- **`ONLY=X ./scripts/test.sh` 跑不了**（`:media-picker` 报 "No tests found"）：改用
  `./gradlew :app:testDebugUnitTest --tests '*A*'`。**JVM 单测里 `android.util.Log` 是桩**，先 `IMLog.useSinksForTest()`。
- **覆盖页的触摸屏蔽层绝不能 consume**（`ui/components/TouchShield.kt` 文件头）：父级在 Main 阶段吞事件
  会让子列表的慢速拖动整次作废，症状是「有时能划有时划不动」。要屏蔽下层兄弟，占住命中测试就够了。
- **本仓 JVM 单测摸不到 Room 生成的真实 SQL**（没有任何测试真跑过 `@Query`，2026-09-23 真机抓到
  `MessageDao.search()` 一条 SQL 逻辑 bug——`test.sh` 879 例全绿却没测出来，见「当前焦点」）。DAO 层的
  `@Query` 改动**光靠单测不算数**，要么真机走一遍界面，要么起个 Robolectric/`Room.inMemoryDatabaseBuilder`
  的桩（本仓目前两者都没有，暂时只能靠真机）。
- **instrumented 测试只有 `TouchShieldTest` 一个，test.sh 不跑它**（要真机）。别用 `connectedAndroidTest`
  （跑完会卸载 App、丢登录态），用：`assembleDebug assembleDebugAndroidTest` → 两个 APK 各 `adb install -r` →
  `adb shell am instrument -w -e class com.libeyond.imandroid.ui.components.TouchShieldTest com.libeyond.imandroid.test/androidx.test.runner.AndroidJUnitRunner`。
  其余布局/滚动/手势/输入法仍靠真机截图核对
  （`adb exec-out screencap`；用 `uiautomator dump` 找控件坐标比按像素猜可靠）。
- **视频不转码**、**分片上传不跨进程续传**、**视频没有本地缓存**、**图片不压缩**（只挡 20MB）、**无断点续传**。
- **查看器翻页只能往更旧续拉**（服务端媒体接口是 `conv_seq < cursor` 倒序分页），本地一次最多取 300 条。
- **`GroupInfoHost.kt` 已顶到硬闸 600 行**（2026-09-28，本次接 `onRemarkChanged` 加的 2 行踩到线，
  已把新注释压到一行才卡着通过——**下一次改这个文件必须先拆**，不能再靠压行数混过去）。
  其余贴线文件（2026-09-23 `test.sh` 报的行数，WARN 线 480）：`ChatHost.kt` 595、`ChatScreen.kt` 557、
  `MessageService.kt` 547、`VoiceRecordUi.kt` 512——都还没触顶但都很近了，加东西前先规划拆分。
- **明文 HTTP 只对 `10.0.2.2`/`localhost`/`127.0.0.1` 放行**（release）；**本机 `JAVA_HOME` 是坏的**（test.sh 已自愈）。

## 关联工程 / 常用命令

- 后端 `../IMServer`；iOS `../IMProgram`；Web `../im-web`。
- **参考实现**：判据类纯函数可照搬 Web，但守 `../IMServer/docs/SYMMETRY.md`：**对称的是不变式，不是代码形状**。
- 本仓：
  ```bash
  ./scripts/test.sh                # 唯一测试入口（门禁 + 编译 + 单测）
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ./scripts/install-hooks.sh       # 每个 clone 一次
  ```
- 后端（改了后端代码**必须重启**）：`cd ../IMServer && ./scripts/dev.sh --no-tail`
- 真机：`GMGY7XF6LBJB6PFU`，adb 在 `~/Library/Android/sdk/platform-tools/adb`；
  装包 `adb -s GMGY7XF6LBJB6PFU install -r app/build/outputs/apk/debug/app-debug.apk`。
  **真机连的是 `192.168.1.12:8080`**（登录页底部可见），不是模拟器那个 `10.0.2.2`。
- 模拟器：AVD `im_test`（API 36 / pixel_5），连宿主机后端用 `10.0.2.2:8080`。
- 本地测试账号：`user1001` / `user1002` / `e2etest1`，密码统一 `123456`
  （**user1002 的密码已被改密测试轮换过，登不上就用登录页的「免密登录（开发）」**）。
