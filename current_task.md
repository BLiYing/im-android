# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **群聊信息页 / 群管理页与 iOS 对齐，共四批（2026-09-22；纯客户端为主、第四批服务端零改动；`./scripts/test.sh` 852 例绿；未真机）**：
> 用户报告「群资料/群管理与 iOS 差得多」四点，先审计（全文对比 iOS `IMChatDetailViewController`/`IMGroupManageViewController`
> 与本端 `GroupInfoScreen`/`GroupInfoHost`/`GroupManageScreen` 等全部相关文件，结论与仍开着的口子见
> [`docs/UI_PARITY_IOS.md`](docs/UI_PARITY_IOS.md) §2/§3 新增行）。
> **第一批**（三项真 bug/体验落差）：① 单聊「备注名」行此前先跳整页 `UserProfileHost` 才能编辑，现改页内弹窗直接编辑
> （`RemarkEditDialog`，与用户资料页共用）；② 群成员长按（禁言/解除禁言/设撤管理员/转让群主/移出群聊）**执行后成员
> 列表不刷新**（角色徽标、🔇 标记、被移出的人都要退出重进才更新）——对齐 iOS 每个动作后都重拉一次，`GroupInfoHost.runManage`
> 现在统一重拉成员首页；顺带修了禁言徽标判据（`!= 0L` → `GroupPermissions.isMuteActive`，过期时间戳误判为禁言中）；
> ③ 群管理页群名称/简介/公告三行现在右侧直接预览当前值。
> **第二批**（用户从审计清单里选了这三项，跳过群二维码/邀请链接）：④ 群资料页新增 Settings 卡——置顶聊天/消息免打扰
> （复用会话设置接口，新增 `ConversationsApi.settings` 对称 GET）、我在本群的昵称（接回此前的孤儿 API
> `GroupApi.setMyNickname`）、群备注（新增 `ConversationsApi.setRemark`，`PUT /conversations/{id}/remark`——后端早有、
> 本端一直没调），状态持有者拆进 `GroupInfoSettings.kt`（贴 600 行硬闸，拆法见 CODING_STYLE §7②）；
> ⑤ 群公告/群简介从合并卡片拆成两个独立行，各自非空才显示、摘要 3 行、点开弹 `GroupTextViewDialog` 看全文。
> **第三批**（用户随后要求补齐前两批留下的两个口子，2026-09-22 同日；`test.sh` 845 例绿；未真机）：
> ⑥ 群简介/群公告编辑框的 `multiline` 参数此前是死代码——`IMTextPrompt` 从未把它接到底层 `IMTextField`，
> 200/500 字的编辑框实际一直是单行框；`IMTextField` 补 `singleLine` 开关（多行 3~8 行）修复，同时给
> `IMTextPrompt` 加 `clearActionText`，群公告用它对齐 iOS 的独立「撤下公告」按钮（此前只能靠清空文本框）。
> 字数上限/计数器本就已对齐（30/200/500），容器仍保留弹窗、不做 iOS 那种全屏专属页（功能已对齐，体量不值当）。
> ⑦ **群二维码/群邀请链接入口补齐**：`QrApi` 新增 `groupQR`/`resetGroupQR`（对接现成的
> `GET/POST /api/v1/groups/{id}/qr[/reset]`），`QrCardScreen` 从「只服务个人名片码」泛化为名片码/群码共用
> （`title`/`subtitle`/`hint`/`onReset` 全参数化），新增 `GroupQrCardHost` 复用个人码那套亮度提升/存相册/
> 分享/复制链接/重置二次确认；群资料页 Settings 卡新增两行，门控用 `GroupPermissions.canInvite`（与
> 「邀请好友入群」卡片同一份判据，对齐 iOS `inviteEntriesVisible`）。**仅接「出示」这一半**——扫码识别/
> 点击邀请链接后的接收方解析加群流程（iOS `IMQRResultRouter`/`IMGroupJoinPreviewViewController`）需要相机
> 权限 + App Links 深链接入，工作量显著更大，明确未接，留作下一批。
> 为不撞 `GroupInfoHost.kt` 600 行硬闸，顺手把 `loadMore` 挪到 `GroupMembersPaging.kt`、语音页签发送者名
> 逻辑挪到 `data/SenderNames.kt` 的 `groupVoiceSenderNameOf`（594/600，留了 6 行余量，下次加东西前建议先规划
> 再拆一块，比如把治理三页 Pick/Bans/Admins 收进 `GroupGovernanceHost.kt`）。
> `/code-review` 复查无 correctness 级问题，按其建议补了 `GroupInfoNavTest`/`SenderNamesTest` 两条用例
> （新增测试先红后绿验证过）、修正 `groupVoiceSenderNameOf` 的 `myUid` 类型（`String?` 保持与被替换的内联
> lambda 逐字等价）、统一了群码副标题文案（"位成员"→"人"，与详情页头部一致）。
> **已知差异（低优先级，未处理）**：iOS 点击「群二维码/群邀请链接」行时会二次判权限、不满足直接吐司拦截，
> 本端只有行级门控——权限过期的边界情况会先进页面再看到服务端 403 报错，不算 bug（服务端仍是唯一权威闸门）。
> 详见 `UI_PARITY_IOS.md`。
>
> **第四批：扫码/点链接加群——接收方这一半补齐（2026-09-22 同日；纯客户端、`./scripts/test.sh` 852 例绿，
> 新测试均先看红过；未提交、未真机）**：服务端 QRCODE P0/G3 早就全量落地（`/qr/resolve`、`POST /groups/join`
> 等接口 2026-08-13 起就在），此前只有 Android 客户端没接扫码/点链接这一侧。全文核对 iOS 真正生效路径
> （`IMQRResultRouter.m`/`IMQRScannerViewController.m`/`IMGroupJoinPreviewViewController.m`/`IMQRModels.m`）后发现
> **iOS 也没有 OS 级深链接（App Links）**——它只在 App 内已经打开的链接（聊天气泡、群资料/收藏里点开的链接）
> 里拦截本站邀请链接，不是靠系统把外部浏览器打开的链接拉起 App；范围据此收敛，不用碰 AndroidManifest 的
> deep link 配置。落地：① `QrApi.resolve()` 手动解 `{kind,data}`（新增 `QrUserCard`/`QrGroupCard`/`QrResolved`
> 密封类），`GroupApi.join(token, hello)` 接 `POST /groups/join`；② `data/QrActions.kt` 纯映射函数（relation/
> joinable/reason → 按钮态，对齐 iOS `IMQRModels.m`/Web `qr.ts` 同一张判据表，全部单测覆盖）；③ `data/WebLinks.kt`
> 新增 `isOwnInviteLink`（host+端口匹配 + dev 回环例外 + 路径命中 `/q/u|g/`，对齐 iOS `routeInviteLinkIfOwn:`）；
> ④ 新增 `ui/QrRouteHost.kt`：挂在 `MainScreen` 内部（不是更外层的 `AppRoot`）——它要改 `openConv` 才能进群聊/
> 单聊，那份状态是 `MainScreen` 的私有变量。**覆盖**外层 `WebLinkHost` 提供的 `LocalOpenLink`：点一条链接先判
> 是不是本站邀请链接，是就 `resolve`+路由，不是就退回原来那份（真正的浏览器打开）；另提供新 CompositionLocal
> `LocalOpenQrScan` 给会话列表 ＋ 菜单的「扫一扫」用（此前是 `toast = "扫一扫还没做"` 的占位）；⑤ `ui/QrScanHost.kt`：
> CameraX 出帧 + zxing-core（`QRCodeReader`，出示码那半已引入的同一个依赖，未叠 ML Kit）解码，只吃 Y 平面、
> 手动处理 rowStride/pixelStride 避免部分机型图像被拉斜；新增 `CAMERA` 权限 + `uses-feature required=false`；
> ⑥ `ui/GroupJoinPreviewHost.kt`+`screens/GroupJoinPreviewScreen.kt`：对齐 `IMGroupJoinPreviewViewController`
> （头像/人数/邀请人/简介 + 需审批时附言框 + 按准入态变文案/可用态的主按钮）；提交回调**先把预览页从组合里摘掉、
> 再异步发 join 请求**（对齐 iOS `submitTapped` 的 pop 在前、回调在后——本页自己的协程作用域会在摘掉那一刻被
> 取消，逻辑放在 `QrRouteHost` 持有的外层 scope 里）。**仍未接**：相册选图识码、一图多码候选、扫码登录（QR P1，
> 命中即提示不支持）。`/code-review` 复查抓到一条高严重度：关闭扫码页后 CameraX 从未 `unbindAll()`
> （单 Activity 架构下 `bindToLifecycle` 挂的是 Activity 级生命周期，Compose 把页面摘出树不会自动解绑）——
> 相机占用指示灯不灭、持续耗电，已修（`DisposableEffect` 里补 `provider?.unbindAll()`）；顺带修了一条中等：
> 从「去设置开启」跳系统设置页回来后权限状态不会自动刷新，加了 `LifecycleEventObserver` 在 `ON_RESUME`
> 重查一次。测试补了两个纯文案函数的遗漏覆盖。`test.sh` **854 例绿**。

