## 阅读Pro 2.0.4（versionCode 20004）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）2.0.4** 同步发布、同一套版本号。

### 本版仅跟进文档，无代码改动

本轮巡检服务端 `docs/更新日志.md` 又新增 **2.0.5 / 2.0.6** 两段（2.0.3 / 2.0.4 段上一版已跟），
`docs/APP同步接口.md` **一字未改**（SHA256 `68e3f7954681`，29343 B）。

| 文档 | 变更 |
| --- | --- |
| `docs/更新日志.md` | **2.0.5**：加入书架后立即抓完整目录（强制 `refresh`、含分页），服务端后台补全并广播，按钮显示章数。**2.0.6**：加入书架立即抓完整目录，有手机在线优先走软总线（能过反爬），无设备或失败自动回落本机解析；按钮与提示显示章数和来源 |
| `docs/APP同步接口.md` | 无变化（`68e3f7954681`，29343 B，逐字节一致） |

两条都在讲服务端「加入书架」这条链路，`service/FnReaderBridgeService.kt`、`model/FnReaderRemote.kt`、
`ui/config/FnReaderConfigDialog.kt` 全部保持 2.0.3 的状态，一行没动。

### 为什么客户端不用改

- **「强制 refresh」等价于客户端现状**：软总线 `toc` 任务走 `WebBook.getChapterListAwait`，
  每次都现抓站点目录，不读缓存，按 `nextTocUrl` / 页面「下一页」翻页（上限 40 页）整本抓完才回
  `chapters`，服务端拿到的本来就是新鲜全量目录。
- **「优先走软总线、失败回落本机」是服务端的调度策略**：任务类型仍是 `toc`，payload 仍是
  `{source, bookUrl, name, author}`，回传仍是 `{"chapters":[{index,title,url}...]}`，契约没变。
- **「按钮显示章数 / 来源」是服务端网页端 UI**，与客户端面板无关。
- **超时照旧**：服务端等目录 20 s，客户端 `JOB_TIMEOUT_TOC = 18000L`；正文 25 s / `JOB_TIMEOUT_CONTENT = 23000L`；
  简介 `JOB_TIMEOUT_BOOKINFO = 9000L`。

### 产物

| 文件 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_2.0.4-arm64.apk` | 18 744 442 B（17.88 MB） | `f1eeed7e314917218276dafdcfea5b868fb888d29fbfbb20d0f4e3b073c94cb3` |
| `yuedu_pro_app_2.0.4-armv7.apk` | 18 699 454 B（17.83 MB） | `13a6528644193a3fbb96ea4fa5fc3f3033778cb4d28159567e42fd2def2eec45` |

`aapt2 dump badging`：arm64 → `io.legado.pro`、`versionCode 20004`、`versionName 2.0.4`、`sdkVersion 24`、
`targetSdkVersion 33`、`native-code 'arm64-v8a'`；armv7 → 同上但 `native-code 'armeabi-v7a'`；v1 + v2 双签。

### 实测结果

- 编译：arm64 `BUILD SUCCESSFUL in 2m 10s`（81 tasks），armv7 `BUILD SUCCESSFUL in 26s`（增量 9 tasks）
- 应用内日志校验：解开三个 APK 读 `assets/updateLog.md`，首条均为 **2026/11/14（2.0.4）**，
  2.0.3 / 2.0.2 / 2.0.1 / 1.11 … 历史段完整保留
- 代码零改动：`git diff` 只含 `app/build.gradle` 版本号、`assets/updateLog.md`、`RELEASE_NOTES.md`、
  README 与 docs（含 `prune-releases.json`）

### 安装

- 包名 `io.legado.pro`，应用名「阅读Pro」，可与官方「阅读」共存
- `arm64-v8a` 给 2017 年后的设备；老 32 位设备用 `armv7`
- 签名与 1.x / 2.0.x 一致，可直接覆盖升级，数据不丢

### 已知限制

- 服务端按**标题**去重，站点目录页的重复条目会被合并；客户端按 URL 去重，两者数量可能不同
- 手机侧删除的书若已在服务端建过，仍需飞牛网页端手动删除（服务端无删书 API 的客户端入口）
- 安卓 4.0 版仍未在真机 4.4 上实测（MuMu 最低 Android 12）
- 服务端「手机在线优先走软总线」需要面板保活（前台服务常驻）才能被服务端看见
