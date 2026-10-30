# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档；每次大瘦身前的全量快照在其顶部，最新一份是 2026-10-03）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点
- **两步验证（TOTP + 恢复码，2026-10-30，未提交；设计 IMServer `docs/design/TWO_STEP_VERIFICATION_DESIGN.md` + 草图）**：隐私页「两步验证」行转活（右值 开启/关闭，进页重取）→ `PrivacyPage.TwoStep` / `TwoStepHost`（状态与动作在 `TwoStepController`，由 `PrivacySecurityHost` 持有，请求挂 `client.scope`，返回再进不丢恢复码）+ `screens/TwoStep*Screen.kt`；登录页同页二步 `LoginTwoStepPane`（ticket 在 `LoginTwoStep`，只在内存）；API `sdk/api/TwoStepApi`（`ApiException.data` 取 ticket / attempts_left）；纯规则 `data/TwoStepRules`；`SysEvents` 补 `twostep_recovery_used`；设置搜索已登记。OPPO 自测走通开启/恢复码/重新生成/关闭/登录二步（注意：OPPO 密码键盘是安全键盘，弹起时截图全黑；`adb input` 的 Enter 会落到返回键焦点上）。
- **清除所有对话（2026-10-29，未提交；设计 IMServer `docs/design/CLEAR_ALL_CONVERSATIONS_DESIGN.md` + 草图）**：隐私页「数据」组首行转活（Trash2 白图标、底 `danger`，不登记搜索）→ `MePage.ClearAllConversations(2)` / `ClearAllConversationsHost` + `screens/ClearAllConversationsScreen`；版式抽成共用 `screens/DangerConfirmPage`（注销账号页同用，`IMErrorText` 加了 `style` 参）。红按钮 → `IMConfirmDialog`（N=打开时 `visibleConversationCount`）→ `ConversationsApi.clearAll()`（`sdk/api/ClearAllResult`）→ **成功后**才动本机：`MessageRepository.clearAllToFloors`（`MessageRepositoryClearFloor.kt`，与 `clearToFloor` 共用事务体 `clearInTx`，N 个会话单事务，位点取 `items[].cleared_at`，复用 `conv_clear_floor_local`，无迁移）→ `HistoryFloors` 作废 → `downloads.clearAll()`（不含 Coil 头像缓存）→ `FcmNotifications.clearAll()`；会话红点/未读由行派生自然归零；编排与「失败不半清」顺序在 `data/ClearAllConversations`，入口 `sdk/IMClientClearAll.kt`。请求挂 `client.scope`，返回后不再 toast。i18n 键 `privacy.clear_all.*` 上游已在 strings.json，已重新生成。单测 `ClearAllConversationsTest`（JVM）+ instrumented `ClearAllToFloorsTest`（`am instrument`，未用 connectedAndroidTest）。
- **删群会话后从「我的群组」进去发消息，会话列表显示「未命名群聊」（2026-10-28 修，未提交）**：根因＝`MessageRepository.onAck`（自己发的消息 ack）经 `bumpConversation` 在本地没有该会话时造了没标题的壳，但它不像 `onIncoming` 那样把「造了壳」回给调用方，`MessageService` 没拉列表补标题；删除会话会移走会话行，所以必现。修法：`onAck` 返回 `Boolean`（造了壳），`MessageService` 的 ACK 分支据此 `refreshConversations()`；instrumented 用例 `ackOnUnknownConversationReportsStubSoCallerRefreshesTitle`（先红后绿），OPPO 实测复现路径已恢复显示「学习交流」。iOS（`viewWillAppear` 必 reload）与 Web（`onAck` → `scheduleListRefresh`）读码确认无此问题。
- **删除会话＝清到位点（2026-10-28，未提交；契约 IMServer `OFFLINE_BACKLOG_DESIGN` §6.7.1）**：`data/MessageRepositoryClearFloor.kt` 的 `clearToFloor` 是唯一例程（清消息+区间+抬位点+游标落位点，位点口径 `ClearFloor.floorOnClear` = 有 `cleared_at` 取 max(本机,服务端)，否则本机 head）；`clearConversation`、`deleteConversationLocal`（本机删除，用 DELETE 回包 `cleared_at`）、`conv_update(delete)`、`applyConversationList`（项 `cleared_at` > 本机位点→跑例程，行重写位点取 max；返回被对齐的会话 id，Service 丢 `HistoryFloors`）共用。位点另存独立小表 `conv_clear_floor_local`（Room v21，`MIGRATION_20_21` 回填非零 `clearedUpTo`；与 iOS 同构，会话行删除不丢）：`clearToFloor` 同事务只增写入，有效位点＝max(行, 表)，`persistPage`/窗口/搜索/媒体/日历/横幅均按它过滤，复现壳行（`ConversationEntity.withFloor`）带着位点与游标出生，删除瞬间在途的旧 sync 页写不进 ≤ 位点的行。`MigrationTest`（v20→v21）与新增 instrumented 用例待在设备上用 `am instrument` 跑。
- **导出我的数据（2026-10-26，未提交、未装机；设计 IMServer `docs/design/DATA_EXPORT_DESIGN.md`）**：`MePage.ExportData(2)`，隐私页「数据」组活行；`ExportDataHost`（2s 轮询 latest，页面离开即停；下载=先 `CreateDocument` 选位置→再换 `download_url`→`HttpClient.download` 流式写入；下载跑在 `IMClient.exportDownloads`（`data/ExportDownloader`，应用作用域，旋转/离开页面不中断，重进恢复「下载中」，仅点取消或退出登录才中断+删半截文件；失败 toast；下载中态 `DownloadingBlock`：进度条+体积+红粗体提醒，字节进度经 `ProgressThrottle` 节流 100ms/1%，总长度 ≤0 走不定长，单测 `ProgressThrottleTest`/`ExportDownloaderTest`，2026-10-28 OPPO 用 629MB 包实测：进度/旋转/取消/完成通过；下载中隐藏完成卡；取消先清 0 字节再删——ColorOS `deleteDocument` 返回成功却不真删，会留 0 字节空壳，属厂商行为不处理）；`ExportRules.nextViewChangeAt` + Host delay 到点自动切已过期/冷却结束）/ `ExportDataScreen` / `data/ExportRules`（视图态、冷却向上取整、按钮规则）/ `sdk/api/ExportApi`；错误码 200010/200011 入 `ErrCode`+`ErrorText`。单测 `ExportRulesTest`/`ExportApiTest`。大包后台下载不支持（无前台服务，被系统回收即中断，需重新下载），见设计稿 §7。
- **自动删除消息 v2（2026-10-21，未提交、未装机；设计 IMServer `docs/design/AUTO_DELETE_DESIGN.md` v2，契约 PROTOCOL §6.8.1）**：会话级共享、**不追溯**。判据 `data/AutoDelete.kt`：`expired = sec>0 && ts>=from && ts+sec*1000<=now`，过期集合是窗口 `[from, now-sec*1000]`（`window()`，无 floorTs）；`AutoDeleteTest` 读向量 `auto_delete.json`（`src/test/resources` 拷贝并比对真源）。会话行 `autoDeleteSec`+`autoDeleteFrom`（Room v20 + `MIGRATION_19_20`：v1 个人档位清 0 不迁移；`MigrationTest` 已写**未跑**，需设备）；`GET /conversations` / `conv_update` / `GET settings` 成对覆盖 sec+from。入库即过滤、`purgeExpired`（SQL `BETWEEN lo AND hi`，预览越过窗口取 `from` 之前最后一条可见消息、`lastTimestamp` 不清零、没变不写库，`AutoDeletePreview.stale/keepAutoDeleteTail`）、`AutoDeleteSweeper`（conv_update 仅 `needsSweep` 才扫；列表刷新 / RESUMED / 每 30s 照旧）、`sdk/api/AutoDeleteFloor` 镜像丢过期项。UI：隐私页账号默认（不弹确认）；`ConvAutoDeletePage`（新>0 且≠当前弹说明性确认，单聊/群两套正文+页脚，主按钮「开启」非 destructive；`setConvAutoDelete` 是**唯一**带 `auto_delete_sec` 的 PUT 点，成功后回读 GET 取权威 from）；群非群主/管理员行与档位页只读（toast `autodel_group_admin_only`，不发 PUT；`300204` 同 toast 并回滚）。系统消息 `sys_event=auto_delete` 在 `SysEvents.resolve`（按 `sys_args.sec` 选 on/off，缺字段/无操作者回退 content；聊天气泡与会话列表预览同源）。instrumented：`AutoDeletePurgeTest`（已改 v2，未跑）。设置搜索登记 `privacy_auto_delete`（**iOS/Web 登记表待同步**）。
- **会话列表编辑模式（2026-10-20，OPPO 已装实测，未提交）**：`IMTopBar` 左槽 `leftLabel`「编辑 / 完成」→ `SelectionCheck` 勾选圈 + 底栏 `ConversationEditBar`（TabRoot 底栏插槽换成选择栏，不与列表重叠）；状态 / 批量执行在 `ui/ChatsEditing.kt`，选择集与 `runBounded` 在 `data/ConversationSelection.kt`（`ConversationSelectionTest` 读共用向量）；`MessageService.deleteConversation(refresh=)` 批量时不逐条拉列表。设计 `IMServer/docs/design/CONV_LIST_EDIT_MODE_DESIGN.md` + `sketches/CONV_LIST_EDIT_MODE_UX_SKETCH.html`；共用向量 `IMServer/docs/conformance/conv_selection.json`；新增 i18n 键 `conv.edit.*`（7 个，已重新生成三端）。
- **注销账号·通话入口与免密提示（2026-10-20，已提交）**：已注销对端不可呼叫——单聊入口拦截（toast `rtc.call.peer_deleted`）、群通话选人 / 通话中邀请候选剔除 `deleted`；冷静期账号免密登录显示本地化 `err.200005`，不再露服务端英文。口径见 `IMServer/docs/design/DELETE_ACCOUNT_DESIGN.md` §3.5/§3.6。已实测通过（回拨拦截 / 群选人 / 英文文案）。限制（已确认属预期）：杀 App 冷启动后直接点通话记录，对端资料未加载，靠服务端 `invite-check` 兜底，提示笼统「对方暂时无法被邀请」。
- **注销账号（2026-10-20，已收尾提交 main，未 push；设计 IMServer `docs/design/DELETE_ACCOUNT_DESIGN.md`，契约 PROTOCOL §6.16 已对账）**：WS error `reason=account_deleted` → `SessionEndReason.AccountDeleted`（`IMSocketManager`）→ `IMClient` 按 owner 清本账号 Room 缓存（`wipeLocalChatData(owner)`，不动其它账号）；用户/好友/群成员/会话摘要 `deleted`/`peer_deleted` 统一 `normalized()` + `displayName` 压过备注（`DeletedUser.name`），`IMAvatar` 对「已注销用户」画灰底人形，资料页对已注销不给发消息/加好友；阻断卡群名可点（`LocalOpenGroupInfo` → MainScreen 开群聊+群资料）；300003 显示拒收注释；自己资料页两个入口（我页 / `UserProfileHost.SelfProfile`）都带注销组（`LocalAccountDeleted`）。OPPO 真机（user 999 隔离空间）自测全通过，code-review 已跑。
  - **后续补强（同日）**：`ConversationEntity.peerDeleted`（Room v18 迁移 `MIGRATION_17_18` + androidTest `MigrationTest`，已在 OPPO user 999 实跑通过）；`DeletedUser`（uid 登记，Compose 可订阅，`normalized()` 登记/注销）取代标题字符串比对，`IMAvatar` / 聊天副标题 / 会话预览发送者名 / 聊天气泡发送者名 / 单聊详情（隐藏消息·通话·备注·用户名，更多只留清空/删好友）都按它判；注销成功与 `account_deleted` 路径除六表外还清媒体缓存目录（整清，不分账号）、`group_info`、`pending_media`、`voice_pending` 与 5 个账号相关 prefs（`LocalWipe`）；阻断卡点群名进群后返回回到注销页（点击前把 `settingsRoute` 设为 `DeleteAccount`）。
  - **独立 review 修复（同日）**：`LocalWipe` 按账号分键偏好（`viewed.<uid>` / `folders.<uid>`）只删被注销账号的键；`account_deleted` 帧先 `generation++` 并关 socket 再 endSession（防迟到帧回灌）；阻断卡点群名同时切到「我」Tab；`SessionStore.clear()` 改 `commit()`（apply 异步，进程被杀会让登录态复活）；`onAccountDeleted` 先立 `accountDeleted` 旗再 logout；`logout()` 清 `DeletedUser` 登记。未修：`IMSocketManager` 外的内存态 `VoiceLocalStore` 未随 wipe 复位、`destructive` 行统一居中（产品决定）、`DeletedUser` 冷启动 LaunchedEffect 播种有一帧闪、`peerDeleted` 判据四处重复、无 `delete_at` 时恢复弹窗标题正文相同（需新文案）、iOS 尚无 `account_deleted` 处理（IMProgram grep 无）。
  - **残留**：①离线设备走 401=Revoked，不清缓存（设计允许 best-effort）；②系统消息里嵌的名字仍是服务端快照；③`strings.json` 里的新键由上游维护（本仓 `chat_reject_recipient_deleted` 已就位）。
