## 阅读Pro 1.0 · Android 7.0+ 首版

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**，分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。

### 安装

1. 按设备选包：主流 64 位机装 `-arm64`，老 32 位设备装 `-armv7`
2. 允许「未知来源」→ 安装
3. 打开 → 我的 → 书源管理 → 网络导入（可选，接飞牛书库用）

### 产物信息

| 项 | armv7 | arm64 |
| --- | --- | --- |
| 文件 | `yuedu_pro_app_1.0.26.110803-armv7.apk` | `yuedu_pro_app_1.0.26.110803-arm64.apk` |
| ABI | armeabi-v7a | arm64-v8a |
| 体积 | 17.45 MB | 17.50 MB |
| SHA256 | `CDFC296CE074EE03EFAB7FE432A44D7372670C00000CEF949A6B68E798AF595C` | `1A35BED550FF2E82B92B23F12BEFA6A21358435E4CDD8A31D583F295F11056F6` |

公共参数：包名 `io.legado.pro`、versionCode `24001`、versionName `1.0.26.110803`、minSdk `24`、targetSdk `33`、
v1 (JAR) + v2 签名（`jarsigner -verify` 校验 v1 通过，`apksigner verify` 校验 v2 通过）。

### 主要改动（相对 API19 版）

- 构建：`applicationId` → `io.legado.pro`、`minSdk 19 → 24`、`targetSdk 30 → 33`、`versionCode 24001`
- 新增 `-PtargetAbi=armv7|arm64`，产物名带 `-armv7` / `-arm64` 后缀，一 ABI 一包
- `modules/book`、`modules/rhino1.7.3` 的 minSdk / targetSdk 同步上调
- 应用名四处语言资源统一为「阅读Pro / 閲讀Pro / 閱讀Pro」
- 清单去掉 `tools:overrideLibrary`（三方库 minSdk 21 < 24，无需 override）
- API19 的兼容补丁（TLS1.2 兜底、TTS 三参、PFD 回退、目录选择回退）保留但不再触发

### 接飞牛书库

配合 [FnReader](https://github.com/TZYLXS/FnReader) 1.7.1+：飞牛「阅读」设置页 →「阅读 APK 接入」→ 复制书源链接，
在本 APK 里网络导入即可搜书、读正文。详见 [docs/飞牛书库接入.md](docs/飞牛书库接入.md)。

### 已知限制

- **未做真机复测**：测试机 adb 掉线。接 USB 后跑 `scripts/verify_device.bat` 可安装并抓启动日志
- 未做混淆 / 资源压缩，单包 17.5MB 偏大
- 应用内「检查更新」仍指向 Legado 官方仓库，不会提示本仓库的版本
