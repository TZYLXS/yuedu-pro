## 阅读Pro 3.0.8.1（versionCode 3000801）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **arm64-v8a** 与 **armeabi-v7a** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、安卓 4.0 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）3.0.8.1** 同步发布、同一套版本号；版号按服务端 FnReader **3.0.8 + 0.0.0.1** 对齐。

### 仅同步服务端 3.0.8，无客户端代码改动

服务端从 `3.0.2` 连发到 `v3.0.8`（tag `eb15a61`），本版只同步版号，客户端代码零改动：

- **书城兜底**：缺封面/简介/评分时自动补全，出版物走亚马逊、网文走起点/晋江；Web 端新增 `/api/books/enrich`，只填空字段，设置页可开关。
- **TXT 导出**：单本与书架批量改由 Python `export_txt` 处理（4 线程、UTF-8、空行规范化），Python 不可用自动回落 JS。
- **导出全部改异步任务**：`POST /api/export/books-txt` 立即返回 202，新增 `GET /api/export/books-txt/status`，网页轮询进度；3.0.8 起进度按章节显示「已导出章数/总章数」。
- **TOC 客户端代理解析保持最高优先级**：缓存不完整也继续问手机；`/api/engine` 探测加 1.5 秒超时，Java/Python 后台启动，不再卡设置页。
- `docs/APP同步接口.md` 与 `/api/m/*` 契约未变；3.0.2.1 的 6 类回传、软总线、封面/书源/缓存通道全部沿用。

### 包信息

| 产物 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_3.0.8.1-arm64.apk` | 18 795 987 B（17.92 MB） | `F6128FA3CECCB8583B97130D12C647C385D977B07650CB611BC4409EC9FED4E8` |
| `yuedu_pro_app_3.0.8.1-armv7.apk` | 18 751 010 B（17.88 MB） | `3A4946DCF61494597F8C534BE22F6FE427E0594FA14F5BC501B81B4DE3C39AAB` |

- `aapt2 dump badging`：两包均 `versionCode='3000801' versionName='3.0.8.1'`，minSdk 24 / target 33，无 `application-debuggable`
- `apksigner verify`：v2 true；minSdk ≥ 24 时 v1 显示 false 属正常，证书 `CN=FnReader YueDu`
- 包内 `assets/updateLog.md` 首条为 `**2026/11/22（3.0.8.1）**`
- 签名沿用 `keystore/yuedu.jks`，可直接覆盖安装旧版

### 安装方式

1. 下载对应 ABI 的 apk（2017 年后的手机 / 平板 / 电视盒子选 arm64，老 32 位设备选 armv7）
2. 覆盖安装即可升级，书源与书架数据保留
3. 进入「我的 → 接入 FnReader 服务器」，填服务端地址与 `X-Api-Token`

### 说明

- 本版无客户端代码改动，未重复做真机加书/翻页/封面回传验证；3.0.2.1 的静态校验与真机遗留项保持不变。
- 应用内「检查更新」仍指向 GitHub `TZYLXS/yuedu-pro` 的 `releases/latest`；本 Release 发布后即指向 `v3.0.8.1`。