- **二维码展示三端对齐（2026-10-16，已三端自测 + 全量测试，未提交）**：名片码/群二维码/群邀请链接卡片头部（头像·名字·成员数/@句柄）竖排居中（iOS `IMQRCardView`、Web `QRCardModal`+扫码结果分支）；iOS 扫一扫「我的二维码」改为嵌入 `IMQRCardViewController`（同 Android 共用整页）；群邀请链接页改为链接框 + 分享/复制 + 「查看二维码」，不画码（三端）；群码有效期提示三端统一为「X月X日 前有效，重置后旧码立即失效」（无到期时间退回 7 天口径）；链接页不放重置（重置只在二维码页）；新增 i18n 键 `qr.card.share_link/view_code/group_link_hint_expiry` 已入 `strings.json` 并重新生成。
- **置顶消息底部面板（2026-10-15，未提交，待真机目测；设计 IMServer `docs/design/PINNED_PANEL_DESIGN.md` + 稿 `sketches/PINNED_PANEL_UX_SKETCH.html`）**：
  - 横幅：整条 / ≡（恒显）= 开面板；恒显最新置顶（`PinnedBanner.latest`），kicker「置顶消息 · N 条 · 发送者」（`PinnedBanner.kicker` + `chat_banner_pinned_count`）；轮转（`index`/`clampIndex`/`nextIndex`/`tapPinned`）、`unpinCurrent`、`PinnedListDialog` 均已删。
  - 面板 `ui/PinnedSheet.kt`：`ModalBottomSheet` + `IMTopBar`（✕ / 标题 / 仅有权限显副标题）+ 壁纸底 `LazyColumn`（每行 = 只读 `Bubble`，日期胶囊，定位图标 32 圆紧挨气泡：我发的在左、别人的在右）+ 流内底栏「取消所有置顶」（`IMConfirmDialog` → `POST …/pinned/clear`）。`sheetBody()` 半屏时把内容区缩回可见区（底栏不被推出屏）。
  - 只读：`Bubble(readOnly, onTap)` / `MediaContent(readOnly)` / `VoiceBubbleBody(readOnly)`——无查看器 / 播放 / 链接 @ 名片 文件 引用 头像点击、无下载环、正文 ≤8 行、气泡包裹内容宽（最大宽 = 行宽 − 头像列，图标列 40 由行先让出）。
  - 长按：`PinnedPanel.menuActions`（取消置顶仅有权限 / 复制仅文本 / 定位）→ 新 `components/WindowContextMenu.kt`（从 `PendingMenuPopup` 抽出的窗口级 `MessageContextMenu` 包装，两处共用）。取消只作用所长按那条：乐观摘行 + pin op(false) + 1.5s 兜底重拉。
  - 数据：`PinnedMessage.message`（完整消息对象）；兜底 `PinnedPanel.resolve`（本地行 > message > 类型词纯文本）；`MsgOpData.batchUnpinSeqs`（clear 广播 targets 帧）→ `MessageService` 逐条落未置顶，`MsgOpSignal.unpin` 让横幅/面板先本地摘。图标 `components/LocateInChatIcon.kt`（§8 路径）。
  - 单测：`PinnedPanelTest`（新）、`PinnedBannerTest`（去轮转、latest/kicker）、`BatchDeleteWireTest`（unpin targets），已变异验红。i18n 由 `gen-i18n.mjs --out-root` 生成后只拷 Android 两份（strings.json 已由上游补键）。
