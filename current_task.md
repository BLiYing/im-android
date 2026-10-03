# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档；每次大瘦身前的全量快照在其顶部，最新一份是 2026-10-03）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

无进行中的开发任务。**2026-10-02~03 补齐了离线积压整套**（设计与逐项状态以 `../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §5 为唯一来源，压测数字见 `../IMServer/docs/ops/LOAD_TESTING.md` §11）：
- **取数与同步**：C1 区间清单（消息 + 区间 + 游标同事务）· C2 `max_gap`/`too_long` · C3 进会话取数分流（锚点窗 / 尾窗 / 上滚 / **向下续接**）· C4 `conv_bump` 与 ↓ 先问区间清单 · C5 delivered 合批 · 本机清空位点 `clearedUpTo`（三端统一）。
- **整会话问题分流（C6）**：「本地齐不齐」问区间清单；会话内搜索服务端翻页；日历 / 跳最早 / 某天在有缺口时只认当天、否则「需要联网」；↓N 数 `tip − 读位点`；搜索/日历/媒体服务端结果滤清空位点；**查看器**本地打底取「点中那条所在本地段」+ 向更旧 / **向更新**（服务端 `media?after=`）续拉；资料页媒体/文件/语音页签并入服务端分页。
- **真机验证**（Pixel 2 XL，10 万积压大群）：点进 ~0.5s 落在未读分割线、↓ 角标 `99+`、锚点窗连续向下读（`#83`→`#1112`，每窗 ~0.6s）。`apply_ms` 偏高与进会话掉帧已定性（非 C1 开销、不随积压增长，`LOAD_TESTING` §11.8）。
- **2026-10-03 UI 对齐 iOS**：详情页 / 群资料 / 收藏页的页签条改为居中且贴合内容（`SegTabBar`，此前拉满、页签靠左、右侧一大片空底）；**点到自己进「我的资料」（可编辑）而非「用户信息」页**（`UserProfileHost` 一处收口，六处入口都经它）。
- **2026-10-03 群相关五项（用户报）**：① 群系统消息里自己显示「我」（`SysSegments.displayName(selfUid)`，聊天行 + 会话列表预览）；② 群资料「群管理」行右侧补「仅群主/管理员」（有待审时改红字角标，同 iOS）；③ 进群确认等开关改**乐观更新**（`GroupSettings.applied`；服务端读写本身没问题）；④ 入群申请页签换成共用 `SegTabBar`，**iOS 同步补了页签**（`IMJoinRequestsViewController`，此前 iOS 只拉 pending、同意后整条消失）；⑤ **点人统一走 `MemberProfileHost`**（= 聊天头像那条路），群成员 / 通讯录 / 收藏 / 扫码 / 聊天头像五处收口。
- **2026-10-03 第二批（用户报，Pixel 2 XL 真机 + iOS 模拟器验过）**：① **清空聊天记录**：会话行不再沉到列表最底（`clearConversation` 误把 `lastTimestamp` 置 0，现保留位置只清预览）；详情页「媒体/文件/语音」页签清空后立即重载（此前仍显示旧图）。清空**不动**未读数与已读位点——**产品确认保留**（2026-10-03：清空后未读还在、来新消息仍能看到是有意的好功能；三端一致，别当 bug 改）。② **进会话定位**：`ChatEntry.CONTEXT_BEFORE` 3→0（分割线/首条未读对齐视口顶部，同 iOS `anchorRowToTop:` 与 Web），此前留 3 条上下文把未读竖图挤到屏幕下沿、下半截被切；③ **视频门控态只显磨砂 thumb**（`VideoContent`：未下载不再画清晰 `poster`，同 iOS `IMImageCell` gated 分支）；宫格本就是磨砂；④ **选人页**（设管理员/转让群主/邀请/群通话共用 `PickListScreen`，iOS 同样共用 `IMFriendPickerViewController`）补顶部搜索 + A–Z 分组 + 右侧索引尺；⑤ **扫一扫页**对齐 iOS：取景框上移 40、提示在框下 22（原先压进框里）、相册钮在提示下 26，补底部「扫码 / 我的二维码」页签。
- **2026-10-03 补缺口第一批（`group` 帧消费）**：`MessageService` 新增 `groupEvents` 流 + 群待审数（`pendingCounts`，不落库）；群资料页收本群帧重拉；被移出/解散 → 提示 + 关聊天/群资料页 + 本机删会话行（`MessageRepository.removeConversation`；此前退群/解散后本机行会残留）；入群审批结果提示（`GroupEventsEffect`，必须画在 `MainScreen` 内容之后否则被盖住）；会话列表红字「[N 待审]」。Pixel 真机四条链路验过。**`GroupInfoHost.kt` 600 行（顶格），`ChatScreen`/`ChatHost`/`MessageService` 均 ≥594：动它们之前必须先拆。**
- **2026-10-03 补缺口第二、三批**：拒收说明行（`SendRejection`，单条与相册都有；200103 带「发送好友申请」）+ 输入栏禁言锁（`ComposerLock`，到期自动解锁）+ 加好友验证消息弹窗（`FriendRequestPrompt`，四处入口共用）+ 业务错误码本地化（`ErrorText`）；**置顶横幅整块**（`ChatBanners.kt` 状态 / `screens/ChatBannerStack.kt` 画 / `data/PinnedBanner.kt` 纯判据；置顶集合不落库，靠 `msgOps` 信号对齐）。`ChatHost` 的群资料状态拆到 `ChatGroupState.kt`。Pixel 真机验过。
- **2026-10-03 补缺口第四~七批（对照 iOS，CLIENT_PARITY 对应行已改）**：群已读 ✓✓ + 「N 人已读」（`ReadTick`/`ReadReceiptsSheet`）· 编辑/翻译/单条举报（`ChatMessageOps`）· 长文本三档（`LongText`/`TextReader`）· 禁言时长档/移出确认（`GroupMemberMenu`）· 返回键未读徽标 · 登录补拉 hidden · 通讯录名片页签 + 分享名片 · 建群两步流 · `UserProfileCache`（`/users/batch`）· `RosterCache`（好友/群离线快照，Room v17）· 审批横幅直达入群申请列表 · 收藏「以聊天模式查看」。**上传整块**：`ChunkedUploader`（暂停/续传/取消，服务端 offset 为准）+ `PendingMediaStore`（≥8MB 私有副本 `filesDir/pending_media` + `.uploadid` 旁路）+ 常驻串行队列 + 气泡/宫格钮盘 + 待发行长按菜单；单张粘贴图+文字合并 caption。**未真机验**：暂停/续传/杀进程续传、待发行长按菜单、caption 粘贴路径、收藏邀请链接原生入群。
- 拆分：`ChatScreen` → `ChatJumpButton`、`MessageService` → `MessageSendSimple`；`ChatHost` 早先拆出 `ChatLookups` / `ChatTailSync`。

