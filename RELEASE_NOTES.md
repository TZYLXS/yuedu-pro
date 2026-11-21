## 阅读Pro 3.0.2.1（versionCode 3000201）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **arm64-v8a** 与 **armeabi-v7a** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、安卓 4.0 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）3.0.2.1** 同步发布、同一套版本号；版号按服务端 FnReader **3.0.2 + 0.0.0.1** 对齐。

### 按 3.0.x 客户端适配清单补齐自动回传

本版不是只跟更新日志，而是按 `3.0.x更新与客户端适配清单.md` 补齐 6 类回传里缺失的自动化能力：

- **加书/改书自动回传**：`Book.save()` 落库后调用 `FnReaderAutoSync.scheduleBookPush()`，2 秒防抖 `POST /api/m/books`；未配置服务器或软总线未开启时内部跳过。
- **阅读进度自动回传**：`ReadBook.saveRead()` 保存后调用 `scheduleProgressPush()`，3 秒防抖 `POST /api/m/progress`；服务层每轮再按 `fnReaderProgressSyncedMs` 水位批量 `POST /api/m/progress/batch`。
- **独立封面上传**：新增 `POST /api/m/cover`，JSON `{bookUrl,mime,data:"data:image/...;base64,..."}`，单张上限 8 MB；接口 404 时写入 `fnReaderNoCoverApi=1` 并停止后续尝试。随 books 的 `coverData/coverUrl` 保留为兼容路径。
- **书源双向同步补齐删除**：自动同步先读本机书源快照，本机删除的书源写入 `deleted` 数组；`POST /api/m/sources/sync` 同时带 `updated` 与 `deleted`，成功后刷新快照；`lastUpdateTime<=0` 的旧书源自动补当前时间戳。
- **自动补书**：服务端有、本机没有的继续拉取；本机有、服务端没有的书按书名归一化去重后回传，同名不同源只计数跳过。
- **应用内更新兼容四段版本号**：`AppUpdateGitHub.versionCodeOf()` 增加四段解析，`v3.0.2.1` 正确换算为 `3000201`，避免新版 Release 被误判为旧版。

### 包信息

| 产物 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_3.0.2.1-arm64.apk` | 18 795 693 B（17.92 MB） | `BEC04A474AEA2BA5A2C670A2E2C4B225115DCA2B754759ED5CC2EEAC325FC3D4` |
| `yuedu_pro_app_3.0.2.1-armv7.apk` | 18 750 715 B（17.88 MB） | `AC9D5E6F9D6F60C5D34E051E0F17B3D7A239325DC75BA2DFCF43BA75B6F4D396` |

- `aapt2 dump badging` 与 `apksigner verify` 结果见构建完成后的校验记录；两包均无 `application-debuggable`
- 包内 `assets/updateLog.md` 首条为 `**2026/11/22（3.0.2.1）**`
- 签名沿用 `keystore/yuedu.jks`（v1 + v2），可直接覆盖安装旧版

### 安装方式

1. 下载对应 ABI 的 apk（2017 年后的手机 / 平板 / 电视盒子选 arm64，老 32 位设备选 armv7）
2. 覆盖安装即可升级，书源与书架数据保留
3. 进入「我的 → 接入 FnReader 服务器」，填服务端地址与 `X-Api-Token`

### 说明

- 自动回传依赖「接入 FnReader 服务器」已开启且地址 / token 有效；未开启时所有钩子直接返回，不产生后台请求。
- 首次启用后，进度水位从 0 开始，会把已有阅读记录的书补推一遍；之后只推增量。
- 封面接口只处理本机文件封面（PNG/JPEG/GIF/WebP），在线 `http(s)` 封面仍随 books 的 `coverUrl` 下发。
