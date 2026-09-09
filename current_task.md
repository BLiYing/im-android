# Current Task — im-android（Kotlin + Compose 客户端）

> **活快照**：只记当前状态，**就地覆盖、不追加**。逐功能×端状态以
> `../IMServer/docs/CLIENT_PARITY.md` 的 Android 列为唯一来源；历史流水见 `git log`
> 与 `current_task.archive.md`（只读归档）。
> 工程规范见 `CLAUDE.md` 与 `CODING_STYLE.md`。

## 当前焦点

> **通讯录 A–Z 索引尺 + 好友行左滑（`docs/UI_PARITY_IOS.md` ③，两条 🔴）✅ 2026-09-09**
>
> 好友列表原来是一条平铺的名单，2000 人只能一路滑。现在按显示名（备注优先）的拼音首字母
> 分组，右侧一条自绘 A–Z 索引尺（按下/拖动即跳组，跳组给一次 `CLOCK_TICK` 轻触感），
> 好友行左滑露出 `[删除, 拉黑/解除拉黑]`。判据逐条抄 iOS `IMContactSectionIndex`
> 与 `trailingSwipeActionsConfigurationForRowAtIndexPath:`，清单见 `docs/UI_PARITY_IOS.md` §4.5.1。
>
> **首字母刻意不与 iOS 同实现**：iOS 用 `CFStringTransform`，Android 没有等价物
> （`android.icu.text.Transliterator` 要 API 29，且在跑单测的桌面 JVM 上不存在——写了等于判据没法测）。
> 本端用 `Collator(Locale.CHINA)` 比 26 个边界字。**要一致的是「按拼音首字母分组」这条不变式，
> 不是同一段实现**；所以单测钉规则（多音姓氏表、`#` 三种情况、组间/组内排序、空组不显示），
> 「哪个汉字归哪个字母」由真机核对。
>
> **真机（模拟器 im_test / API 36 / user1001 / 2014 个好友）抓出三条，桌面单测一条都测不出来**：
> ① 组头的 LazyColumn key 写成了字面量（`$` 被转义），所有组头共用一个 key，
>   **画到第二组当场崩**（`Key ... was already used`）——LazyColumn 测量期才抛，单测碰不到。
> ② **「阿强」既不在 A 组也不在任何组，直接掉进 `#`**：桌面 JVM 上「阿」≥ 边界字「啊」，
>   Android 的 ICU 上却相反。判据补成「排在第一个边界字之前的汉字仍归 A」。
>   这一档在桌面 JVM 上**走不到**（扫遍 U+4E00–U+9FFF 没有汉字排在「啊」之前），
>   所以比较抽成参数注入（`initialByBoundaries`）——不然那条断言永远绿，正是本仓那条
>   「没红过的断言不算数」。变异验证过：把 `?: "A"` 改回 `?: OTHER` 当场红。
> ③ **2000 人量级会卡到 ANR**：排序键原本写在 comparator 里，`Collator` 于是**每次比较
>   都重建一个 CollationKey**（2014 人 ≈ 4 万多次）。改成先算完键再排 + 首字母按首字缓存，
>   桌面 JVM 实测 98ms → 14ms、取首字母 38ms → 0.9ms。
>
> **真机逐条验过**：A–Z 分组显示、索引尺点跳（A/L 精确落到组头；Z/# 触底夹紧是列表本来的行为）、
> 拖动连续跳组、左滑露出两格且**删除在最外侧**（同 iOS 数组第一个贴边）、
> 拉黑→行显「已拉黑」且服务端 `blocked=true`、解除拉黑、删除**二次确认**后
> 服务端好友数 2014→2013 且 A 组整组消失（空组不显示，实时成立）、2013 行快速滑动不掉帧。
>
> **`/code-review` 打回 8 条，全修了**（§4.5.1 有逐条表）：分割线被 `Box` 叠到行首、
> 「拉黑」格借用半透明令牌导致白字看不见（iOS 那侧是不透明 systemGray）、
> 滑开的行仍可点且多行能同时敞开、`Collator` 单例的线程约束没写、
> 三处裸 `runCatching` 吞 `CancellationException`、`reload()` 空 catch 静默吃掉所有失败、
> 索引尺 `+1` 偏移没被单测钉住、重音拉丁掉进 `#`。
> **顺带删掉一段死代码**：组内排序照抄 iOS 多了一档大小写兜底，变异验证发现删了没测试变红
> ——查证后确认它永远走不到（本端 CollationKey 是 TERTIARY 强度，本来就区分大小写）。
> 这正是「照抄代码形状而不是不变式」的样子。
>
> **欠账**：选好友页没跟（iOS 那侧通讯录与选好友页共用同一套分桶，本端 `CreateGroupScreen`
> 仍是平铺；`ContactSection` 是数据层，复用零成本）；CJK 扩展 B 及以后仍归 `#`；
> 分组仍在组合期主线程算（真机 2013 人不掉帧，挪后台要先解 `Collator` 线程安全）。
>
> **删除加了二次确认，iOS 没有**：删好友不可撤销，而左滑+点一下只有两个手势；
> im-web 与本端资料页的删好友都有确认，三处里两处有，缺的那处更像 iOS 的疏漏。

