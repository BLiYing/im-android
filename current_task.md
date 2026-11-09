# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档；每次大瘦身前的全量快照在其顶部，最新一份是 2026-10-03）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点
- **移动端冒烟 T3（2026-11-09，未提交）**：`./scripts/ui-smoke.sh` = installDebug → 服务端造 ping → `scripts/ui_smoke_driver.py` 经 adb 按无障碍树操作真 App 回 pong → 服务端核对。不用 instrumentation / connectedAndroidTest（原因见设计 `../IMServer/docs/design/E2E_REGRESSION_DESIGN.md` §6）。
- **后台「安全风控」S4 客户端（2026-11-09，未提交，未装机）**：注册页可见时按输入框地址读免鉴权 `GET /api/v1/public-config`（`sdk/api/PublicConfigApi.kt`，失败/缺字段=开放），`register_enabled=false` 显示 `err_200007` 提示条并禁用注册按钮；串台守卫 `data/RegisterGate.kt`（按发起 host 记账）。`ErrCode.LOGIN_LOCKED/REGISTER_CLOSED`；`LoginError` 与 `ErrorText.friendlyText` 对 200006 按 `data.retry_after_sec` 拼分钟（ceil/60，≥1，无 data 回退服务端文案）。**待真机**：注册页开关切换、改地址、锁定文案。CLIENT_PARITY 未更新（本轮不动 IMServer 仓）。
- **后台「系统设置」S2 客户端（2026-11-08，未提交，未装机）**：`ServerConfig` 加六个可空字段，归一 `data/ServerConfigStore.kt` 的 `ServerLimits.of`（缺失→默认 120s/不限/9/100/30/500，时间窗 0=不限，合并转发封顶 100）；进程级 `ServerConfigStore`（object）在进主界面 / 每次 WS 连上 / 回前台重拉（`sdk/IMClientServerConfig.kt`，成功后 5s 节流（同 iOS）、失败保留旧值且不计节流，退出/被踢 `forget`；「核对代次→写状态→写 lastSuccessAt」与 forget 同锁，在途标志按代记），顺手喂 `PowerSavingStore.setFcmEnabled`。接入：长按菜单撤回 / 编辑窗口（`MessageActions.availableFor` 新参数）、转发目标上限（`ForwardPickerScreen` / `SharePickBottomBar`）、合并转发条数（`forwardMerged` 超限吐司 `forward_merge_max`，多选本身仍 100）、群名 / 简介 / 公告输入限长（三者一律按码点计，`data/GroupTextLimits.kt`）。`IMTextPrompt` 改为「变短永远放行」（上限调低后旧长名能删字）+「超限且改过禁用确认」（判据 `ui/components/TextPromptRules.kt`）。单测 `ServerConfigStoreTest`、`TextPromptRulesTest`、`GroupTextLimitsTest`。
- **退出后不再收离线通知（2026-11-06，未提交）**：`sdk/session/PendingLogoutQueue` 持久化待吊销凭据并重试，`FcmNotifications.clearForSessionEnd` 清已显示通知。设计与真机清单：`../IMServer/docs/design/LOGOUT_REVOKE_RETRY_DESIGN.md`。**待真机验证**。
- **分享选择页：好友/群聊入口 + 底部已选栏 + 发送确认（2026-11-05，编译 + 单测绿，未装机目测）**：规格 `../IMServer/docs/design/sketches/SHARE_PICKER_UX_SKETCH.html`。共用页仍是 `ui/screens/ForwardPickerScreen.kt`，新参数 `sources`（传了才出入口行/底栏/三源搜索，「添加例外」不传，行为不变）/`preview`/`exclude`；选择模型 `data/SharePick.kt`（按 convId 共用一份已选、合计 9、好友 convId 走 `data/ConvIds.kt`，`IMClient.conversationStubFor` 也改用它），单测 `SharePickTest`；数据 `ui/SharePickSourcesLoader.kt`（离线快照→网络）；子页 `SharePickSubPage`、底栏 `SharePickBottomBar`、确认面板 `SharePickConfirmSheet`（ModalBottomSheet）；建群资料页成员条抽成 `ui/components/SelectedAvatarStrip.kt`（`SelectedAvatarChip` 一份实现，建群条 / 分享底栏 / 确认面板网格共用）；好友/群拉取失败且无快照 → 子页「加载失败，点击重试」。`Forward.toggleTarget`/`targetsInOrder` 已删，勾选判据只在 `SharePickSelection`。二维码页「分享」先选「发送给朋友 / 其他应用…」（`ui/QrShareToFriends.kt`）：名片码走 `ShareMyCardHost`，群码/群链接发「邀请你加入群聊「X」\n链接」文本。
- 近期功能（邮箱登录、两步验证、清除所有对话、删除会话统一口径、导出我的数据、自动删除 v2、会话列表编辑模式、注销账号、置顶面板、聊天文件夹、失效媒体口径等）均已落地并推送，详情见 `current_task.archive.md` 末段。
- 真机目测欠账：邮箱登录、导出我的数据、自动删除 v2、置顶面板、气泡尾角/会话列表待审行/转文字文案、好友成立系统消息/收藏页/发名片确认（均未装机或未目测）。原文见 archive。

## 下一步

00. **分享选择页真机目测**（浅/深色）：转发单条/多条/合并、收藏转发、归档转发、资料页推荐名片、我→分享我的名片、我的二维码/群二维码/群链接「分享 → 发送给朋友」；子页返回保留已选、选满 9 吐司、底栏 × 与确认面板 × 同步、发给没聊过的好友后会话列表出现（靠 ack 的 `bumpConversation` 造行，非发送瞬间乐观插行）。子页切换无转场动画（同卡片内直接换内容）。

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
- **退出吊销重试**：待吊销凭据存在 `SessionStore` 的私有 SP（`allowBackup=false`，未额外加密，与在用凭据同级）；重试依赖 App 被再次打开；队列随卸载删除。全部限制见 `LOGOUT_REVOKE_RETRY_DESIGN.md` §4。
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
