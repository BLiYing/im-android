# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档；2026-10-02 瘦身前的全量快照在其顶部）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

无进行中的开发任务。最近收口（均已真机验证并推送，细节见 archive 顶部与 `git log`）：
2026-10-02 会话列表返回保位置 / 陌生人会话壳不露 convId / 我页资料断网兜底；
2026-10-01 通知显示发送人头像、别端已读清手机通知/角标、多选删除两档改批量接口。

## 下一步

1. **真机验证欠账**（均已实现，需两台设备/两个账号才测得全；验完再合并对应分支）：
   - 通知与提示音 P1 第一批 `feature/notif-p1a`（横幅出现/点击/上滑/自动收/按住暂停/连发替换/进会话收起/预览关文案/通话中不出，「添加例外」另一端同步）与第二批 `feature/notif-p1b`（定时免打扰：三个入口时长菜单、到期后铃铛/角标/例外列表自动刷新、另一端 `conv_update` 显示「永久」、免打扰期间改置顶/标未读/群备注 `mute_until` 不变）——**两批建议一起验完再合并 main**。
   - 设置 ▸ 最近通话剩余项：滚动分页时序、「未接」tab 连续翻页观感、`callEnded` 重拉首页、群聊行跳转、空/401/网络错误三态——需攒几通真实通话（含群通话）。
   - 群资料页「成员」tab 搜索：>50 人入口门槛、搜索/去抖/翻页、选中跳资料页——需 50+ 人的测试群。
   - 归档查看器「更多」五项、合并转发记录页内翻页、长按预览里点图/点链接只关菜单；转发带 @ 图片到群后「别人视角」点被 @ 的名字与强提醒；「对方撤回」两种文案（单设备单账号测不出）。