## 下一步

**0. 按 [docs/UI_PARITY_IOS.md](docs/UI_PARITY_IOS.md) 里剩下的 🔴 排**：
   ① ~~会话内搜索~~ ✅ 2026-09-09（见「当前焦点」）。**剩下两块**：📅 日历按日期跳转、
      👤「来自某人」发件人过滤（SEARCH_DESIGN 定的 P1）；
   ② ~~群聊详情也改成内联页签~~ ✅ 2026-09-09；
   ③ ~~通讯录好友拼音 A–Z 分组 + 右侧索引尺 + 好友行左滑~~ ✅ 2026-09-09（见「当前焦点」）；
   ④ 水滴头部的**形变那半**（缩放 / 名字迁进标题栏 / 松手吸附，遮罩不做）；
   ③′ ~~按锚点开窗 window_req~~ ✅ 2026-09-09（`MESSAGE_WINDOW_DESIGN` 的 Android 那一期）；
   ⑤ ~~归档内长按菜单 + 定位到聊天~~ ✅ 2026-09-09（见 `docs/UI_PARITY_IOS.md` §2.2）；
      ⑥ ~~引用块内真缩略图~~ ✅；
   ⑦ 语音页签内播放（要接聊天页那套单例播放器）；⑧ 「名片」页签；
   ⑨ 复制的适用范围（iOS 还能复制图片字节与图说，本端只有文本）；
   ⑩ 长按菜单里 iOS 有而本端没有的项（转文字/收藏/置顶/编辑/多选/翻译/举报）。

**0. 群成员头像图 URL 缺失**：`showsSenderAvatar` 挂的是首字母色块（取色三端同源，颜色对），
   但没有头像图——群成员头像无本地缓存。要接得先做 `POST /users/batch` 解析器（CLIENT_PARITY 有这行）。
**0-. 多选（M4-3 的另一半）**：长按菜单的「多选」本轮**刻意没加**——不带着只会弹
   「还没做」的死菜单项交付。`Forward.toggleCapped` 与 100 条上限已就位并测过，
   缺的是多选态 UI + 底部批量栏。
**0a. 合并转发（chat_record 一张卡片）本端没做**：现在只有逐条转发。
   合并转发要一整套卡片渲染 + 匿名 sender key（im-web `useForward.ts` 那套），是独立一块。

按 `../IMServer/docs/CLIENT_PARITY.md` 追 iOS，优先级从高到低：

1. **转发 / 多选 / 收藏**（M4-3、M4-4）：长按菜单现在缺这三项，是 iOS 用得最多的一批。
2. **消息编辑**（M4-5）：`msg_op op=edit` 协议侧已接，缺 UI。
3. **会话详情页**（媒体/文件/链接归档，M4.5-3）。
4. **设置页逐项**（外观/字号/主题偏好等 9 项）：「我」页入口列表已经把位置占好了，
   点进去是「还没做」；`IMAppearance` 令牌层已就位，缺持久化与界面。
   接外观时一并解掉 `values-night` 只跟系统深色导致的启动闪烁（见已知坑）。
