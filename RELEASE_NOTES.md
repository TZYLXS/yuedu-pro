## 阅读Pro 3.0.10.1（versionCode 3001001）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **arm64-v8a** 与 **armeabi-v7a** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、安卓 4.0 版共存安装。
与 **安卓 4.0 版（`io.legado.api19`）3.0.10.1** 同步发布、同一套版本号；版号按服务端 FnReader **3.0.10 + 0.0.0.1** 对齐。

### 仅同步服务端 3.0.10，无客户端代码改动

服务端从 `3.0.8` 连发到 `v3.0.10`（tag `255c51c`），本版只同步版号，客户端代码零改动：

- **3.0.9 修复导出卡死**：TXT 导出不再占用规则解析 Python 进程池，改用一次性独立进程；单次 60 秒硬超时，超过 4000 章直接走 JS 拼装；stdin EPIPE 不再触发 `uncaughtException`。
- **3.0.10 修复全新安装启动失败**：启动脚本依次探测 `nodejs_v22/v24/v20/v26` 与系统 node，逐个执行 `node -v` 校验，跳过缺 libatomic 等损坏运行时，并记录实际使用的 Node。
- `docs/APP同步接口.md` blob 仍为 `f4b8c9f957a1d609eb0a231123fadaa196ec5644`，`docs/软总线路由与权重.md`、`docs/AI构建指南.md` 也未变；`/api/m/*` 契约未变。
- 3.0.2.1 的 6 类回传、软总线、封面/书源/缓存通道全部沿用。

### 包信息

| 产物 | 体积 | SHA256 |
| --- | --- | --- |
| `yuedu_pro_app_3.0.10.1-arm64.apk` | 18 796 333 B（17.93 MB） | `3409671F0562C23D2C53CDC1638E298E6CB44B62BC94EDDD5DE64D472A9CBD52` |
| `yuedu_pro_app_3.0.10.1-armv7.apk` | 18 751 353 B（17.88 MB） | `274DF243765BD3CDEBC97E969661026AD98DCEB48352578AF9CDAEACB844FADE` |

- `aapt2 dump badging`：两包均 `versionCode='3001001' versionName='3.0.10.1'`，minSdk 24 / target 33，无 `application-debuggable`
- `apksigner verify`：v2 true；minSdk ≥ 24 时 v1 显示 false 属正常，证书 `CN=FnReader YueDu`
- 包内 `assets/updateLog.md` 首条为 `**2026/11/22（3.0.10.1）**`
- 签名沿用 `keystore/yuedu.jks`，可直接覆盖安装旧版

### 安装方式

1. 下载对应 ABI 的 apk（2017 年后的手机 / 平板 / 电视盒子选 arm64，老 32 位设备选 armv7）
2. 覆盖安装即可升级，书源与书架数据保留
3. 进入「我的 → 接入 FnReader 服务器」，填服务端地址与 `X-Api-Token`

### 说明

- 本版无客户端代码改动，未重复做真机加书/翻页/封面回传验证；3.0.2.1 的静态校验与真机遗留项保持不变。
- 应用内「检查更新」仍指向 GitHub `TZYLXS/yuedu-pro` 的 `releases/latest`；本 Release 发布后即指向 `v3.0.10.1`。
