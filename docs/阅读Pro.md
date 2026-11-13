# 阅读Pro · 改动说明

底座：[LegadoTeam/legado](https://github.com/LegadoTeam/legado) tag `3.23.110211`
（commit `56c3301dc441c23aaa75bac973943f7469c202be`），不是当前 HEAD。

选这个 tag 的原因：它的依赖组合（AGP 8.1.2 / Gradle 8.3 / Kotlin 1.9.20 / media3 1.1.1 /
room 2.6.0 / okhttp 4.11.0 / compileSdk 34）在 JDK 17 上可稳定构建，且源码已按 targetSdk 33 适配。

## 相对 API19 版（yuedu-android4）的改动

| 位置 | 改动 |
| --- | --- |
| `app/build.gradle` | `applicationId` → `io.legado.pro`（与官方版、API19 版共存） |
| `app/build.gradle` | `minSdk 19 → 24`，`targetSdk 30 → 33` |
| `app/build.gradle` | 新增 `-PtargetAbi=armv7/arm64` 开关，决定 `abiFilters` 与产物名后缀 |
| `app/build.gradle` | `versionCode 19001 → 20004`（当前 2.0.4），输出名 `${appName}_${flavor}_${version}${abiSuffix}.apk` |
| `modules/book`、`modules/rhino1.7.3` | `minSdk 24`、`targetSdk 33`，与主模块一致 |
| `res/values*/strings.xml` | `app_name` → 阅读Pro（含 zh / zh-HK / zh-TW 三个语言目录） |
| `AndroidManifest.xml` | 删除 `<uses-sdk tools:overrideLibrary=...>`（三方库 minSdk 21 < 24，合并无需 override） |
| `README.md`、`docs/*` | 改为 Pro 版说明 |

## 从 API19 版继承、在 API24+ 上不再触发的补丁

这些代码保留但走不到，删了反而增加回归风险，因此原样留着：

- `help/http/Tls12SocketFactory.kt`：只在 `SDK_INT < 21` 时补 `TLSv1.2`
- `help/TTS.kt` 的 `speakCompat`：`SDK_INT >= 21` 走四参 `speak`
- `ui/file/HandleFileActivity.kt`：仅 `SDK_INT < LOLLIPOP` 回退 `FilePickerDialog`
- `modules/book/.../PfdHelper.java`：`@RequiresApi(21)` 分支优先，19 的 `FileInputStream` 回退不会进
- `App.kt` 的 `MultiDexApplication` + `multiDexEnabled true`：minSdk 24 本身原生支持 multidex，留着无害

同样继承下来的移除项：Cronet（整包删除，全部走 OkHttp）、Firebase（analytics/perf 依赖注释掉）。
如需恢复 Cronet，需要重新引入 `app/cronetlib/*.jar` 与 `download.gradle`，并接受 `Cronet_Version` 构建字段的取值来源。

## 签名

`keystore/yuedu.jks`（alias `yuedu`，storepass/keypass `yuedu2026`，JKS，36500 天），**不入库**。

APK 里同时有 `META-INF/CERT.SF` / `CERT.RSA`（v1）与 v2 签章：

- `jarsigner -verify` → 「jar 已验证」（自签证书会提示 PKIX 路径，属正常）
- `apksigner verify --verbose` → `v1 false / v2 true`。这是 apksigner 在 minSdk ≥ 24 时不再校验 v1 的表现，不是签名缺失

Android 7.0 起系统按 v2 校验，v1 只是给仍做 JAR 校验的老安装器留的兜底。

## 构建与验证结果

环境：JDK 17（`jdk-17.0.21+9`）、Android SDK platform 34 + build-tools 34.0.0、Gradle 8.3（wrapper）。

```bash
./gradlew :app:assembleAppRelease -PtargetAbi=armv7
./gradlew :app:assembleAppRelease -PtargetAbi=arm64
```

注意：同一 variant 连续构建会清空 `app/build/outputs/apk/app/release/`，
所以每打完一个 ABI 要立刻把 APK 拷到 `dist/`，否则第二个 ABI 会覆盖掉第一个。

产物：

| APK | 体积 | ABI | 校验 |
| --- | --- | --- | --- |
| `yuedu_pro_app_2.0.2-armv7.apk` | 17.83 MB | armeabi-v7a | `sdkVersion 24` / `targetSdkVersion 33` / `io.legado.pro` / v2 签名 |
| `yuedu_pro_app_2.0.2-arm64.apk` | 17.88 MB | arm64-v8a | 同上 |

原生库（每个 APK 只带自己那一套）：

- `lib/armeabi-v7a/` 或 `lib/arm64-v8a/`：`libarchive-jni`、`libimage_processing_util_jni`、`librenderscript-toolkit`

DEX：6 个（`classes.dex` ~8.6MB、`classes2.dex` 仅 2.9KB、`classes3.dex` ~0.46MB、`classes4.dex` ~8.3MB、
`classes5.dex` ~9.8MB、`classes6.dex` ~2.6MB），minSdk 24 由系统原生处理 multidex，不需要安装 multidex 补丁。

## 还没做的

- **真机运行验证**：测试机 adb 掉线，未实测启动、搜索、阅读。接上 USB 后跑 `scripts/verify_device.bat`。
- 应用内「检查更新」仍指向 Legado 官方仓库，换成本仓库的 Release 需要改 `AppUpdateService` 的地址。
- 未做混淆/资源压缩（`minifyEnabled false`），体积偏大，后续可按需开启。

## 2.0.4（versionCode 20004）：仅跟进文档，无代码改动

服务端 `docs/更新日志.md` 再新增 2.0.5 / 2.0.6 两段（都在讲「加入书架后自动抓完整目录」），
`docs/APP同步接口.md` 无变化（`68e3f7954681`，29343 B）。

- **2.0.5**：加入书架后立即抓完整目录（强制 `refresh`、含分页），服务端后台补全并广播，按钮显示章数
- **2.0.6**：加入书架立即抓完整目录，有手机在线优先走软总线（能过反爬），无设备或失败自动回落本机解析；
  按钮与提示显示章数和来源

客户端侧无需改动：`toc` 任务走 `WebBook.getChapterListAwait`，按 `nextTocUrl` / 页面「下一页」翻页
（上限 40 页）整本抓完才回，没有缓存，天然等同服务端要求的 `refresh`；软总线 `toc` 收到即回
`chapters` 全量，服务端拿去补全即可。超时照旧：`JOB_TIMEOUT_TOC = 18000L`（服务端等 20 s）、
`JOB_TIMEOUT_CONTENT = 23000L`、`JOB_TIMEOUT_BOOKINFO = 9000L`。本版只动版本号与应用内日志。

## 2.0.3（versionCode 20300）：仅跟进文档，无代码改动

服务端 `docs/更新日志.md` 新增 2.0.3 / 2.0.4（目录引擎分页上限 5 → 40 页、带循环检测、
没写 `nextTocUrl` 时按页面「下一页」兜底；Python 引擎目录同样分页），`docs/APP同步接口.md` 无变化。
分页与去重都在服务端做，客户端 `toc` 任务与超时（`JOB_TIMEOUT_TOC = 18000L`，服务端等 20 秒）不变，
本版只动版本号与应用内日志。实测（服务端 2.0.4）：单书源《群星之子逃婚录》从「只回 50 章」
变成整本目录一次回完（末条「第3139章 白骨王座（三更）」，服务端按标题去重 1610 条）。

## 2.0.x（versionCode 20200 / 2.0.2）：书架去重、删除墓碑、软总线拆两条队列

### 主线（2.0.0 – 2.0.1）

- **书架同名去重**：同名（不同书源）只留一本，优先保留本地已有 `.nb` 缓存的那本，其余副本进度先并过去再删；
  推送与自动同步都按这条规则取书
- **删除墓碑**：手动删掉的书不再被自动同步推回来（记墓碑，服务端再推同一本跳过，通知栏「跳过已删 N 本」）
- **⑧⑨ 转后台**：缓存上传与书源双向同步连上服务端即自动，每 5 分钟一轮；缓存只传整本下完的
- 启动页换成「飞牛阅读」竖版毛笔字

### 2.0.2：跟 `docs/APP同步接口.md` §18

- 文档新增 **§18 简介补全：让手机把书籍详情带回来**（`bookinfo` 任务），`docs/更新日志.md` 新增
  2.0.2 / 2.0.1 / 2.0.0 / 1.11.4 四段
- `service/FnReaderBridgeService.kt` 拆两条队列：`bookinfo` 进独立 `infoQueue`（单并发），
  `search` / `toc` / `content` / `rank` 进 `searchQueue`（`SEARCH_CONCURRENCY = 2`），互不挤占
- `bookinfo` 超时由 23 s 收到 **9 s**（`JOB_TIMEOUT_BOOKINFO = 9000L`），对齐 §18.2「超时 10 秒」：
  手机先放手，服务端按时按原样返回，不再白等啃不动的详情页
- `model/FnReaderRemote.kt` 未改：`bookInfo()` 回传 `intro` / `author` / `coverUrl` / `kind` / `wordCount` /
  `lastChapter` / `totalChapterNum` 与 §18.1 字段表一致，`matchSource()`（source 为空按 host 找源）、
  `plainText()`（富文本简介转纯文本）在 2.0.1 已落地
- 两仓 `FnReaderBridgeService.kt` 同构（SHA256 一致），版本 2.0.2 / 20200

## 1.11：lean LEDE 更新日志订阅 + 给 lean 许愿（GitHub）

### 1. lean 的「更新日志」在哪里

`coolsnowwolf/lede` 没有独立 CHANGELOG 文件（`CHANGELOG.md` / `CHANGELOG` / `CHANGELOG.txt` / `docs/README.md` 全部 404，
`README.md` 200），它唯一的「更新日志」就是 **master 分支的提交历史**。GitHub 给每个仓库都开了 Atom 源，
所以订阅地址是：

```
https://github.com/coolsnowwolf/lede/commits/master.atom
```

### 2. Atom 解析（本版才有的能力）

`RssParserDefault.kt` 原先只认 RSS 2.0 的 `<item>`，Atom 的 `<entry>` 一条都进不来，订阅加进去是空白。改动：

| 位置 | 改动 |
| --- | --- |
| 条目态 | `<item>` 与 `<entry>` 同等进入 `insideItem`；`END_TAG` 两个名字都收尾 |
| 链接 | `<link>` 有 `href` 属性就是 Atom 写法，取 `rel="alternate"` 那条；没有属性才 `nextText()`（RSS 写法） |
| 摘要/正文 | `<summary>` / `<content>` 映射到 `description` / `content`，并参与封面提取 |
| 时间 | `<updated>` / `<published>` 走 `atomDate()`：ISO 8601 → 本地 `yyyy-MM-dd HH:mm` |
| 标题 | `oneLine()` 把缩进与换行压成一行（Atom 的 `<title>` 常带换行缩进） |

### 3. 内置订阅的写入方式

新增 `DefaultData.ensureBuiltinRssSources()`：**只补缺失、不动用户增删**。
原来的 `importDefaultRssSources()` 是「先 `deleteDefault()` 再插入」，会把用户删掉的默认源每次升级都塞回来，
所以内置 lean 源走新函数，只在 `upVersion()` 的版本升级分支里调用一次。

内置字段：`sourceUrl=LEDE_RSS_URL`、`sourceName=lean LEDE 更新日志`、`sourceGroup=固件`、
`singleUrl=false`、`enableJs=false`、`customOrder=6`。

### 4. 给 lean 许愿（GitHub）

GitHub 只允许「从 fork 的分支向上游发 PR」，所以「一键许愿」实际是四步，全部实现在 `model/GithubWish.kt`：

1. `GET /user` 认人（同时探测 `GET /repos/<login>/lede` 是否已有 fork）
2. 没 fork → `POST /repos/coolsnowwolf/lede/forks`，然后每 3 秒轮询一次，最多 2 分钟
3. 在 fork 的 master 上建 `wish-<yyyyMMdd-HHmmss>` 分支 → `PUT /contents/wishlist/<时间>-<账号>.md`
4. `POST /repos/coolsnowwolf/lede/pulls`，`head=<login>:<branch>`、`base=master`

兜底：`POST /repos/coolsnowwolf/lede/issues`（不想等 fork 时用），返回 Issue 地址。

UI 用 App 自带控件（`BaseDialogFragment` + `dialog_github_wish.xml`，与「接入 FnReader 服务器」同一套观感）：
token 输入 / 登录·退出登录 / 账号状态行 / 多行许愿框 / 「提交 PR」·「改发 Issue」/ 结果行（成功后可点开）。

token 存 `SharedPreferences`（`githubWishToken` / `githubWishLogin`），不进备份、不写日志。
权限：classic token 勾 `public_repo`；细粒度 token 要 `Contents` 读写 + `Pull requests` 读写，
自动 fork 还需 `Repository creation`（`403` 时界面回显 GitHub 原文并给出这条建议）。

## 1.10：跟进 `docs/更新日志.md` 1.11.3，无代码改动

`docs/更新日志.md` 新增 **1.11.3（2026-11-10）**：服务端补 `applyReplaceRules`（正文净化）、`formatJs` 结果生效、脚本/测试目录名统一、日志写入 `docs`、README 版本自动同步——全是服务端 legado-py 侧修复。

`docs/APP同步接口.md` 本轮**未变**（27 458 B / SHA256 `7b46d6a625031f31…`），第 4 / 9 / 11 / 13 / 14 / 15 / 16 / 17 章的字段表继续有效，`FnReaderRemote.kt` / `FnReaderBridgeService.kt` / `FnReaderConfigDialog.kt` 与 1.9 完全一致，本版只动版本号（1.10 / 11000）与文档。

正文净化与 `formatJs` 在服务端就做完了，`/api/m/content` 返回的已经是净化后的正文；客户端不再叠一层，否则二次净化会把分段拆乱。

实测（MuMu + FnReader 1.11.3）：首启日志首条 `2026/11/10（1.10）`，「测试连接」回显 1.11.3，书架 10 本 / 进度 10 条 / 书源 403 个。

## 1.9：跟进文档 §15，软总线支持 rank 代抓榜单

`docs/APP同步接口.md` 新增 **§15 每日推荐（`GET /api/daily`）**：服务端按「起点 → 纵横 → 17K」
依次抓榜单，起点有反爬时先让软总线上的在线手机抓，任务类型 `rank`、`payload={url}`。
`docs/更新日志.md` 补了 1.10.0–1.11.2 的版本记录（§16 缓存同步、§17 书源双向同步本客户端 1.8 已实现，复核无差异）。

代码只动一处（`model/FnReaderRemote.kt`、`service/FnReaderBridgeService.kt`，与 A4 同构）：

| 改动 | 说明 |
| --- | --- |
| `FnReaderRemote.execute` 增加 `"rank"` 分支 | 以前收到 rank 直接回 `ok:false`（1.8 及更早写死只认 search/toc/content） |
| `rank(payload)` | 浏览器 UA 抓 `payload.url`；不足 10 KB 视为反爬页直接报错；Jsoup 按三套选择器解析（起点 `.book-img-text li` / `.rank-list li`、纵横 `.zh-modules-rank-book`、17K `/book/<id>.html`），去重后补 `rank` 序号 |
| 回传结构 | `{source,url,books:[{rank,name,url,author,cover,score}]}`，服务端直接当 `/api/daily` 的 books 用 |
| `timeoutOf("rank")` | 23 秒（与正文同档，抓页面本身就慢） |

反爬页与「解析出 0 本」都会回 `ok:false` + 原因，服务端照旧降到纵横 / 17K，网页端不会白屏。

## 1.8：origin 必须是书源真实地址（修「没有书源」）

1.7 有个真实事故：客户端加书时 `origin` 传空、`originName` 传 `手机端`，服务端靠 `book.origin` 取目录与正文，
结果网页端打开提示「没有书源」、目录空、正文读不出来。1.8 在 `ui/config/FnReaderConfigDialog.kt` 里补齐：

| 位置 | 改动 |
| --- | --- |
| 新增 `SourceIndex` / `buildSourceIndex()` | 由 `bookSourceDao.flowAll()` 建「`bookSourceUrl` → (地址, 源名)」和「host → 同宿主书源」两张表 |
| `SourceIndex.resolve(book)` | 三级匹配：书的 origin 已是真实源地址 → 用规范的地址+源名；按 `bookUrl` 的 host 命中（多源优先同名）；按源名唯一命中；全不中才留空 |
| `looksLikeSearchUrl()` | `/search`、`?q=`、`&q=`、`?wd=`、`?keyword=`、`/so/`、`s?ie=` 一律不是详情页，跳过不推 |
| `pushJson(book, index, cover)` | 推书的 `origin` = `bookSourceUrl`、`originName` = 源名；结果区报「非详情页跳过 N 本」「无匹配书源 N 本」 |
| `cacheJson(book, folder, files, index, cover)` | ⑧ 缓存补 `layout:"legado"`、`chapters[].file`、`chapters[].raw`（`.nb` 原文）、`origin`/`originName` |
| `fetchCoverData()` / `downloadCover()` / `fetchCoverBytes()` / `gunzip()` | 本机没有封面文件、或服务端封面仍是源站外链时现下（8s 超时、≤3 MB），`Referer` 先空、再图片域名、再书源地址，图片被 gzip 预压过就解一层，转 base64 随书推 |
| `syncSourcesBoth()` | `currentUrls` / `goneUrls`（靠 `fnReaderSourcesSnapshot` 快照）+ `deleted[{bookSourceUrl,lastUpdateTime}]`；`sourceJson()` 给没时间戳的源补当前毫秒；`pull` 覆盖本地 |
| 文件级工具 | `ORIGIN_PLACEHOLDERS`（`""` / `手机端` / `本地` / `未知` / `导入` / `默认` / `loc_book` / `local` / `null` / `none`）、`isPlaceholderOrigin`、`normSourceUrl`、`hostOfUrl`、`looksLikeSearchUrl` |

实测（MuMu 模拟器 + FnReader 1.11.1/1.11.2）：9 本书 origin 全部等于书源 `bookSourceUrl`；
6 本目录/正文可读；⑧ 落盘 `layout: legado` + `raw` 原文；5 本封面落 NAS `covers/`；⑨ 双向改名生效。

## 1.7 起：FnReader 1.11.0 的两个新接入项


服务端 `docs/APP同步接口.md` 新增 §16 缓存同步、§17 书源双向同步，客户端在
`ui/config/FnReaderConfigDialog.kt` 里加了两项（菜单 ①–⑦ 不动，新增 ⑧⑨）：

| 方法 | 作用 | 要点 |
| --- | --- | --- |
| `uploadCache()` | ⑧ 把本机 `book_cache/<书名>/` 的 `.nb` 整本推给 `POST /api/m/cache` | `raw` 传原文；单本 > 30 MB 跳过；章节图片 1 B–2 MB 转 base64；读超时 60s |
| `cacheJson()` | 组 §16 载荷 | `index` 取文件名前 5 位，`title` 取本地章节表，`file` 原名 |
| `syncSourcesBoth()` | ⑨ 书源双向同步 | 首次 `POST /api/m/sources` 合并上传；之后按 `lastUpdateTime > since` 增量 POST/GET；`pull` 覆盖、`pullDeleted` 删除 |
| `mergePull()` / `applyPullDeleted()` | 落服务端更进 / 删除的书源 | 都按 `bookSourceUrl` |

首次同步为什么不用 `POST /api/m/sources/sync` 推全量：实测「本机全量 + `since: 0`」会被服务端当成整库快照，
删掉本机没有的源（1253 → 995，丢 258 条），只能靠 `backup/sources-*.json` + `POST /api/m/sources` 补回。
这条坑写进了 `docs/app回传接受规范.md` §9。
