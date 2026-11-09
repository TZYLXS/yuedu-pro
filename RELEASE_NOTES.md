## 阅读Pro 2.0.2（versionCode 20200）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）2.0.2** 同步发布、同一套版本号。

### 本版主线：软总线拆两条队列，`bookinfo` 对齐文档 §18

本轮巡检命中 FnReader 两份文档同时更新：

| 文档 | 变更 |
| --- | --- |
| `docs/APP同步接口.md` | 新增 **§18 简介补全：让手机把书籍详情带回来**——任务 `bookinfo`，payload `{bookUrl, source, name, author}`，回传 `intro / author / coverUrl / kind / wordCount / lastChapter / totalChapterNum`（全可选，服务端只补空字段）；服务端在 `/api/intro`、`/api/detail` 抓不到时才问手机，**超时 10 秒**，成功响应带 `"via": "phone"` 并写回书架 |
| `docs/更新日志.md` | 新增 **2.0.2 / 2.0.1 / 2.0.0 / 1.11.4** 四段（翻章回顶部、搜索并发闸门与熔断、删除墓碑、`bookinfo`、Python 解析引擎开关等） |

客户端改动（`service/FnReaderBridgeService.kt`，两仓同构）：

- **两条队列**：`bookinfo` 进独立 `infoQueue`（单并发）；`search` / `toc` / `content` / `rank` 进
  `searchQueue`（`SEARCH_CONCURRENCY = 2`）。搜索被慢源拖住时，简介与封面照样准时回传。
- **`bookinfo` 超时 23 s → 9 s**（`JOB_TIMEOUT_BOOKINFO = 9000L`）：对齐 §18.2「超时 10 秒」，手机先放手，
  服务端按时按原样返回，不再白等一个啃不动的详情页。
- `model/FnReaderRemote.kt` **未改**：`bookInfo()` 回传字段与 §18.1 逐项一致，`matchSource()`（source 为空按
  bookUrl host 找本机书源）、`plainText()`（富文本简介转纯文本）在 2.0.1 已落地；服务端 2.0.2 的其余条目
  （书架去重、删除墓碑、搜索熔断）都是服务端行为，客户端侧 2.0.1 已对齐，本版不再重复改动。

### 产物

| 文件 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_2.0.2-arm64.apk` | 18 743 648 B（17.88 MB） | `fff99812bf1648e5b9008f701e7d7270638d0ce4d10549273ad7a8e19f77ee6b` |
| `yuedu_pro_app_2.0.2-armv7.apk` | 18 698 675 B（17.83 MB） | `6400ea9e2fb3458ec7de3fcf7352c48f9d1e87a13549cca722ed4836036714b4` |

`aapt2 dump badging`：`io.legado.pro`、`versionCode 20002`、`versionName 2.0.2`、`sdkVersion 24`、
`targetSdkVersion 33`、`application-label '阅读Pro'`，arm64 / armv7 各一包；v1 + v2 双签。

### 实测结果

- 编译：arm64 `BUILD SUCCESSFUL in 1m 29s`，armv7 `BUILD SUCCESSFUL in 22s`
- 应用内日志校验：解开 APK 读 `assets/updateLog.md`，首条为 **2026/11/10（2.0.2）**，旧段落（2.0.1 / 1.11 / 1.10 …）完整保留
- 服务端：`GET /api/m/info` → `{"app":"阅读","version":"2.0.2"}`
- 模拟器（MuMu Android 12 / API 31）：装 2.0.2 包后首启更新日志首条即本版；
  「我的 → 接入 FnReader 服务器」面板 ①–⑤ 齐全、无 ⑧⑨ 残留按钮；测试连接回显 2.0.2；
  软总线服务连上后能领任务，`bookinfo` 走独立队列（通知栏「正在解析：bookinfo」随后「已回传 bookinfo 结果」）

### 安装

- 包名 `io.legado.pro`，应用名「阅读Pro」，可与官方「阅读」共存
- `arm64-v8a` 给 2017 年后的设备；老 32 位设备用 `armv7`
- 签名与 1.x / 2.0.x 一致，可直接覆盖升级，数据不丢

### 已知限制

- 服务端 `/api/toc` 只抓目录第一页（单书源那本《群星之子逃婚录》服务端只能看到 50 章），
  完整 3139 章需要客户端按 `nextPage` 翻页读取
- 手机侧删除的书若已在服务端建过，仍需飞牛网页端手动删除（服务端无删书 API 的客户端入口）