2. **离线积压**（`../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §4.11.1 / §5 B3a；**别照抄 Web/iOS 补丁**，按 §4 重走）：
   - **已做**：**C4a ✅ 2026-10-02**（`conv_bump` 记 head+刷列表行；实时/ack 紧接游标则同事务推进游标；`GapRule` 跳号只发一次单会话 sync_req；**C4b UI 层未做**：↓ 判据/bump 贴底才补/↓N，先拆 `ChatHost.kt`）；**C5 ✅ 2026-10-02**（`ReceiptBatcher` 120ms 合批，实时与 sync 同走；window 不回）；**C1 ✅ 2026-10-02**（`conv_range_local` 表 + `SyncRanges`/`ConvRanges` + `persistPage` 同事务写四条路径 + 清空联动，只写不读；Room 14→15 回填 `[1,synced]`；**C3 硬前置**：进会话「清单说齐、手里没东西 → 问服务端」兜底，且只在 `head > max(服务端下界, clearedUpTo)` 时才问）；**清空位点 `clearedUpTo` ✅**（Room 15→16；清空后重进不会拉回，见设计 §6.7；C6 服务端搜索/媒体/日历结果要滤 `≤ clearedUpTo`）；**C2 ✅ 2026-10-02**（超级群 `max_gap=0`、`too_long`/`head` 落 `headConvSeq`、`isSuper` 列、Room 13→14，未提交）；**压测埋点**（`sdk/logging/PerfMarks.kt` + `apply_ms`，未提交）；**B0 基线已跑**（`../IMServer/docs/ops/LOAD_TESTING.md` §11）：进 10 万积压会话**聊天页空白、永不收敛**（只读本地尾窗，本地为空又不发 `window_req`）。
   - **顺序（每块一次一个）**：~~C2 收尾~~ ✅ → ~~C1~~ ✅ → ~~C5~~ ✅ → **C4b**（↓ 判据 = 区间覆盖 `[tip-page+1, tip]`，下沿 `tip-page+1`；bump 贴底才补，翻历史只让 ↓N 按 head 计数；C4a 已做）→ **C3**（进会话锚点开窗：`readSeq=0` 夹到 1、有无未读只认服务端 unread；上滚 本地段内取→下界闸→`window_req`；应答时现算边界）→ 复压 → **C6**（先搜索翻页/日历/跳最早/媒体离线提示，再发件人候选/置顶/↓N）。
   - **体量**：`ChatHost.kt` 599、`GroupInfoHost.kt` 589、`MessageService.kt` 574 贴 600 闸，C1/C3 新增一律放新文件；动 `ChatHost` 前先拆。`Daos.kt` 别再加。
   - **验收**：纯函数 JVM 单测 + 变异；DAO/@Query/迁移走 instrumented（`installDebugAndroidTest` + `adb shell am instrument`，别用 `connectedAndroidTest`）；真机 `GMGY7XF6LBJB6PFU` 走大群进会话/↓/上滚。
   - 压测装机：真机连 `127.0.0.1:8099` 要 `adb reverse`；OPPO 禁 `pm clear`/`pm grant`，装包与通知权限各点一次（见 LOAD_TESTING §11.1）。⚠️ 本机已被我卸载重装并登录到 :8099 副本（原 :8080 登录态已清）。
3. **转场没接的几处**（`docs/UI_PARITY_IOS.md` §4）：`ChatDetailHost` 已全部接完，`GroupInfoHost` 仅 `Media` 一支已接。
   - 根因：`ui/components/PushTransition.kt` 要退场页按冻结的 `state` 渲染，而这些 host 是「关闭即把数据变量置空」，退场时数据已没，半路变白。改法 = 「是否打开」与「显示什么数据」拆两个变量，关闭只翻布尔（参照 `ChatDetailHost` 的 `viewingOpen`/`viewingData`）。
   - 余 8 支未动（Pick/Bans/Admins/JoinRequests/MemberProfile/MemberSearch/Manage/Qr），逐支来；**全部改完才把 9 支统一进一个 `PushTransition(page, depthOf)`**。`GroupInfoHost.kt` 已贴 600 行硬闸，**动它之前先拆文件**。
   - `ChatHost` 覆盖层栈（`ChatOverlays.kt`，`ChatRecord` 可嵌套压栈）范围最大且是产品判断，**动手前先问用户**。
4. 卡片弹层推广：@提及、选文件、已读详情、日期跳转、选联系人发名片仍是整屏/底部面板，逐个换 `IMCardSheet`。
5. 收藏剩余：「以聊天模式查看」、来源名到群昵称级；长按菜单缺举报、翻译。
6. 宫格按 `IMAlbumRowPattern` 重写布局 + 五道防跳版闸。
7. `docs/UI_PARITY_IOS.md` 剩余 🔴：水滴头部形变、「名片」页签、隐私页无障碍。
8. 群成员头像图：首字母色块对但无头像缓存，要先做 `POST /users/batch` 解析器。
9. 小尾巴（均不影响行为）：「刷新补名失败不重试」靠下次连接/刷新自愈；`resolveKind` 对畸形 convId 当群聊（测试已钉住）；`VoiceTranscriptStore.putText` 每次重写整份 FIFO 顺序表；`PendingVoiceBubble` 里多余的 `coerceAtLeast(160.dp)`；`applySync` 对全是 `msg_op`/墓碑的一页也调 `bumpConversationFromLatest`（白多一次索引 SELECT）；语音发送成功后 `voice_pending/` 本地文件不清理（要等 ack 才安全删，单独做）。

## 已知坑 / 限制

- **体量贴线**：`GroupInfoHost.kt` 已到 600 硬闸，下次必须先拆；`ChatHost.kt` ~598、`ChatScreen.kt` 557、`MessageService.kt` ~574、`VoiceRecordUi.kt` 512 也很近（WARN 线 480）。
- **合并转发记录里的名片 →「发消息」换不了会话**（未修）：`ChatHost` 绑死单一 `convId`，点了只关资料层退回记录页。
- **`FavoritesHost`/`ChatPickerLayers`/`QrRouteHost` 进 `UserProfileHost` 没接 `onRemarkChanged`**（缓行，按 `ContactsHost` 同一套本机状态覆盖补即可）。
- **App Links 系统级深链接：明确暂不做**（2026-09-22 拍板）：需要固定公网 HTTPS 域名放 `assetlinks.json`，没有域名写了也测不出；三端联动，等有部署域名再评估。**改原生 Material 风格：暂缓**（见 `docs/design/MATERIAL_DESIGN_EVAL.md`），别顺手改配色/图标/弹层。
- **浮层里的协程必须由宿主传 `scope`**：长按菜单/归档菜单点完就 `onDismiss()`，`rememberCoroutineScope()` 绑的是那一层，挂上的活当场被取消，日志里只有一句 `LeftCompositionCancellationException`。
- **注释里别写「斜杠 + 星号」的通配 MIME 字面量**：Kotlin 块注释可嵌套，会吞到文件尾，报「Missing '}'」。
- **门控三处要一起看**：聊天气泡（别人发的）守门控；归档/收藏宫格走 `DownloadPolicy.archiveTileUngated`；自己发的不门控。「已失效」不在豁免之列；宫格放行的图按原图地址加载；门控外观只准复用 `GateOverlays.kt`。
- **转发必须带** `poster`/`media_w`/`media_h`/`duration`/`thumb`/`waveform`/`mentions`/`mentionSpans`（判据 `Forward.attributesOf`，SYMMETRY 已登记），两个入口都要走到，待发行也要写；加一个「随消息走」的新字段要动七处，最后一处 `AckCarryOver.CARRIED` 有反射闸。
- **`ChatScreen.kt` 的滚动时序整组在 `ui/screens/ChatScroll.kt`**，四条 effect 读写同一份 `ChatScrollMarks`，必须待在一起；**宫格绝不能 LazyColumn item 里塞 LazyVerticalGrid**（由外层逐行渲染，`data/MediaGrid.kt`），列数 = 3（改它先改 `../IMServer/docs/UI_SPEC.md`）。
- **粘贴判据三条纪律**（`data/PasteImage.kt`）；**长按菜单原位预览里链接与图片都不可点**（别改成在 `Bubble` 里按 `onLongPress != null` 判）；**`IMCardSheet` 关闭途中也拦返回键**；**覆盖页触摸屏蔽层绝不能 consume**（`TouchShield.kt`）。
- **OkHttp 收到服务端关闭帧不会自己回帧**：不在 `onClosing` 里 `close(1000, null)`，踢下线要等 25s。
- **会话列表离线首登是空白**；改密后的新续期凭据只出现一次（`NonCancellable` + 带发起时 uid）。
- **应用内浏览器**：release 不放行明文 http，debug 整个放开，debug 真机看不出来；明文 HTTP 只对 `10.0.2.2`/`localhost`/`127.0.0.1` 放行。
- **JVM 单测摸不到 Room 生成的真实 SQL**（`@Query` 改动光靠单测不算数，要真机走一遍）；instrumented 测试只有 `TouchShieldTest`，`test.sh` 不跑它，别用 `connectedAndroidTest`（会卸载 App 丢登录态），步骤见 `git log`/archive。
- **`ONLY=X ./scripts/test.sh` 偶有 `:media-picker` "No tests found"**：改用 `./gradlew :app:testDebugUnitTest --tests '*A*'`；JVM 单测里 `android.util.Log` 是桩，先 `IMLog.useSinksForTest()`。
- **视频不转码、分片上传不跨进程续传、视频无本地缓存、图片不压缩（只挡 20MB）、查看器翻页只能往更旧续拉（本地一次最多 300 条）**。
- **本机 `JAVA_HOME` 是坏的**（`test.sh` 已自愈；直接跑 gradle 要自己 `export JAVA_HOME="$(/usr/libexec/java_home -v 17)"`）。

## 关联工程 / 常用命令

- 后端 `../IMServer`；iOS `../IMProgram`；Web `../im-web`。**参考实现**：判据类纯函数可照搬 Web，但守 `../IMServer/docs/SYMMETRY.md`——对称的是不变式，不是代码形状。
- 本仓：`./scripts/test.sh`（唯一测试入口）；`BUILD_ONLY=1 ./scripts/test.sh`（只编译）；`./scripts/install-hooks.sh`（每个 clone 一次）。
- 后端（改了后端代码**必须重启**）：`cd ../IMServer && ./scripts/dev.sh --no-tail`。
- 真机：`GMGY7XF6LBJB6PFU`（OPPO PKD130），装包 `./gradlew :app:installDebug`；连局域网后端（登录页底部可见地址），够不着时 USB + `adb reverse`，步骤见 `../IMServer/docs/ops/DEPLOY.md` §2.E（⚠️ 音视频通话不能用这种方式测，媒体走 UDP）。
- 模拟器：AVD `im_test`（API 36 / pixel_5），连宿主机后端用 `10.0.2.2:8080`。
- 本地测试账号：`user1001` / `user1002` / `e2etest1`，密码 `123456`（user1002 密码被改密测试轮换过，登不上就用登录页「免密登录（开发）」）。