- **聊天文件夹（2026-10-14，已提交 75fcd48；Tab 长按菜单锚定 feb9529）**：`ChatFolders.kt`/`ChatFolderStore.kt`/`ChatFoldersApi.kt`、`ChatFolderTabs.kt`（Tab 条 + 横滑）、`ConversationMenu.kt`（自 MainScreen 拆出，文件夹项第一位）、管理/编辑/选择三页；`IMColors.segmentPill` 深色药丸 token；设计见 IMServer `docs/design/CHAT_FOLDERS_DESIGN.md`。
- **失效三端口径对齐（2026-10-14，已提交并推送 a30e39a / 04640c5）**：
  - **口径**：服务端已清理即标失效，**不看本机有无缓存/原件**（取代 2026-08-07「铁律 A」）；`stateOf` 失效排在就绪之前。
  - **探测**：进页探（`rememberGate(probeExpiry)` → `verifyExpired(probeOnce)`），**本机已就绪的不在宫格/气泡里批量探**；并发上限 4，**满了就丢**（`tryAcquire`，撤记号，下次进组合再探）；查看器当前页进来不带 `probeOnce` 真探一次，命中经状态流回刷各面。
  - **查看器**：失效页黑底 + `OctagonX`，「更多」去掉下载/复制/收藏/转发（`SheetItem.needsMedia`）；失效图视频长按菜单去掉复制图片/转发/收藏（`availableFor(mediaGone)`）；群资料/详情宫格失效视频只留磨砂。
  - **重开全新状态**：宿主 `GroupInfoHost`/`ChatDetailHost` 每次打开自增 `viewingToken` 换键；`MediaViewerScreen` 内**不再**按 startSeq 换键（未确认消息拿到 conv_seq 会变，会重置翻页）。
  - **三端查看器/长按对齐（2026-10-14，未提交）**：「更多」同项同序 = 下载（内置）·定位到聊天位置·收藏·复制（仅图）·转发·删除，群资料/详情查看器与聊天页同（`archiveViewerMoreItems` 补收藏/复制）；失效页去掉要字节项。宫格长按「定位到聊天」第一位（`ArchiveActions`）。聊天长按**不再给举报**（多选批量举报/资料页保留）。
  - 已知：`GroupInfoHost` 598/600、`ChatHost` 599/600 靠压行过关，下次改动即撞线，需专门拆分。失效样本 5 个文件仍在 scratchpad `expiry-backup/`，用户决定不还原。
