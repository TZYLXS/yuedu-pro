## 阅读Pro 1.0.26.110805（versionCode 24003）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。

### 本版改动

**1. 设置里新增「接入 FnReader 服务器」入口**
我的 → 设置 → 其它设置 → FnReader 服务器 → 接入 FnReader 服务器。弹窗里填服务器地址与访问令牌（自动补 `http://`、只填主机时补 `/app/legado-web`），可保存、测试连接，并列出 7 种接入方式：

1. 一键导入书源（推荐，直接拉起导入确认）
2. 复制书源 JSON 地址
3. 复制 `legado://import/bookSource?src=...` 链接
4. WebDAV（`http://主机:1122/dav/`，books 只读、backup 可接备份）
5. HTTP API（`/api/m/` 接口表 + 令牌）
6. App 内置浏览器打开飞牛网页书架
7. 打开 GitHub 接入文档

**2. 修复「网络导入」报 `格式不对`**
网络导入原先只认 JSON 数组，FnReader 这类自建服务返回单个书源对象时会失败；现在数组与单对象都接受。
注意：`legado://` 链接要**点开**用，粘进「网络导入」输入框仍会提示格式不对。

**3. 应用内更新指向本仓库**
「我的 → 关于 → 检查更新」读 `TZYLXS/yuedu-pro` 的 Release：按 tag 里的 versionCode 比较（`v1.0.24003` → 24003），并按设备 ABI 自动挑 `-arm64` / `-armv7` 包。原来读官方 `gedoor/legado` 且按 tag 字符串比较，对自定义 tag 不成立。

### 安装

1. 按设备选包：主流 64 位机装 `-arm64`，老 32 位设备装 `-armv7`
2. 允许「未知来源」→ 安装（同签名可覆盖升级）
3. 接飞牛书库：设置 → 其它设置 → 接入 FnReader 服务器，填地址与令牌后一键导入书源

### 产物信息

| 项 | armv7 | arm64 |
| --- | --- | --- |
| 文件 | `yuedu_pro_1.0.26.110805_vc24003-armv7.apk` | `yuedu_pro_1.0.26.110805_vc24003-arm64.apk` |
| ABI | armeabi-v7a | arm64-v8a |
| 体积 | 18,317,859 B（17.47 MB） | 18,362,851 B（17.51 MB） |
| SHA256 | `A04C89D079F76D1B894E907EE74B7FB2C3F0ABF6AF8EEBA9C759F71042936264` | `BA346FF7D2B04A9A8A8D4854CF0FB94C3FBBFAE73F4DAA3D257963698D87E456` |

公共参数：包名 `io.legado.pro`、versionName `1.0.26.110805`、minSdk `24`、targetSdk `33`、compileSdk `34`、
签名证书 `CN=FnReader YueDu`（SHA-256 `81ce1c10476fa400ed962c4a18b54ae4497581320b1d940d896d62b6c609ef0f`）。

### 已知限制

- 未做真机复测（测试机 adb 未识别）；接上设备后可跑 `scripts/verify_device.bat`
- 未做混淆 / 资源压缩，单包约 17.5 MB
- 应用内更新需要本仓库存在更高 versionCode 的 Release 才会提示