6. **@提及**（`mention_spans`，M4-8）：协议字段已在 DTO 里，缺高亮与 @面板。
7. **扫一扫 / 群码 / 扫码登录**（QR P0 的其余部分 + P1）：出码那半边已经做了，
   **读码**这半边需要相机权限与 ML Kit/CameraX，工作量单独一档。
   另欠**头像裁切页**——现在是自动居中方裁，竖幅全身照会裁掉头（iOS 有 `IMAvatarCropViewController`）。
8. **推送**（M5）：依赖 FCM，iOS 侧也还没做。

## 已知坑 / 限制

- **视频不转码**：iOS 会转 720p H.264 再发（`IMMediaPicker`），本端原样直传。
  好处是快且无损，坏处是 iPhone 拍的 HEVC 在部分收端只能看封面点不开——封面有了，播放没解决。
  真要转码得上 MediaCodec，是独立一块。
- **分片上传不跨进程续传**：`upload_id` 只活在这次调用里，杀进程要重传。
  服务端 `/upload/{id}/status` 是支持续传的，缺的是把 `upload_id` 落库。
- **上传进度只到「整条」粒度**：相册宫格里**每格**的环形进度（iOS 有）没做；
  图片走整包上传，没有进度也不打算加（压缩后几百 KB，接了只会闪）。
- **视频没有本地缓存**：每次打开查看器都重新下载整段（本端未配 `SimpleCache`）。
  iOS 有 `IMOriginalVideoCache`。**刻意往后放**：收益只是重复打开同一段省流量，
  而代价是 `SimpleCache` 同一目录只能被一个实例持有（多进程直接抛）+ 要自己定配额与清理策略——
  四条媒体欠账里投入产出比最差的一条。
- **查看器只能看单条**：iOS/Web 还能在会话媒体时间线上左右翻页（CLIENT_PARITY 任务3），
  那要先有「会话媒体列表」这套数据，是独立一块。保存/转发已接，**删除**入口仍没有。
- **存相册的 Q 以前分支没实测**：本机是 Android 15，走的是免权限的分区存储那条；
  `WRITE_EXTERNAL_STORAGE` 那条（Android 9 及更早）只有代码没跑过。
- **媒体失效没有本地登记**：iOS 有 `IMMediaExpiryRegistry`，存之前就能拦下「已被服务端清理」的
  地址并说「该文件已失效」。本端没有，只会走到 HTTP 失败、报一句「保存失败」。
- **选图器的预览页仍不播视频**：只显首帧 + 时长 + 「预览不播放」。
  查看器已经能播了，这里要不要接同一个播放器是个产品选择（微信的选图预览也不播）。
- **相册分页只在页大小调成 4 时验过**：真实 PAGE=300、本机只有 12 张素材，
  日常走不到续页路径。逻辑有 `PageMergeTest` 覆盖，查询层（Bundle 参数 vs 老写法）只验了 Android 11。

- **`CLIENT_PARITY.md` 的 Android 列已补齐**（2026-09-07），「我」页相关四行同日更新。
  填法保守——不确定的一律往低了填，所以可能低估、不会高估。
- **布局验证靠像素测量，不是靠肉眼**：`adb exec-out screencap` 出图后用 PIL 扫，
  比对 `UI_SPEC.md` 的规格值。本轮就靠它纠正了一次目测误判。**但这是手工的**，
  没有自动化——真正的回归还得靠 Robolectric/Compose 或截图基线，两者都还没做。
- **没有 instrumented 测试**：`androidTest` 依赖配好了但一个用例没写。
  Compose 的布局/滚动/输入法这类只能靠模拟器手测，目前全靠人肉截图核对。
- **媒体只做了图片**：视频/语音/文件只能**看**不能**发**，也不能播放/下载。
  语音气泡的波形是等高条纹占位（`waveform` 字段还没解析）。
