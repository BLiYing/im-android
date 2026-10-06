# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档；每次大瘦身前的全量快照在其顶部，最新一份是 2026-10-03）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点
**聊天选图器收尾（2026-10-07，已合入 main）**：`:media-picker` 被拒改同页空状态（无降级）、原图流式上传（≥8MB 私有副本可续传，杀进程后实测续传成功）、封面单次探测、压缩 ≤2 并发；今日在 OPPO 实测另修两处：①发送方视频被省电模式（「始终开启」）画成磨砂——磨砂只给接收侧；②竖拍视频待发气泡先画成横矩形再变竖——`PendingMediaBubble` 写死横向尺寸，现落行前用 `VideoProbe.info` / 图片 bounds 预探宽高写进待发行，待发与正式气泡共用 `rememberMediaDisplaySize`。待验证见根目录 `current_task.md` 下一步 0。

## 下一步

1. **离线积压剩余**：「@我的消息列表」入口（服务端接口已有，三端 UI 都缺，产品暂不要）。
2. **转场没接的几处**（`docs/UI_PARITY_IOS.md` §4）：`ChatDetailHost` 已全部接完，`GroupInfoHost` 仅 `Media` 一支已接。
   - 根因：`ui/components/PushTransition.kt` 要退场页按冻结的 `state` 渲染，而这些 host 是「关闭即把数据变量置空」，退场时数据已没，半路变白。改法 = 「是否打开」与「显示什么数据」拆两个变量，关闭只翻布尔（参照 `ChatDetailHost` 的 `viewingOpen`/`viewingData`）。
   - 余 8 支未动（Pick/Bans/Admins/JoinRequests/MemberProfile/MemberSearch/Manage/Qr），逐支来；**全部改完才把 9 支统一进一个 `PushTransition(page, depthOf)`**。`GroupInfoHost.kt` 现 596/600（已拆出 `GroupInfoDialogs/Live/Settings/Placeholder.kt`，贴线），**动它之前先拆文件**。
   - `ChatHost` 覆盖层栈（`ChatOverlays.kt`，`ChatRecord` 可嵌套压栈）范围最大且是产品判断，**动手前先问用户**。
3. 卡片弹层推广：@提及（`MentionPanel`）、选文件、日期跳转（现为 `AlertDialog`）、选联系人发名片仍是整屏/底部面板，逐个换 `IMCardSheet`（已读详情已换）。
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

- **体量贴线**（硬闸 600，WARN 线 480）：`GroupInfoHost.kt` 596、`ChatHost.kt` 589（**只差 11 行**）、`MessageRepository.kt` 580、`ChatScreen.kt` 532、`MessageService.kt` 506 都很近；动它们之前先拆文件，新增一律放新文件。`Daos.kt` 别再加。
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