- **气泡尾角 + 会话列表待审行 + 转文字业务码文案（2026-10-13，未 commit，待真机目测）**：有尾气泡自己右下/对方左下直角（`BubbleShape.kt`，待发文件/语音气泡同轮廓）；群待审时预览行只显红字「[入群申请 N]」不接上一条消息（对齐 iOS/Web）；转文字 REST 失败按业务码给专用文案（`ErrorText` 增 5001xx）。已知差异：`AppearancePreview` 的气泡预览仍是四角全圆，未跟尾角。
- **好友成立系统消息 + 收藏页紧凑行 + 发名片多选确认 + 会话列表时间锚点（2026-10-13，未 commit，待真机目测）**：
  - `friend_accepted`：`SysEvents.GROUP` 加无参表项，聊天页居中灰字与会话列表预览共用；`IncomingRule.countsAsUnread` 对空 sender 兜底，`ChatJumpButton` 的 ↓N 改走同一判据（此前把 system 当对方消息计数）。
  - 收藏页（`FavoriteRows.kt`，尺寸常量 `FavoriteRowMetrics` 取自 iOS）：文本/记录 92、文件 92、语音 108、名片 82（头像 44 + 名字 + 时间 + @句柄 + 「由X分享」）；分隔线缩进统一 16dp（`ArchiveSeparatorInset`，同时作用于详情页文件/链接/语音行）。
  - 发名片：`ContactCardPicker`（`PickListScreen` 多选上限 9）→ `ContactCardConfirmSheet`（标题 / 首张预览卡 / 摘要 / 发送·取消）→ `MediaSendFlow.sendContactCards` 逐张发；判据 `ContactCardSend`。`FriendPickerScreen` 已删。
  - 会话列表：时间挂名称行右端、角标/圆点挂预览行右端（各自独立锚定，`UnreadMark.shown`）。