> **im-rtc 通话接入（2026-09-19，联调期；单聊 1v1 + 群通话；单测绿、未提交、未真机）**：
> SDK 走本机 Maven（先在 `../im-rtc/im-rtc-android` 跑 `./gradlew publishToMavenLocal`，`settings.gradle.kts` 只对该 group 开 `mavenLocal()`），
> 版本坐标在 `libs.versions.toml` 的 `imrtc`。**票用调试密钥在本机签**（`rtc/RtcCall.signToken` 是票的唯一来源，以后换后台接口只改这里）。
> 配置写 `local.properties`（已忽略）：`rtc.wsUrl` / `rtc.appId` / `rtc.keyId` / `rtc.debugSecret`，缺项则入口提示、其余功能不受影响。
> 生命周期：`AppRoot` 进主界面 `RtcCall.start`、回登录页 `stop`（Restoring 不动，Activity 重建不挂在途通话；同账号重复 start 是空操作）。
> 入口：单聊详情页语音 / 视频 pill；群资料页新增语音 / 视频 pill → 选成员（`PickPurpose.Call`，最多 8 人）→ `placeGroup`。附件面板「音视频」不接（后面会去掉）。
> ⚠️ 服务端地址联调时是 Mac 的局域网 IP（`ws://<Mac IP>:8787/v1/ws`），换网络要改 `rtc.wsUrl` 重新打包；名字与头像由 `rtc/RtcProfileResolver` 注入（按 uid 取名片：备注 → 昵称 → @句柄，未缓存时先显示 uid、取到再重画；单测绿、未真机）。
> 未做：设置页「后台接口 / 调试」开关（等 IMServer 换票接口）、IMServer 侧换票、群成员超一页时选人页只列已加载的。

