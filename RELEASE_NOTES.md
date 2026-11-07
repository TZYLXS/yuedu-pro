## 阅读Pro 1.0.26.110805（versionCode 24005）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。

### 本版改动

**1. 「FnReader 服务器」入口挪到我的首页**
我的 → **书源管理下面一行**：FnReader 服务器（原在 设置 → 其它设置 里的入口已移除，只留这一处）。

**2. 按 FnReader「App 同步接口」重新设计**
弹窗里填地址与令牌，点「同步」直接调 `GET /api/m/sync`，一次就显示：

- 服务端 `app` / `version`、`serverTime`
- `shelfCount` 本书、`progressCount` 条进度
- `sources.total` 个书源、其中 `enabled` 个启用

**3. 一集菜单：七种接法收在同一个列表**
① 导入书源：下 `GET /api/m/sources.json?all=1`（与 Legado 导出同构的数组）直接导入
② 复制同步接口地址：`GET /api/m/sync?token=…`，可给别的客户端/脚本用
③ 回传阅读进度：本机书架每本书的章节与位置 `POST /api/m/progress/batch`（不在飞牛书架的书会被服务端跳过）
④ WebDAV：`/dav/backup/` 备份恢复，`/dav/books/` 浏览已下载 txt
⑤ HTTP API：`sync / sources.json / shelf / toc / content / cover / progress` 全表 + 令牌
⑥ 内置浏览器打开飞牛网页书架
⑦ 接入手册：同步接口、APK 接入、手机接入 API 三份文档

**4. 网络导入更宽容**
- 书源接口返回单个 JSON 对象也能导入（原来只认数组）
- `legado://import/bookSource?src=…` 直接粘进网络导入框也能用（自动取 `src` 再下载）
- `192.168.x.x:1122/api/m/…` 这类不带协议的地址自动补 `http://`
- 失败提示改为说清原因：返回网页（缺令牌/未登录）、返回空内容、返回的不是书源 JSON（附前 60 字）

**5. 应用内更新指向本仓库**
「我的 → 关于 → 检查更新」读 `TZYLXS/yuedu-pro` 的 Release：按 tag 里的 versionCode 比较（`v1.0.24005` → 24005），按设备 ABI 自动挑 `-arm64` / `-armv7` 包。

### 安装

1. 按设备选包：主流 64 位机装 `-arm64`，老 32 位设备装 `-armv7`
2. 允许「未知来源」→ 安装（同签名可覆盖升级）
3. 接飞牛书库：我的 → FnReader 服务器 → 填地址与令牌 → 同步 → ① 导入书源

### 产物信息

| 项 | armv7 | arm64 |
| --- | --- | --- |
| 文件 | `yuedu_pro_1.0.26.110805_vc24005-armv7.apk` | `yuedu_pro_1.0.26.110805_vc24005-arm64.apk` |
| ABI | armeabi-v7a | arm64-v8a |
| 体积 | 18,325,437 B（17.48 MB） | 18,370,405 B（17.52 MB） |
| SHA256 | `F922BDA51223639313213A5739BBEC77B0963B27671F90BEDAEC581A769E4516` | `E75ECD433D31B2838952DAF7B2C88263D1B2391B56369E76D78BF5A1F571F035` |

公共参数：包名 `io.legado.pro`、versionName `1.0.26.110805`、minSdk `24`、targetSdk `33`、compileSdk `34`、
签名证书 `CN=FnReader YueDu`（SHA-256 `81ce1c10476fa400ed962c4a18b54ae4497581320b1d940d896d62b6c609ef0f`）。

### 已知限制

- 未做真机复测（测试机 adb 未识别）
- 未做混淆 / 资源压缩，单包约 17.5 MB
- 应用内更新需要本仓库存在更高 versionCode 的 Release 才会提示
