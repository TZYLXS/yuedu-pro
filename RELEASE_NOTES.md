## 阅读Pro 2.6.7.1（versionCode 2060701）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **arm64-v8a** 与 **armeabi-v7a** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、安卓 4.0 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）2.6.7.1** 同步发布、同一套版本号；版号基线取自服务端 `legado-web` **v2.6.7**，本版为其上的 `0.0.0.1` 小更新。

### 本版做了什么

**仅跟进文档，无代码改动**。轮询发现服务端一夜之间从 v2.6.5 连发 v2.6.6 / v2.6.7，逐条比对后没有任何一条改到 App 契约，本版把版本号一次对齐到服务端 2.6.7；Kotlin / Java 代码与 2.4.4.1 完全一致
（`FnReaderRemote.kt`、`FnReaderBridgeService.kt`、`FnReaderConfigDialog.kt` 零 diff）。

- `docs/APP同步接口.md` **零改动**：33802 B，SHA256 `e04f34664bcd5e5bd02abb5342698d2e1219d0b849d76003211c589e223f9ea1`
- `docs/更新日志.txt` 新增 2.6.6 / 2.6.7 两段（`docs/更新日志.md` 自 2.6.0 起服务端只写 `.txt`，巡检两处都比对，避免漏更新）
- **2.6.6**：净化规则（替换·屏蔽）开始作用于客户端接口 `/api/m/content`，App 正文与网页从此一致；返回体仍是
  `bookUrl` / `title` / `index` / `total` / `prev` / `next` / `paragraphs` / `cached`，结构未变
- **2.6.7**：网页端新增「离线缓存」页（按书列出在线缓存与本地电子书、逐本导出 TXT）、`GET /api/ebook/export`（整本 TXT 导出）、
  `GET /api/update/check`（启动时检查 GitHub 最新 Release 并弹窗）；三个入口都只在网页端用，App 侧没有对应界面，也不需要

### 契约复核（逐项核对，全部未变）

| 维度 | 结论 |
| --- | --- |
| 回传字段 | `search` / `toc` / `content` / `rank` / `bookinfo` 上行字段与 2.4.4.1 一致；2.6.6 只改了 `content` 的正文内容，字段名与结构没动 |
| 接口路径 | `/api/m/*` 未增删改；2.6.7 新增的 `/api/offline`、`/api/ebook/export`、`/api/update/check`、`/api/logs/export` 全在网页端 |
| 鉴权 | 回传用 `X-Api-Token`；传书仍是 `GET /api/ebook/upload` 现取令牌 + `POST /api/m/ebook/upload?name=…` 带 `X-Upload-Token`，401 `code=upload-token` 时重取一次 |
| 任务类型与超时 | `search` 18 s / `toc` 18 s / `content` 23 s / `rank` 23 s / `bookinfo` 9 s，均未变 |
| 错误码 | 未变（`upload-token` 401、超限 413、multipart 400 语义照旧） |
| 封面 / 书源通道 | `/api/m/cover`、`/api/m/sources`、`/api/m/sources/sync`、`/api/m/cache` 未变 |

### 实测结果（服务端 `TZYLXS/FnReader`，MuMu Android 12）

| 环节 | 结果 |
| --- | --- |
| 文档比对 | `git fetch` 远端 `6e471c3 → 01beafd`（中间 `5392a3a` = v2.6.6）；三份文档各自核 sha256，`更新日志.txt` 变、另两份未变 |
| server.js diff | 6 文件 +556/−121：新增 `LOG_RING`/`logPush`、`applyReplaceRules`、`/api/offline`、`/api/ebook/export`、`/api/update/check`、`/api/logs/export`；`/api/m/*` 一行未动 |
| 编译 | arm64 / armv7 两次 `assembleAppRelease` 均 `BUILD SUCCESSFUL`（exit 0） |
| badging | 两包均 `versionCode 2060701` / `versionName 2.6.7.1`；arm64 `native-code 'arm64-v8a'`、armv7 `'armeabi-v7a'`；均无 `application-debuggable`（发版包去 debug 能力） |
| 应用内日志 | 用 zipfile 解包读 `assets/updateLog.md`，首条即 `2026/11/16（2.6.7.1）`，旧段落保留（共 53 条） |
| MuMu 冒烟 | 覆盖安装启动正常，首启更新日志弹窗首条即本版；面板 ①–⑧ 与 `buildMethods()` 一致（本版 Kotlin 零 diff，沿用 2.4.4.1 实测结论） |

### 产物

| 文件 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_2.6.7.1-arm64.apk` | 18 773 207 B（17.90 MB） | `bb97040d084336eda92856b0af9e72f3bae7a5b03972e27ab06c37d82ded6b6a` |
| `yuedu_pro_app_2.6.7.1-armv7.apk` | 18 728 228 B（17.86 MB） | `5bd0bf72bc337b8d11a881b83ec4fd24769112ab79726a962f04dfe42fd4c521` |

### 安装

- 包名 `io.legado.pro`，应用名「阅读Pro」，可与官方「阅读」、安卓 4.0 版共存
- `arm64-v8a` 给 2017 年后的设备；老 32 位设备用 `armv7`
- 签名与 2.4.4.1 及更早版本一致，可直接覆盖升级，数据不丢

### 已知限制

- 传书只做「本机文件 → 飞牛」，不做管理端的上传历史与重试队列；失败后重新点 ⑧ 再选一次即可。
- 分片上传（大文件断点续传）未做，当前是单次裸 body 直传，超 `maxMB`（默认 1024 MB）由服务端 413 拒绝。
- 服务端 `ebook-parse` 派发 payload 仍不带 `tocOnly` / `mode:toc`，toc-only 分支继续休眠。
- PDF 原页模式（`k:"img"`）客户端尚无渲染能力。
- 服务端「离线缓存」页与「更新提醒」只在网页端有，App 内没有对应入口；App 更新仍走 GitHub Release。