> **收藏页 + 长按「收藏」+ 详情页下载示意复用聊天页组件 + 从收藏发送（2026-09-17 第二、三批；纯客户端、后端零改动；
> `./scripts/test.sh` 807 例绿，新测试均先看红过；⚠️ 用户要求**不装真机**，布局/手势未实测；未提交）**。
> 逐条状态见 `../IMServer/docs/CLIENT_PARITY.md` 顶部「2026-09-17 第二批 / 第三批」，SYMMETRY 新登记 3 行。
>
> ① **详情页宫格 / 文件行的门控外观 = 聊天页那几个组件**：宫格 `AlbumTileGate` + `TileDurationChip` + `VideoPlayBadge`
>    （与聊天页相册格同一对），文件行图标位 `FileGateSlot(side = 36dp)`（与文件气泡同一个），副行 `DownloadLabels.archiveFileLine`
>    （照 iOS `IMDetailFileCell`）。旧 `DownloadBadge` 与 `fileHint()` 已删。放行判据 `DownloadPolicy.archiveTileUngated`：
>    自己发的 / 图片且策略放行 → 直接显示，**失效不豁免**；门控格点一下 = 下载，不打开（iOS 铁律①）。
>    ⚠️ 这条**更正**了上一批「iOS 宫格直接按 URL 加载」的说法——iOS 是按策略放行，出厂默认图片恒自动所以看着像直出。
> ② **收藏列表页**（「我 ▸ 收藏消息」= `ui/FavoritesHost.kt` + `ui/screens/FavoritesScreen.kt`，判据 `data/Favorites.kt`）：
>    七签只列存在者；媒体/文件/语音/链接**直接复用 `ArchiveRows.kt` 的行**（收藏 → `ConvMediaItem`，key = 收藏 id，
>    下载态按 URL 与聊天页共享——iOS「合成 `IMMessageModel` 喂共用编排器」的对应物）；名片/聊天记录复用 `CardBubbles.kt` 卡片内容。
>    签内搜索、分页、来自X、长按 转发/复制/取消下载/删除、点开查看器/文件/浏览器/记录页/资料页/全文阅读页。
> ③ **长按菜单「收藏」**（`MessageAction.Favorite`，判据 = 多选栏的 `SelectionActions.favoritable`），执行挂宿主 scope。
> ④ **附件面板「从收藏发送」**（第三批）= `FavoritesHost(onPicked = …)` 的选择模式，由 `ChatPickerLayers` 盖在聊天页上
>    （返回键层 `ChatOverlays.Layer.FavoritePicker`）。判据 `data/FavoritePick.kt`（上限 9、取消永远允许、按列表顺序发）；
>    勾选框 `PickCheckButton`（行尾槽 `trailing` / 宫格 `picked`，复用多选圈 `SelectionCheck`），点行/点格仍是打开；
>    发送 `ForwardSend.kt` 的 `sendFavoritesTo`（同一个 `MessageService.forward`，失效媒体跳过并写进回执）。
>    ⚠️ 收藏发出去前 `FavoritesHost.toMessages` 先补齐来源名（好友表 / 名片），解析不出写「未命名用户」——
>    此前名字没回来就发送会把对方内部 uid 写进「转发自」（复查抓出，长按转发同一条路）。

