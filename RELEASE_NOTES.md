## 阅读Pro 2.4.3（versionCode 20403）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）2.4.3** 同步发布、同一套版本号，并与服务端 `legado-web` **v2.4.3** 对齐。

### 本版新增

- **目录先行（toc-only）**：解析电子书时只回传目录分片，正文不在首轮全量上传，按章按需补齐。
- **按章回传**：服务端下发 `ebook-chapter` 任务时，客户端用本地原文件取出该章，推给 `/api/m/ebook/chapter`，再把服务端 ack 作为任务结果回传。

### 实测结果（测试端 `10.5.4.70:1123`）

| 环节 | 结果 |
| --- | --- |
| 上传 → 派发 → 手机解析 | mobi 上传后约 6 s 变 `complete`，`chapters=3`、`parts=2/2`，`/api/bus` 出现在线设备与 `ebook-parse ok:true` |
| 按章回传 | `POST /api/remote/job` 造 `ebook-chapter`，客户端 0 s 回 `{"chapters":1,"indexes":[1]}`；`/api/content?index=1` 返回 2 段正文（`cached:true`、`via:ebook-json`） |
| 删除接口 | `DELETE /api/ebook/delete?sha1=` 两条 `failed` 条目均 200，列表 count 6→5 |
| 发版包冒烟 | MuMu 安装启动正常，`versionName 2.4.3` / `versionCode 20403`，更新日志首条即本版 |

### 产物

| 文件 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_2.4.3-arm64.apk` | 18 767 398 B（17.90 MB） | `115cc50b5c8dafb40e9b2afe8b12cf5f92ee00b89742fdfdd075d7ce074fae91` |
| `yuedu_pro_app_2.4.3-armv7.apk` | 18 722 423 B（17.85 MB） | `260840a4c5cfd921657fee9071217d9af94da62bd5e4429a46bf3ea80ff4c540` |

`aapt2 dump badging`：arm64 → `io.legado.pro`、`versionCode 20403`、`versionName 2.4.3`、`sdkVersion 24`、`targetSdkVersion 33`、`native-code 'arm64-v8a'`；armv7 同上但 `native-code 'armeabi-v7a'`；两个包的 `application-debuggable` 均不存在（发版包无 debug 能力）。

### 安装

- 包名 `io.legado.pro`，应用名「阅读Pro」，可与官方「阅读」共存
- `arm64-v8a` 给 2017 年后的设备；老 32 位设备用 `armv7`
- 签名与 1.x / 2.x 一致，可直接覆盖升级，数据不丢

### 已知限制

- **toc-only 目前是休眠分支**：服务端 `ebook/bus.js` 派发 `ebook-parse` 时 payload 不带 `tocOnly` / `mode:toc`，上传一律走完整分片解析，客户端 `runEbookJob` 里只发 part0 的判断暂时不会命中；要激活需服务端在 payload 里补字段。
- PDF 原页模式（`mode=page` 返回 `k:"img"`）客户端尚无渲染能力，本版未做。
- 服务端删除条目时若未带 `&file=1`，`ebook` 目录里的源文件不会删，重扫会再次入库。
