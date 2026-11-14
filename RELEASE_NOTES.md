## 阅读Pro 2.4.4.1（versionCode 2040401）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）2.4.4.1** 同步发布、同一套版本号；版号基线取自服务端 `legado-web` **v2.4.4**，本版为其上的 `0.0.0.1` 小更新（服务端 2.4.4 只有网页端改动，接口契约未变）。

### 本版新增

- **传书到飞牛**：设置面板新增「⑧ 传书到飞牛」，直接选本机电子书（pdf / mobi / prc / azw / azw3 / epub / txt）推给飞牛，不必再挂 WebDAV 或用 curl。
  - 上传令牌**每次现取**：上传前先 `GET /api/ebook/upload` 取当前 `token`，再 `POST /api/m/ebook/upload?name=<文件名>`，头带 `X-Upload-Token`、body 为裸 `application/octet-stream`（非 multipart）。
  - 令牌被服务端重置/轮换也无需改配置：命中 `401` 且 `code=upload-token` 时自动重新取一次令牌续传（最多重试一次）。
  - 服务端关闭传书（`enabled=false`）、超限（`maxMB`）、格式不支持时，面板直接显示服务端给出的原因文案。

### 实测结果（测试端 `10.5.4.70:1123`，MuMu Android 12）

| 环节 | 结果 |
| --- | --- |
| 传书 mobi | 面板显示「已传：shuihu-transfer.mobi（0.0 MB）… sha1 e9140aecff…」，与服务端已有书同 sha1 故按去重处理 |
| 传书 txt | 面板显示「飞牛已入库并解析完成」，服务端 `/api/ebook/list` 新增 `transfer-test` 155 B、`status=complete`、sha1 与本地一致 |
| 令牌轮换 | `POST /api/ebook/upload/reset-token` 换成新令牌后，不重启 App 直接再传 `transfer-token2.txt` 成功，服务端新增 `transfer-token2` 181 B、`complete`，sha1 `c45379f7…` 一致 |
| 发版包冒烟 | MuMu 安装启动正常，`versionName 2.4.4.1` / `versionCode 2040401`，更新日志首条即本版，面板 ⑧ 项可见可执行 |

### 产物

| 文件 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_2.4.4.1-arm64.apk` | 18 772 822 B（17.91 MB） | `80c66191070ab8e4fbe71deeb819db51be9b509d03072eb11a3cc0e61f22804b` |
| `yuedu_pro_app_2.4.4.1-armv7.apk` | 18 727 842 B（17.86 MB） | `324762795d4c2ef9ff6bc8373800b1e8610a1b96bf03f1298ef44d8d5578b73c` |

`aapt2 dump badging`：arm64 → `io.legado.pro`、`versionCode 2040401`、`versionName 2.4.4.1`、`sdkVersion 24`、`targetSdkVersion 33`、`native-code 'arm64-v8a'`；armv7 同上但 `native-code 'armeabi-v7a'`；两个包均无 `application-debuggable`（发版包无 debug 能力）。

### 安装

- 包名 `io.legado.pro`，应用名「阅读Pro」，可与官方「阅读」共存
- `arm64-v8a` 给 2017 年后的设备；老 32 位设备用 `armv7`
- 签名与 2.4.3 及更早版本一致，可直接覆盖升级，数据不丢

### 已知限制

- 传书只做「本机文件 → 飞牛」，不做管理端的上传历史与重试队列；失败后重新点 ⑧ 再选一次即可。
- 分片上传（大文件断点续传）未做，当前是单次裸 body 直传，超 `maxMB`（默认 1024 MB）由服务端 413 拒绝。
- 服务端 `ebook-parse` 派发 payload 仍不带 `tocOnly` / `mode:toc`，toc-only 分支继续休眠。
- PDF 原页模式（`k:"img"`）客户端尚无渲染能力。
- 服务端删除条目时若未带 `&file=1`，`ebook` 目录里的源文件不会删，重扫会再次入库。