- **图片不压缩**：只在端上挡 20MB，超了直接不发。iOS/Web 都会先压。
- **消息编辑、多选、收藏、@提及、扫码未做**（转发已做，合并转发未做）。
- **归档里语音仍不能播**：要接聊天页那套单例播放器（iOS 是复用 `IMVoicePlayer sharedPlayer`），
  否则会同时响两处。长按已经可以定位/转发/删除，脚注也如实写明了。
- **会话内搜索只到 P0**：词命中导航有了，📅 日历与 👤「来自某人」没有；命中只取一页
  （本地 500 / 服务端 50），更多时靠计数补 `+` 如实告知，**翻更多页三端都没做**。
- **`window_req` 那条分支没实测**：本地库齐全时走不到它（`windowAround` 恒有结果）。
  要验得先造一个"本地有缺口"的会话（清库后只同步一半）。
- **一次性数据订正靠 SharedPreferences 标记**（`SessionStore.msgOpConverged`）：
  历史遗留 `msg_op` 行的收敛要按 `contentType` 扫全表，而 `message` 表上没有这一列的索引。
  20 多万行的库上每次启动扫一遍会让首屏卡住（实测约 90 秒，两次 `Long db operation`）。
  **以后再加这种一次性订正，一律配一次性标记，别为它加只用一次的索引。**
- **`GroupInfoHost.kt` 已 599 行**（上限 600）：下一个动它的人先拆再加。
- **「我」页 11 个入口里只接通了 3 个**（设备 / 资料 / 二维码），其余 8 个点了是「还没做」提示。
  主题/字号/壁纸等外观偏好的令牌层在（`IMAppearance`），但没有持久化也没有界面。
- **头像没有裁切页**：选图后自动居中方裁 512×512 再传，竖幅全身照可能裁掉头。
- **`values-night`** 见下条。
- **`values-night` 只管启动窗口且跟随系统深色**：用户在应用内选浅色时，
  启动那一瞬间仍是深色底 → 闪一下。接主题偏好时一并解决。
- **明文 HTTP 只对 `10.0.2.2`/`localhost`/`127.0.0.1` 放行**。
  真机连开发机要把内网 IP 加进 `res/xml/network_security_config.xml`，**且别留到生产**。
- **本机 `JAVA_HOME` 是坏的**（Homebrew 未替换占位符 `@@HOMEBREW_JAVA@@`）。
  `scripts/test.sh` 已自愈；手拼 `./gradlew` 要自己
  `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`。
- **启动图标是占位**（绿底白气泡矢量图）。
- **`SessionStore` 用普通 SharedPreferences**：应用私有目录，未 root 读不到，
  但**没有额外加密**（`androidx.security:security-crypto` 已废弃）。要再加一层得自己包 Keystore。

## 关联工程 / 常用命令

- 后端 `../IMServer`；iOS `../IMProgram`；Web `../im-web`。
- **参考实现**：判据类的纯函数可直接照搬 Web（`entryWindow.ts`/`unreadBelow.ts`/`wake.ts`），
  但要守 `../IMServer/docs/SYMMETRY.md` 那条：**对称的是不变式，不是代码形状**。
- 本仓：
  ```bash
  ./scripts/test.sh                # 唯一测试入口（门禁 + 编译 + 单测）
  BUILD_ONLY=1 ./scripts/test.sh   # 只编译
  ONLY=EnvelopeTest ./scripts/test.sh
  ./scripts/install-hooks.sh       # 每个 clone 一次
  ```
- 后端（改了后端代码**必须重启**）：`cd ../IMServer && ./scripts/dev.sh --no-tail`
- 模拟器：AVD 名 `im_test`（API 36 / pixel_5）。启动：
  ```bash
  ~/Library/Android/sdk/emulator/emulator -avd im_test -no-snapshot-load -gpu swiftshader_indirect
  ```
- 模拟器连宿主机后端：`10.0.2.2:8080`（`127.0.0.1` 在模拟器里指模拟器自己）。
- 本地测试账号：`user1001` / `user1002` / `e2etest1`，密码统一 `123456`。