## 下一步

1. **真机验证欠账**（均已实现，需两台设备/两个账号才测得全；验完再合并对应分支）：
   - 通知与提示音 P1 第一批 `feature/notif-p1a`（横幅出现/点击/上滑/自动收/按住暂停/连发替换/进会话收起/预览关文案/通话中不出，「添加例外」另一端同步）与第二批 `feature/notif-p1b`（定时免打扰：三个入口时长菜单、到期后铃铛/角标/例外列表自动刷新、另一端 `conv_update` 显示「永久」、免打扰期间改置顶/标未读/群备注 `mute_until`）。
   - 设置 ▸ 最近通话剩余项：滚动分页时序、「未接」tab 连续翻页观感、`callEnded` 重拉首页、群聊行跳转、空/401/网络错误三态——需攒几通真实通话（含群通话）。
   - 归档查看器「更多」五项、合并转发记录页内翻页、长按预览里点图/点链接只关菜单；转发带 @ 图片到群后「别人视角」点被 @ 的名字与强提醒；「对方撤回」两种文案（单设备单账号测不出）。
   - **离线积压**：查看器**向更新方向**续拉只有单测 + 变异 + 服务端 curl，**没做真机端到端**（要停在缺口会话的历史段里点图，造数据成本高）；离线时的「只能翻已加载的部分」提示没在真机上看过。
2. **离线积压剩余**：「@我的消息列表」入口（服务端接口已有，三端 UI 都缺，产品暂不要）。
3. **转场没接的几处**（`docs/UI_PARITY_IOS.md` §4）：`ChatDetailHost` 已全部接完，`GroupInfoHost` 仅 `Media` 一支已接。
   - 根因：`ui/components/PushTransition.kt` 要退场页按冻结的 `state` 渲染，而这些 host 是「关闭即把数据变量置空」，退场时数据已没，半路变白。改法 = 「是否打开」与「显示什么数据」拆两个变量，关闭只翻布尔（参照 `ChatDetailHost` 的 `viewingOpen`/`viewingData`）。
   - 余 8 支未动（Pick/Bans/Admins/JoinRequests/MemberProfile/MemberSearch/Manage/Qr），逐支来；**全部改完才把 9 支统一进一个 `PushTransition(page, depthOf)`**。`GroupInfoHost.kt` 已贴 600 行硬闸，**动它之前先拆文件**。
   - `ChatHost` 覆盖层栈（`ChatOverlays.kt`，`ChatRecord` 可嵌套压栈）范围最大且是产品判断，**动手前先问用户**。
4. 卡片弹层推广：@提及、选文件、已读详情、日期跳转、选联系人发名片仍是整屏/底部面板，逐个换 `IMCardSheet`。
5. 收藏剩余：来源名到群昵称级；长按菜单缺举报、翻译。
6. 宫格按 `IMAlbumRowPattern` 重写布局 + 五道防跳版闸。
7. `docs/UI_PARITY_IOS.md` 剩余 🔴：水滴头部形变、「名片」页签、隐私页无障碍。
8. 群成员头像图：首字母色块对但无头像缓存，要先做 `POST /users/batch` 解析器。
9. 小尾巴（均不影响行为）：「刷新补名失败不重试」靠下次连接/刷新自愈；`resolveKind` 对畸形 convId 当群聊（测试已钉住）；`VoiceTranscriptStore.putText` 每次重写整份 FIFO 顺序表；`PendingVoiceBubble` 里多余的 `coerceAtLeast(160.dp)`；`applySync` 对全是 `msg_op`/墓碑的一页也调 `bumpConversationFromLatest`（白多一次索引 SELECT）。

## 已知坑 / 限制

- **体量贴线**（硬闸 600，WARN 线 480）：`GroupInfoHost.kt` 589、`ChatHost.kt` 568、`MessageRepository.kt` 554、`ChatScreen.kt` 549、`MessageService.kt` 546 都很近；动它们之前先拆文件，新增一律放新文件。`Daos.kt` 别再加。
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