- **群通知两行 + 相册合并（2026-10-13，已 OPPO 真机验证）**：`FcmNotifications.post` 群聊按一对一 MessagingStyle 画（不设 conversationTitle / isGroupConversation），标题=群名、正文=`发送人: 内容`；`ConversationLines` 同发送人同 `album_id` 合成一行、后到成员不再重响（`FcmPayload.albumId`）。
**详情页用户名缺失 / 先闪「添加好友」（2026-10-10，已修，OPPO 已装待目测）**：根因 = `MainScreen.knownFriends` 只在启动拉一次，启动后才加的好友不在表里；详情页按钮读进页重拉的 `friend`（闪一下就对），用户名行却读旧 `knownFriends`（一直空）。修：`data/PeerRelation.kt` 把好友态/拉黑/句柄从同一份 `friend` 派生（`PeerRelationTest`，变异验红）；`ChatDetailHost` 新增 `onFriendsRefreshed` 回灌；`MainScreen` 订阅 `friendEvents` 重拉 `knownFriends`；资料页分支 `knownRelation` 改读 `friend`。iOS 不需要改（`IMFriendStateStore` 每次拉列表都刷新）。已跑 `/code-review --fix`；OPPO 实测通过（启动后才加的好友：进详情页不再先闪「添加好友」，用户名正常）。补丁：`relationSettled`（表里没有该好友时重拉返回前不画操作排）+ `ContactsHost.onFriendsLoaded` 回灌 knownFriends。
**「新的朋友」补「已添加」段 + 通讯录 Tab 角标（2026-10-09，未 commit，设计 `IMServer/docs/design/NEW_FRIENDS_DESIGN.md`）**：
- `NewFriendsScreen` 三段（待我确认 / 已发出 / 已添加），已发出行补「等待验证」、已添加行右侧「已添加」、点行进资料页；无删除入口。段数据 `data/RecentAdded.kt` `recentAdded()`（`RECENT_ADDED_DAYS`=30 / `RECENT_ADDED_MAX`=50，`RecentAddedTest`）。
- `ContactsHost`：资料页记 `profileOrigin`（从新的朋友进则返回新的朋友，且按深度 2 做 push 转场）；同意/拒绝加忙态防连点 + 失败吐司。
- 通讯录 Tab 角标：`MainScreen` 持有计数（`ui/ContactsPending.kt` 冷启动/好友帧拉 pending），`ContactsHost.onPendingCount` 即时覆盖；`ui/components/UnreadCapsule.kt` 与入口行共用。**注意**：会话 Tab 现状是 8dp 蓝点不是数字胶囊，通讯录 Tab 按设计稿画数字胶囊，两者形态不同。
- 待办：设备目视未做（见下），CLIENT_PARITY 由上游更新。

**以下为此前已提交内容：**
- 被拒文案 / 撤回墓碑 / 失败红点对齐 iOS（eb92509）：200102/200103 微信式文案（`chat_reject_*`）、300001 说明行；撤回改居中胶囊 `RecallNote` + 重新编辑；红点统一 `ui/components/FailBadge.kt`（被拒收的不可点）。
- 发送失败红点 + ack 超时（5ce8f4d）：`SendAckTimeouts` 同 client_msg_id 每 5s 重发、3 次后判失败（`LocalSendError.ACK_TIMEOUT`=5002），判失败后不再自动补发、点红点重发。OPPO 真机复测通过（2026-10-15：断网发送 → 5s 重发 → 约 20s 红❗；点红❗转「发送中」再失败回红❗；长按可删）。
- 通话被踢后现场重启（784935c）：`RtcCall` 存 `Wanted`，`placeSingle/placeGroup/fetchCallHistory` 先 `restartIfStopped()`；`RtcRestartPolicy` 有单测。

