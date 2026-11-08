# 阅读Pro

> 交流群：778213624 / 339426057 / 1063542188

[Legado（阅读）](https://github.com/LegadoTeam/legado) 源码编译的 **Android 7.0+（API 24+）** 版本，
分 **armv7 / arm64** 两个产物，应用名「阅读Pro」，包名 `io.legado.pro`（可与官方「阅读」共存安装）。

同时提供「接入飞牛服务器」能力：把 [FnReader](https://github.com/TZYLXS/FnReader) 的书架 / 书源 / 正文直接读进来，
手机上不用再重复导入一堆书源。

> 需要 Android 4.4–5.1（API 19–22）的老设备，请用 [yuedu-android4](https://github.com/TZYLXS/yuedu-android4)。

## 下载与选包

| 产物 | 适用设备 | 说明 |
| --- | --- | --- |
| `yuedu_pro_app_1.8-arm64.apk` | 2017 年后的手机 / 平板 / 电视盒子（绝大多数） | arm64-v8a，64 位原生库 |
| `yuedu_pro_app_1.8-armv7.apk` | 老一些的 32 位设备、部分低端盒子 | armeabi-v7a，兼容性优先 |

不确定选哪个：先装 arm64，闪退或提示「应用未安装」再换 armv7。
两者 Java 侧完全一致，只差 APK 里打包的原生库（.so）。

安装：允许「未知来源」安装 → 直接装即可。包名独立，装了官方「阅读」也不用卸载。

## 参数

| 项 | 值 |
| --- | --- |
| 包名 / 应用名 | `io.legado.pro` / 阅读Pro |
| minSdk / targetSdk | 24 / 33 |
| versionCode | 10800（1.8） |
| 签名 | v1 (JAR) + v2 都在包里。`jarsigner -verify` 对 v1 报「jar 已验证」；`apksigner` 在 minSdk ≥ 24 时只校验 v2，会把 v1 显示成 false |
| 依赖底座 | Legado `3.23.110211` |

## 接入飞牛服务器（FnReader）

服务端是 [FnReader](https://github.com/TZYLXS/FnReader)：装在飞牛 fnOS 上的「阅读」，提供书架、书源、搜索与正文。
客户端不需要改一行代码——服务端会生成一份 Legado 书源，把「搜索 / 详情 / 目录 / 正文」映射到 `/api/m/*`。

### 1. 服务端装好并配好书源

按 [FnReader README](https://github.com/TZYLXS/FnReader/blob/main/README.md) 的「安装」一节装 fpk，
再在飞牛「阅读」里导入 Legado 书源（粘贴 / 链接 / 文件都行），点一次「检测」，把没通过的源停用。

### 2. 取接入链接

打开飞牛「阅读」→ 设置 → **「阅读 APK 接入」** 区块：

- **书源导入链接**：`legado://import/bookSource?src=http%3A%2F%2F<飞牛IP>%2Fapp%2Flegado-web%2Fapi%2Fm%2Fbooksource%3Ftoken%3D<令牌>`
- 也可以只取 **书源 JSON** 地址（粘贴同一地址到 App 也能导入）

令牌在「设置 → 手机接入」里；重置令牌后书源要重新导入。

### 3. 在阅读Pro里导入

阅读Pro → 我的 → 书源管理 → 右上角菜单 → **网络导入** → 粘贴上面的链接。
成功后书源列表会出现分组「飞牛」的书源「飞牛书库」。

### 4. 搜书与阅读

书 → 搜索 → 输入书名 → 结果来自飞牛上已配置的书源。
第一次点开不在飞牛书架里的书，服务端会自动按 `addedBy=apk` 入架；之后在飞牛网页端也能看到。

### 接口对应（排障用）

| 阅读Pro的动作 | 实际请求 |
| --- | --- |
| 搜索 | `GET /api/m/legado/search?token=…&key=…&page=…` |
| 详情 | `GET /api/m/legado/book?token=…&u=<b64url(bookUrl)>&s=<b64url(源)>&n=<书名>` |
| 目录 | `GET /api/m/legado/toc?token=…&u=<b64url(bookUrl)>` |
| 正文 | `GET /api/m/content?token=…&url=<bookUrl>&index=<i>` |
| 封面 | `GET /api/m/cover?token=…&url=<bookUrl>` |

### 客户端接入面板（我的 → 接入 FnReader 服务器，①–⑨）

服务端 1.11.0 起，App 里这个面板能把书架、进度、书源、缓存都双向对齐：

| 项 | 干什么 | 接口 |
| --- | --- | --- |
| ① | 导入书源（推荐接法） | `GET /api/m/sources.json` |
| ② | 全部同步到飞牛书架（新书自动建、重名跳过、封面一起回传） | `POST /api/m/books` + `/api/m/progress/batch` + `/api/m/cover` |
| ③ | 打开飞牛阅读网页 | 内置浏览器 |
| ④ | 接入手册 | 同步接口 / APK 接入 / 手机接入 API 三份文档 |
| ⑤ | 手机解析待命（飞牛跑不了的源派给本机解析） | `GET /api/m/remote/pending` + `POST /api/m/remote/result` |
| ⑥ | 接入书源桥（一个「飞牛书库」源读完所有） | 服务端生成的 Legado 源 |
| ⑦ | 查看软总线（哪台设备在代理解析） | `GET /api/bus` |
| ⑧ | 上传本机缓存到飞牛（网页离线读正文，1.11.0 §16） | `POST /api/m/cache`，传 `.nb` 原文，服务端转码落 `cache/<书名>/` |
| ⑨ | 书源双向同步（最后一次修改为准，1.11.0 §17） | 首次 `POST /api/m/sources` 合并；之后 `POST/GET /api/m/sources/sync` 按 `lastUpdateTime` 增量 |

⑧ 单次请求上限 30 MB（超了整本跳过），⑨ 首次只做合并上传、不推「全量 + since=0」（服务端会当成整库快照删源，
详见 `docs/app回传接受规范.md` §9）。

### 另外两种接法

- **WebDAV**：`http://<飞牛IP>:1122/dav/`，用户名任意、密码填访问令牌；`/dav/backup/` 可接阅读Pro 的「备份与恢复 → WebDAV」
- **HTTP API**：`GET /api/m/shelf | /toc | /content | /cover`、`GET /api/m/search?key=`、`POST /api/m/progress`，请求头 `X-Api-Token`

详见 FnReader 的 [docs/手机接入API.md](https://github.com/TZYLXS/FnReader/blob/main/docs/手机接入API.md) 与 [docs/APK接入.md](https://github.com/TZYLXS/FnReader/blob/main/docs/APK接入.md)。

## 与「阅读（API19 版）」的区别

| 项 | 阅读Pro | 阅读（yuedu-android4） |
| --- | --- | --- |
| 最低系统 | Android 7.0 / API 24 | Android 4.4 / API 19 |
| 包名 | `io.legado.pro` | `io.legado.api19` |
| ABI | arm64-v8a、armeabi-v7a 各一包 | 仅 armeabi-v7a |
| targetSdk | 33 | 30 |
| 4.4 兼容补丁 | 保留但不再触发（TLS1.2 兜底、TTS 三参、PFD 回退等） | 全局生效 |

两者可以同时装在一台设备上，书源与书架各自独立。

## 构建

需要 JDK 17、Android SDK（platform 34 / build-tools 34）、Gradle 8.3（wrapper 自动下载）。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleAppRelease -PtargetAbi=armv7
./gradlew :app:assembleAppRelease -PtargetAbi=arm64
```

产物在 `app/build/outputs/apk/app/release/`。签名用 `keystore/yuedu.jks`（**不入库**），
本地没有该文件时放一份并改 `app/build.gradle` 的 `signingConfigs`。

## 已验证 / 未验证

已验证：两个包编译通过；`aapt2 dump badging` 显示 `sdkVersion 24`、`targetSdkVersion 33`、包名 `io.legado.pro`、
标签「阅读Pro」、ABI 正确；`apksigner verify` v2 通过，`jarsigner -verify` v1 通过。

未验证：**真机运行**。测试机 adb 掉线，接上 USB 后可跑 `scripts/verify_device.bat`。

## 免责

本项目只提供阅读工具，不附带任何书籍内容，请自行确认所用书源的合法性。
底层 Legado 遵循其原项目许可，改动见 [docs/阅读Pro.md](docs/阅读Pro.md)。