## 下一步

0. **本批真机回归**（没装机）：详情页宫格门控层/点门控格是下载、文件行 36dp 图标位与进度环、收藏页七签与长按菜单、
   名片进资料页「发消息」、收藏转发到会话后收端封面/尺寸、长按「收藏」后收藏页出现；
   **从收藏发送**：勾选框点击不连带打开、宫格右上角勾选圈在浅色图上看得清、底栏不被导航栏压住、
   发出后聊天页回到最新、返回键先关选择页内的查看器/资料页再关选择页。
0b. **上几批仍欠的真机回归**（这两轮没动到、也没回归）：归档查看器「更多」五项、合并转发记录页内翻页、
   长按预览里点图/点链接只关菜单、以及 2026-09-15/16 那两批的清单（见 `current_task.archive.md` 顶部）。
1. **语音播放整端缺失**：聊天页气泡与归档语音行都只画波形、点不响（全 App 没有播放器，也没有录音）。
   iOS 那行的 ▶ 与波形都能就地播（`IMVoicePlayer sharedPlayer`）。这是本端与 iOS 最后一处**能力差**。
2. **Android 离线积压整套未启动**（`../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §4.11.1 / §5 B3a）：
   建议先让 sync 带 `max_gap`；另缺区间清单、`conv_bump` 被丢弃、sync/window 路径不回 `delivered`；
   C4 未做；会话内检索只取一页。
3. **转场没接的几处**（`docs/UI_PARITY_IOS.md` §4）：群资料 / 聊天信息内部子页读的是已置空的状态。
4. **卡片弹层推广**：@提及、选文件、已读详情、日期跳转、选联系人发名片仍是整屏/底部面板，逐个换 `IMCardSheet`。
5. **收藏的剩余项**：「以聊天模式查看」（按来源会话分组下钻）、来源名到群昵称级（现只到好友备注/昵称/补拉名片）；**长按菜单缺项**：举报、翻译。
6. **宫格**按 `IMAlbumRowPattern` 重写布局 + 五道防跳版闸；相册宫格逐格勾选。
7. 按 `docs/UI_PARITY_IOS.md` 剩下的 🔴：会话内搜索的📅/👤过滤、水滴头部形变、语音页签内播放、「名片」页签、隐私页无障碍。
8. 按 `CLIENT_PARITY` 追 iOS：消息编辑（M4-5）→ 设置页其余 6 项 → 扫码读码半边 + 头像裁切页 → 推送（M5）。
9. **群成员头像图**：首字母色块对，但无头像缓存；要先做 `POST /users/batch` 解析器。

## 已知坑 / 限制

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
- **转发必须带 `poster`/`media_w`/`media_h`/`duration`/`thumb`/`waveform`**（判据 `Forward.attributesOf`，
  SYMMETRY 已登记）：漏带全程静默，只有收件人看得出来，且事后补不回来。**两个入口**（聊天页长按/多选、
  详情页归档查看器）都要走到，**待发行也要写**（resend 从它读）。
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
- **instrumented 测试只有 `TouchShieldTest` 一个，test.sh 不跑它**（要真机）。别用 `connectedAndroidTest`
  （跑完会卸载 App、丢登录态），用：`assembleDebug assembleDebugAndroidTest` → 两个 APK 各 `adb install -r` →
  `adb shell am instrument -w -e class com.libeyond.imandroid.ui.components.TouchShieldTest com.libeyond.imandroid.test/androidx.test.runner.AndroidJUnitRunner`。
  其余布局/滚动/手势/输入法仍靠真机截图核对
  （`adb exec-out screencap`；用 `uiautomator dump` 找控件坐标比按像素猜可靠）。
- **视频不转码**、**分片上传不跨进程续传**、**视频没有本地缓存**、**图片不压缩**（只挡 20MB）、**无断点续传**。
- **查看器翻页只能往更旧续拉**（服务端媒体接口是 `conv_seq < cursor` 倒序分页），本地一次最多取 300 条。
- **六个贴线文件已全部拆开**：`GroupInfoHost.kt` 547、`ChatScreen.kt` 520、`MessageRepository.kt` 519、
  `ChatHost.kt` 521、`MessageService.kt` 484、`DetailArchive.kt` 265（上限 600，**WARN 线 480**）。
  别把新东西再往这几个里加。
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