- **10-08 令牌过期不再当成被踢**：握手 401 先续期（`ws/WakeAction.kt` `unauthorizedActionFor`），续期被拒才回登录页；`manualClose` 初值 true，`connect()` 前唤醒不连；本机未登录时 FCM 消息/来电/未接一律丢并 `FcmToken.delete()`。
- 10-08 接 im-rtc 2.2.0 Kit tokenProvider（JitPack 正式版）。
- 聊天选图器收尾（10-07，已合入，细节见 archive）：被拒改同页空状态、原图流式上传、发送方视频不画磨砂、待发气泡预探宽高。

## 下一步

0a. **清除所有对话真机目测**（OPPO：N 文案/ toast / 失败红字 / 聊天页停留中被清保持打开且为空 / 清后新消息会话复现 / 杀进程重进不拉回历史 / 另一台在线设备同步）。
0. **置顶面板真机目测**（浅/深色；各类型各置顶一条：文本长文 / 图 / 视频 / 语音 / 文件 / 引用 / 转发 / 名片；半屏 ↔ 全屏拖动时底栏位置、长按菜单位置、定位跳转、取消所有后横幅消失、无权限成员视图）。
1. **离线积压剩余**：「@我的消息列表」入口（服务端接口已有，三端 UI 都缺，产品暂不要）。
2. **转场没接的几处**（`docs/UI_PARITY_IOS.md` §4）：`ChatDetailHost` 已全部接完，`GroupInfoHost` 仅 `Media` 一支已接。
   - 根因：`ui/components/PushTransition.kt` 要退场页按冻结的 `state` 渲染，而这些 host 是「关闭即把数据变量置空」，退场时数据已没，半路变白。改法 = 「是否打开」与「显示什么数据」拆两个变量，关闭只翻布尔（参照 `ChatDetailHost` 的 `viewingOpen`/`viewingData`）。
   - 余 8 支未动（Pick/Bans/Admins/JoinRequests/MemberProfile/MemberSearch/Manage/Qr），逐支来；**全部改完才把 9 支统一进一个 `PushTransition(page, depthOf)`**。`GroupInfoHost.kt` 现 596/600（已拆出 `GroupInfoDialogs/Live/Settings/Placeholder.kt`，贴线），**动它之前先拆文件**。
   - `ChatHost` 覆盖层栈（`ChatOverlays.kt`，`ChatRecord` 可嵌套压栈）范围最大且是产品判断，**动手前先问用户**。
3. 卡片弹层推广：@提及（`MentionPanel`）、选文件、日期跳转（现为 `AlertDialog`）、选联系人发名片（选人页仍整屏，确认 sheet 是半高底部卡片、无下拉手势），逐个换 `IMCardSheet`（已读详情已换）。
4. 收藏剩余：来源名到群昵称级；收藏页长按菜单是否已覆盖举报、翻译待核（聊天侧已做）。
5. 宫格：「五道防跳版闸」是否齐全待核（布局已按 `IMAlbumRowPattern` 对齐）。
6. `docs/UI_PARITY_IOS.md` 剩余 🔴：水滴头部形变、隐私页无障碍。
7. 群成员头像图：首字母色块对但无头像缓存；`POST /users/batch` 解析器已有（`UserProfileCache`，用于发送者头像），待接到群成员头像。
8. 小尾巴（均不影响行为）：「刷新补名失败不重试」靠下次连接/刷新自愈；`resolveKind` 对畸形 convId 当群聊（测试已钉住）；`VoiceLocalStore.putText` 每次重写整份 FIFO 顺序表；`PendingVoiceBubble` 里多余的 `coerceAtLeast(160.dp)`；`applySync` 对全是 `msg_op`/墓碑的一页也调 `bumpConversationFromLatest`（白多一次索引 SELECT）。

## 已知坑 / 限制
- **非群成员发送者卡片缓存无过期（2026-10-26）**：`UserProfileCache` 仅内存、无 TTL，只兜底「本地无好友也无群成员记录」的发送者（如超级群发言者）。对方改头像可见性（隐私「谁能看到」）后，这类人的旧头像要等重启 / 切换账号才更新；好友 / 群成员 / 会话列表都是整表覆盖，不受影响。要补：给缓存加短 TTL 或会话列表刷新时清。设计见 `IMServer/docs/design/PRIVACY_VISIBILITY_DESIGN.md` §5。

- **自动删除**：本机时钟决定清理时机，后台不扫（回前台补扫）；关闭/放宽不复活已清的本机消息；前台 30s tick 与到点消失未能在真机造「差几十秒满 1 天」的消息实测（服务端无法造旧时间戳），靠 `AutoDeletePurgeTest` + 判据向量覆盖；`MessageRepository.kt` 593 / `MessageService.kt` 583 行贴线。

