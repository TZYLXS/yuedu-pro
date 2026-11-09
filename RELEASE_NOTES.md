## 阅读Pro 2.0.3（versionCode 20300）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）2.0.3** 同步发布、同一套版本号。

### 本版仅跟进文档，无代码改动

本轮巡检只有服务端 `docs/更新日志.md` 变了（新增 2.0.3 / 2.0.4 两段），`docs/APP同步接口.md` **一字未改**，
所以客户端 `service/FnReaderBridgeService.kt`、`model/FnReaderRemote.kt`、`ui/config/FnReaderConfigDialog.kt`
全部保持 2.0.2 的状态，一行没动。

| 文档 | 变更 |
| --- | --- |
| `docs/更新日志.md` | **2.0.3**：目录引擎分页上限 5 → 40 页，带循环检测；没写 `nextTocUrl` 时按页面「下一页」链接兜底，没有新章节即停。**2.0.4**：Python 解析引擎的目录同样支持分页，桥接层按 `nextTocUrl` / 页面「下一页」循环抓取（上限 40 页、去重、无新增即停） |
| `docs/APP同步接口.md` | 无变化（SHA256 与上一轮基线一致，`68e3f795…`） |

这两条都发生在**服务端**：分页、去重、循环检测都是服务端自己做的，客户端收到的 `toc` 任务与回传格式完全不变，
超时也不用调——服务端等目录 20 秒，手机侧 `JOB_TIMEOUT_TOC = 18000L` 照旧。

### 实测：单书源从「只回 50 章」变成整本目录一次回完

服务端 `GET /api/m/info` → `{"app":"阅读","version":"2.0.4"}`。

```
GET /api/toc?url=https%3A%2F%2Fwww.dingdians.com%2Findex%2F11010898%2F&refresh=1
→ 191 253 B，chapters 1610 条（服务端按标题去重），
  首条「群星之子逃婚录 第1章」，末条「第3139章 白骨王座（三更）」
```

同一本《群星之子逃婚录》在 2.0.2 服务端上只回 50 章，现在一次翻到末页；这也是上一版遗留问题
（「服务端 `/api/toc` 只抓目录第一页」）在 2.0.4 上的解除。客户端本地按 URL 去重的全量仍是 3139 章，
与服务端的 1610 条差异来自站点目录页的重复条目，与客户端无关。

### 产物

| 文件 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_2.0.3-arm64.apk` | 18 744 091 B（17.88 MB） | `bcad8f9fc048deb771f923470b83882758bd9cf793bd43834fc085a6b2485ce2` |
| `yuedu_pro_app_2.0.3-armv7.apk` | 18 699 109 B（17.83 MB） | `2bba529c6c8befa154c655c3546731a1e23ba75d7a20b1df212511d6845d3783` |

`aapt2 dump badging`：`io.legado.pro`、`versionCode 20003`、`versionName 2.0.3`、`sdkVersion 24`、
`targetSdkVersion 33`、`application-label '阅读Pro'`，arm64 / armv7 各一包；v1 + v2 双签。

### 实测结果

- 编译：arm64 `BUILD SUCCESSFUL in 1m 20s`，armv7 `BUILD SUCCESSFUL`（增量）
- 应用内日志校验：解开 APK 读 `assets/updateLog.md`，首条为 **2026/11/10（2.0.3）**，2.0.2 / 2.0.1 / 1.11 … 历史段完整保留
- 代码零改动：`git diff` 只含 `app/build.gradle` 版本号、`assets/updateLog.md`、`RELEASE_NOTES.md`、README 与 docs

### 安装

- 包名 `io.legado.pro`，应用名「阅读Pro」，可与官方「阅读」共存
- `arm64-v8a` 给 2017 年后的设备；老 32 位设备用 `armv7`
- 签名与 1.x / 2.0.x 一致，可直接覆盖升级，数据不丢

### 已知限制

- 服务端按**标题**去重，站点目录页的重复条目会被合并；客户端按 URL 去重，两者数量可能不同（本书 1610 vs 3139）
- 手机侧删除的书若已在服务端建过，仍需飞牛网页端手动删除（服务端无删书 API 的客户端入口）
- 安卓 4.0 版仍未在真机 4.4 上实测（MuMu 最低 Android 12）
