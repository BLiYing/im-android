# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **七条用户报告 + 详情页各页签对齐 iOS ✅（2026-09-17；纯客户端、后端零改动；`./scripts/test.sh` 781 例绿，
> 逐条真机验过）**。⚠️ **有四条的根因与报告时的猜测不同**，按实测改的：
>
> ① **「媒体库/详情页宫格看不到图」**——真因不是滚动：宫格未就绪时喂给 Coil 的是 `gate.model`（门控未
>    就绪即 `null`），于是整页只剩磨砂底 + 一排 ↓，看着像页面死了。iOS 两处宫格
>    （`IMConversationMediaViewController` / `IMDetailMediaContainerCell` 里的 `IMMediaTileCell`）
>    **都是直接按 URL 加载缩略，门控只作盖在上面的状态层**（`autoPrefetchEnabled = NO` 管的是
>    "不自动整包预取原件"，不是"不显示这张图"）。已改成按地址直出；**聊天气泡那侧仍守门控**。
>    列数 **4 → 3**（原注释声称"与 iOS 同为 4"是错的），媒体库标题改 **「图片与视频」**（逐字取 iOS）。
> ② **「详情页划不动 / 有时能划」**——实测**能滚**（21 条媒体那个群滚动正常），"划不动"是①造成的空页错觉；
>    只有 1 条媒体时更是本来就没得滚。顺手删掉 `AlbumBubble` 里一处没有任何读取方的每帧状态写。
> ③ **「转发的图片消息看不到」**——转发本身正常（服务端落库带 `media_w/h`/`thumb`/`file_size`/`forward_from`）。
>    真因是**自己转发出去的图在自己这一侧被门控挡成空盒**：转发透传的是原图 URL，本机并没有那些字节。
>    门控是挡"别人发来的、我还没决定要不要下"的，对自己发出去的内容没有意义 → **`mine` 一律不门控**
>    （单图 `MediaContent.mine` + 宫格 `AlbumTileView`）。
> ④ **「复制图片后长按输入框没反应」**——**两个根因**：(a) `ClipData.newUri` 只声明图片 MIME、**不含
>    `text/plain`**，而 Compose `BasicTextField` 的"能不能粘贴"走 `ClipboardManager.hasText()` → 菜单里
>    根本不出现「粘贴」；(b) 更要命的是**复制协程挂在长按菜单自己的 `rememberCoroutineScope()` 上**，
>    菜单一关就取消，真机日志是 `image_copy_failed {err=LeftCompositionCancellationException}`
>    ——图片压根没进剪贴板。已改：剪贴项同时带 URI + 纯文本两种表示；`ChatMessageMenu` / `ArchiveActionsHost`
>    改由**宿主传 scope**（同 `ArchiveViewer.kt` 文件头那条 ⚠️），顺带救了「仅删除自己」同一条线。
> ⑤ **「视频下完点播放只有声音没画面」**——`AndroidView` 没有 `update` 块：`player` 是 `remember(absolute)` 的，
>    下完切本地那一刻换了实例，而 `PlayerView` 还挂着**已被 onDispose release 的旧 player**（没有输出 surface
>    → 有声无画，退出重进才好）。补 `update = { it.player = player }`。
> ⑥ **查看器**：顶部改**两行**（会话名 17/semibold + `i / N` 13/次要灰），逐条对齐 iOS
>    `IMMediaPagerViewController` 的 `IMLiquidNavigationBar`；图片补**磨砂占位**（原图现拉那几秒原先是纯黑，
>    像图没了，iOS 是 `showThumbPlaceholder`）。**翻页与远端加载实测本来就正常**，未下载的图也能看。
> ⑦ **各页签对齐 `IMChatDetailViewController`**：语音行改三行（发送者名 / **真波形**（复用聊天页
>    `VoiceContent`，同 iOS 复用 `IMVoiceMiniPlayerView`）/ 完整年月日时分）；文件行、链接行同改三行；
>    时间一律改 `TimeFormat.fileDateTime`（同 iOS `IMFormatFileDateTime`）；空态文案逐字改成
>    「暂无媒体 / 暂无文件 / 暂无语音 / 暂无链接」。**波形与发送者名服务端归档接口都不回带**，
>    由本地消息表按 `conv_seq` / `uid` 兜底（`rememberVoiceWaveforms` / `rememberLocalSenderNames`）——
>    顺带**修掉了「从语音页签转发出去的语音丢波形」那条老限制**。
>
> **真机逐条验过**（user1002 真机 ↔ 服务端库核对）：宫格 3 列真图 / 语音行波形+1:34+完整时间 /
> 查看器两行标题 + 21→19 翻页 / 转发落库字段齐 + 自己这侧可见 / 复制→粘贴出现粘贴条与缩略图 /
> 原视频下完胶囊消失且播放有画面（00:09 在走）。