- **OPPO（MTK）4K 视频没有封面/缩略**：硬解上限 2560×1440，`MediaMetadataRetriever` 与 `ContentResolver.loadThumbnail` 都失败（实测），宫格与待发气泡同样空白；发出去的视频没有 `poster`。设备限制，别当 bug 反复查；真要兜底得自己 `MediaCodec` 走软解。
- **App 的省电模式（设置里「始终开启」）会让视频预加载生效值为关**：`MediaBubbles`/`AlbumBubble` 对**接收侧**视频只画磨砂、不拉封面；自己发的（`ungated`）必须照常画封面，别再把这个分支放开到发送方。测省电相关 UI 前先看 `shared_prefs/im_power_saving.xml` 的 `mode`。
- **OPPO 禁 `screenrecord`（`/sdcard`、`/data/local/tmp` 都 Permission denied）**：抓过程用循环 `adb exec-out screencap -p`（每张约 0.4s）。`pm revoke` 也被禁，改权限走「应用详情 → 权限管理」界面。

- **群资料页有本地快照**（`data/GroupInfoCache.kt`，`filesDir/group_info/<uid>_<convId>.json`，剔成员表）：进页先画快照、网络回来覆盖；没快照退 `GroupInfoPlaceholder`（只画顶栏 + 头像 + 群名，**不画任何依赖角色/开关/人数的入口**，默认值会让权限判定放开）。拉不到有「点击重试」。
- **登录被顶（账号在别处登录）后应用不会立刻回登录页**：群资料等页面请求全 401，页面停着；重启才见「你的账号已在别处登录」。未修，属登录失效反馈问题。

- **置顶面板半屏高度是 M3 固定的 50%**（稿上约 60%）：`ModalBottomSheet` 的 PartiallyExpanded 锚点写死 `fullHeight/2`，不可配；要 60% 得自写 sheet，暂不做。
- **体量贴线**（硬闸 600，WARN 线 480）：`GroupInfoHost.kt` 596、`ChatHost.kt` 598（**只差 2 行**）、`MessageRepository.kt` 580、`ChatScreen.kt` 532、`MessageService.kt` 506 都很近；动它们之前先拆文件，新增一律放新文件。`Daos.kt` 别再加。
- **合并转发记录里的名片 →「发消息」换不了会话**（未修）：`ChatHost` 绑死单一 `convId`，点了只关资料层退回记录页。
- **`FavoritesHost`/`ChatPickerLayers`/`QrRouteHost` 进 `UserProfileHost` 没接 `onRemarkChanged`**（缓行，按 `ContactsHost` 同一套本机状态覆盖补即可）。
- **App Links 系统级深链接：明确暂不做**（2026-09-22 拍板）：需要固定公网 HTTPS 域名放 `assetlinks.json`；三端联动，等有部署域名再评估。**改原生 Material 风格：暂缓**（见 `docs/design/MATERIAL_DESIGN_EVAL.md`），别顺手改配色/图标/弹层。
- **浮层里的协程必须由宿主传 `scope`**：长按菜单/归档菜单点完就 `onDismiss()`，`rememberCoroutineScope()` 绑的是那一层，挂上的活当场被取消，日志里只有一句 `LeftCompositionCancellationException`。
- **注释里别写「斜杠 + 星号」的通配 MIME 字面量**：Kotlin 块注释可嵌套，会吞到文件尾，报「Missing '}'」。
- **门控三处要一起看**：聊天气泡（别人发的）守门控；归档/收藏宫格走 `DownloadPolicy.archiveTileUngated`；自己发的不门控。「已失效」不在豁免之列；宫格放行的图按原图地址加载；门控外观只准复用 `GateOverlays.kt`。
- **转发必须带** `poster`/`media_w`/`media_h`/`duration`/`thumb`/`waveform`/`mentions`/`mentionSpans`（判据 `Forward.attributesOf`，SYMMETRY 已登记），两个入口都要走到，待发行也要写；加一个「随消息走」的新字段要动七处，最后一处 `AckCarryOver.CARRIED` 有反射闸。
- **`ChatScreen.kt` 的滚动时序整组在 `ui/screens/ChatScroll.kt`**，五条 effect（首屏定位 / 贴底 / 跟底 / 翻页保位 / 可见即读 + 滚到底续要更新）读写同一份 `ChatScrollMarks`，必须待在一起；**宫格绝不能 LazyColumn item 里塞 LazyVerticalGrid**（由外层逐行渲染，`data/MediaGrid.kt`），列数 = 3（改它先改 `../IMServer/docs/UI_SPEC.md`）。
- **离线积压三条纪律**：①「有没有缺口」只问区间清单，不看 conv_seq 连不连号、不看同步游标；②清空位点 `clearedUpTo` 只增不减，会话快照整行重写必须保住它，服务端的搜索/日历/媒体结果一律滤 `conv_seq ≤ 位点`；③有缺口且离线时**不拿尾窗兜底**（可见即读会越过缺口把未读清掉），保持空窗并说「需要联网」。
- **粘贴判据三条纪律**（`data/PasteImage.kt`）；**长按菜单原位预览里链接与图片都不可点**（别改成在 `Bubble` 里按 `onLongPress != null` 判）；**`IMCardSheet` 关闭途中也拦返回键**；**覆盖页触摸屏蔽层绝不能 consume**（`TouchShield.kt`）。
- **握手 401 ≠ 被踢**：gateway 对过期 JWT 与吊销 sid 都回 401（正文 `unauthorized` / `session revoked`），本端一律先续期；续期接口对吊销 sid 回 100101 才算被踢。iOS/Web 每次连前先换票，没这条路。
- **OkHttp 收到服务端关闭帧不会自己回帧**：不在 `onClosing` 里 `close(1000, null)`，踢下线要等 25s。
- **会话列表离线首登是空白**；改密后的新续期凭据只出现一次（`NonCancellable` + 带发起时 uid）。
- **应用内浏览器**：release 不放行明文 http，debug 整个放开，debug 真机看不出来；明文 HTTP 只对 `10.0.2.2`/`localhost`/`127.0.0.1` 放行。
- **测试分层**：JVM 单测摸不到 Room 生成的真实 SQL——`@Query` / 迁移 / 事务回滚一律走 instrumented（`MigrationTest`、`ConvRangesTest`、`ConversationDaoHeadTest`、`MessageRepositoryRangesTest`、`MessageDayQueryTest`、`SyncPageBenchTest`、`TouchShieldTest`；`test.sh` 不跑它们）。跑法：`./gradlew installDebug installDebugAndroidTest` 后 `adb shell am instrument -w -e class <全类名> com.libeyond.imandroid.test/androidx.test.runner.AndroidJUnitRunner`；**别用 `connectedAndroidTest`**（会卸载 App 丢登录态）。新测试先变异看红一次。
- **`ONLY=X ./scripts/test.sh` 只认完整类名**（不吃通配符），偶有 `:media-picker` "No tests found"：改用 `./gradlew :app:testDebugUnitTest --tests '*A*'`；JVM 单测里 `android.util.Log` 是桩，先 `IMLog.useSinksForTest()`。
- **视频不转码、分片上传不跨进程续传、视频无本地缓存、图片不压缩（只挡 20MB）**；查看器本地打底最多 300 条，向更旧 / 向更新都靠服务端续拉。
- **本机 `JAVA_HOME` 是坏的**（`test.sh` 已自愈；直接跑 gradle 要自己 `export JAVA_HOME="$(/usr/libexec/java_home -v 17)"`）。
- **真机装包两台同时在线时 gradle 会往两台都装**：只装一台就 `export ANDROID_SERIAL=<serial>`；OPPO 禁 `pm clear` / `pm grant`，卸载重装后安装确认页按钮位置会变（先截图确认再点，曾误点「取消安装」）。

