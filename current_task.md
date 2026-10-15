# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档；每次大瘦身前的全量快照在其顶部，最新一份是 2026-10-03）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点
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

- **OPPO（MTK）4K 视频没有封面/缩略**：硬解上限 2560×1440，`MediaMetadataRetriever` 与 `ContentResolver.loadThumbnail` 都失败（实测），宫格与待发气泡同样空白；发出去的视频没有 `poster`。设备限制，别当 bug 反复查；真要兜底得自己 `MediaCodec` 走软解。
- **App 的省电模式（设置里「始终开启」）会让视频预加载生效值为关**：`MediaBubbles`/`AlbumBubble` 对**接收侧**视频只画磨砂、不拉封面；自己发的（`ungated`）必须照常画封面，别再把这个分支放开到发送方。测省电相关 UI 前先看 `shared_prefs/im_power_saving.xml` 的 `mode`。
- **OPPO 禁 `screenrecord`（`/sdcard`、`/data/local/tmp` 都 Permission denied）**：抓过程用循环 `adb exec-out screencap -p`（每张约 0.4s）。`pm revoke` 也被禁，改权限走「应用详情 → 权限管理」界面。

- **群资料页有本地快照**（`data/GroupInfoCache.kt`，`filesDir/group_info/<uid>_<convId>.json`，剔成员表）：进页先画快照、网络回来覆盖；没快照退 `GroupInfoPlaceholder`（只画顶栏 + 头像 + 群名，**不画任何依赖角色/开关/人数的入口**，默认值会让权限判定放开）。拉不到有「点击重试」。
- **登录被顶（账号在别处登录）后应用不会立刻回登录页**：群资料等页面请求全 401，页面停着；重启才见「你的账号已在别处登录」。未修，属登录失效反馈问题。

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
