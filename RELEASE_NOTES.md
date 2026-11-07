## 阅读Pro 1.0.26.110805（versionCode 24004）

Legado（阅读）`3.23.110211` 编译，**minSdk 24 / targetSdk 33**（Android 7.0+），分 **armv7** 与 **arm64** 两个产物。
应用名「阅读Pro」，包名 `io.legado.pro`，可与官方版、API19 版共存安装。

### 本版改动

**1. 设置里新增「接入 FnReader 服务器」入口**
我的 → 设置 → 其它设置 → FnReader 服务器 → 接入 FnReader 服务器。填服务器地址与访问令牌（自动补 `http://`、只填主机时补 `/app/legado-web`），可保存、测试连接，并列出 7 种接入方式：一键导入书源、复制书源 JSON 地址、复制 `legado://` 链接、WebDAV、HTTP API、内置浏览器网页书架、GitHub 接入文档。

**2. 修「网络导入」报格式不对**
- 书源接口返回**单个 JSON 对象**时也能导入（原来只认数组）
- 把 `legado://import/bookSource?src=...` 链接**直接粘进网络导入框**也能导入（自动取出 `src` 再下载）
- 只填 `192.168.x.x:1122/api/m/...` 这类不带协议的地址，自动补 `http://`
- 失败提示不再只说「格式不对」：会区分「返回的是网页（多为缺令牌/未登录）」「返回空内容」「返回的不是书源 JSON（附前 60 字）」

**3. 应用内更新指向本仓库**
「我的 → 关于 → 检查更新」读 `TZYLXS/yuedu-pro` 的 Release：按 tag 里的 versionCode 比较（`v1.0.24004` → 24004），按设备 ABI 自动挑 `-arm64` / `-armv7` 包。

### 安装

1. 按设备选包：主流 64 位机装 `-arm64`，老 32 位设备装 `-armv7`
2. 允许「未知来源」→ 安装（同签名可覆盖升级）
3. 接飞牛书库：设置 → 其它设置 → 接入 FnReader 服务器，填地址与令牌后一键导入书源

### 产物信息

| 项 | armv7 | arm64 |
| --- | --- | --- |
| 文件 | `yuedu_pro_1.0.26.110805_vc24004-armv7.apk` | `yuedu_pro_1.0.26.110805_vc24004-arm64.apk` |
| ABI | armeabi-v7a | arm64-v8a |
| 体积 | 18,319,737 B（17.47 MB） | 18,364,726 B（17.51 MB） |
| SHA256 | `419C677B72F86A1C61523E6D7E97D22E1AED118B6769C2008E8B671D8E3002D7` | `A66C81CA16382DE4B4B3421F7065615C3776BFB3B989FDA0A266ED32D936E3AD` |

公共参数：包名 `io.legado.pro`、versionName `1.0.26.110805`、minSdk `24`、targetSdk `33`、compileSdk `34`、
签名证书 `CN=FnReader YueDu`（SHA-256 `81ce1c10476fa400ed962c4a18b54ae4497581320b1d940d896d62b6c609ef0f`）。

### 已知限制

- 未做真机复测（测试机 adb 未识别）
- 未做混淆 / 资源压缩，单包约 17.5 MB
- 应用内更新需要本仓库存在更高 versionCode 的 Release 才会提示