## 关联工程 / 常用命令

- 后端 `../IMServer`；iOS `../IMProgram`；Web `../im-web`。**参考实现**：判据类纯函数可照搬 Web，但守 `../IMServer/docs/SYMMETRY.md`——对称的是不变式，不是代码形状。
- 本仓：`./scripts/test.sh`（唯一测试入口）；`BUILD_ONLY=1 ./scripts/test.sh`（只编译）；`./scripts/install-hooks.sh`（每个 clone 一次）。
- 后端（改了后端代码**必须重启**）：`cd ../IMServer && ./scripts/dev.sh --no-tail`。
- **真机两台**：`GMGY7XF6LBJB6PFU`（OPPO PKD130，禁清数据）、`903KPED2067148`（**Pixel 2 XL，可 `pm clear`**，造「全新安装」场景用它）。装包 `./gradlew :app:installDebug`；连局域网后端（登录页底部可见地址，点它可改 `host:port`），够不着时 USB + `adb reverse`，步骤见 `../IMServer/docs/ops/DEPLOY.md` §2.E（⚠️ 音视频通话不能用这种方式测，媒体走 UDP）。登录页键盘弹出会让布局位移，`adb shell input` 要逐字段截图确认；`pm clear` 后服务器地址回到 `10.0.2.2:8080`。
- **压测副本后端**（离线积压验证用）：`cd ../IMServer && go run ./cmd/imserver -addr :8099 -db <副本库> -dev-login`，真机 `adb reverse tcp:8099 tcp:8099`；服务端重启会让已登录的 token 失效（要重登）；造未读：改副本库 `im_read_position` 后**重启后端**（服务端有缓存）。数据集与脚本见 `../IMServer/docs/ops/LOAD_TESTING.md`。
- 模拟器：AVD `im_test`（API 36 / pixel_5），连宿主机后端用 `10.0.2.2:8080`。
- 本地测试账号：`user1001` / `user1002` / `e2etest1`，密码 `123456`（user1002 密码被改密测试轮换过，登不上就用登录页「免密登录（开发）」）。