## 下一步

0. **本批仍欠的真机回归**（这轮没动到、也没回归）：归档查看器「更多」五项、合并转发记录页内翻页、
   长按预览里点图/点链接只关菜单、以及 2026-09-15/16 那两批的清单（见 `current_task.archive.md` 顶部）。
1. **语音播放整端缺失**：聊天页气泡与归档语音行都只画波形、点不响（全 App 没有播放器，也没有录音）。
   iOS 那行的 ▶ 与波形都能就地播（`IMVoicePlayer sharedPlayer`）。这是本端与 iOS 最后一处**能力差**。
2. **Android 离线积压整套未启动**（`../IMServer/docs/design/OFFLINE_BACKLOG_DESIGN.md` §4.11.1 / §5 B3a）：
   建议先让 sync 带 `max_gap`；另缺区间清单、`conv_bump` 被丢弃、sync/window 路径不回 `delivered`；
   C4 未做；会话内检索只取一页。
3. **转场没接的几处**（`docs/UI_PARITY_IOS.md` §4）：群资料 / 聊天信息内部子页读的是已置空的状态。
4. **卡片弹层推广**：@提及、选文件、已读详情、日期跳转、选联系人发名片仍是整屏/底部面板，逐个换 `IMCardSheet`。
5. **收藏的另一半**（列表页/删除/长按菜单「收藏」/附件面板「从收藏发送」）；**长按菜单缺项**（举报/收藏/翻译）。
6. **宫格**按 `IMAlbumRowPattern` 重写布局 + 五道防跳版闸；相册宫格逐格勾选。
7. 按 `docs/UI_PARITY_IOS.md` 剩下的 🔴：会话内搜索的📅/👤过滤、水滴头部形变、语音页签内播放、「名片」页签、隐私页无障碍。
8. 按 `CLIENT_PARITY` 追 iOS：消息编辑（M4-5）→ 设置页其余 6 项 → 扫码读码半边 + 头像裁切页 → 推送（M5）。
9. **群成员头像图**：首字母色块对，但无头像缓存；要先做 `POST /users/batch` 解析器。

## 已知坑 / 限制

- **浮层里的协程必须由宿主传 `scope`**（2026-09-17 真机抓到）：长按菜单/归档菜单点完就 `onDismiss()`，
  `rememberCoroutineScope()` 绑的是那一层，挂上去的活会**当场被取消**且只在日志里留一句
  `LeftCompositionCancellationException`。已收口的有 `ChatMessageMenu`、`ArchiveActionsHost`、
  `ArchiveViewer.kt` 整族；`ChatViewerLayer` 恒在组合里，自建是安全的。
- **注释里别写「斜杠 + 星号」的通配 MIME 字面量**：Kotlin 块注释**可嵌套**，它会当场开一层内层注释，
  本段的结束符只关掉里层、外层一路吞到文件尾，而编译器报的是「Missing '}'」（2026-09-17 栽过一次）。
- **门控的边界**：聊天气泡（别人发的）守门控；**归档宫格/媒体库按地址直出**；**自己发的（`mine`）一律不门控**。
  改任一处都要回头看另两处，否则又会出现"某个入口看不到图"。
- **归档里语音仍不能播**（见「下一步 1」）；**波形已能显示**（本地兜底），但**只覆盖本地已加载的那一段**，
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
- **没有 instrumented 测试**：布局/滚动/手势/输入法全靠真机截图核对
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
