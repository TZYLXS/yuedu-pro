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
| `yuedu_pro_app_2.0.3-arm64.apk` | 2017 年后的手机 / 平板 / 电视盒子（绝大多数） | arm64-v8a，64 位原生库 |
| `yuedu_pro_app_2.0.3-armv7.apk` | 老一些的 32 位设备、部分低端盒子 | armeabi-v7a，兼容性优先 |

不确定选哪个：先装 arm64，闪退或提示「应用未安装」再换 armv7。
两者 Java 侧完全一致，只差 APK 里打包的原生库（.so）。

安装：允许「未知来源」安装 → 直接装即可。包名独立，装了官方「阅读」也不用卸载。

## 参数

| 项 | 值 |
| --- | --- |
| 包名 / 应用名 | `io.legado.pro` / 阅读Pro |
| minSdk / targetSdk | 24 / 33 |
| versionCode | 20300（2.0.3） |
| 签名 | v1 (JAR) + v2 都在包里。`jarsigner -verify` 对 v1 报「jar 已验证」；`apksigner` 在 minSdk ≥ 24 时只校验 v2，会把 v1 显示成 false |
| 依赖底座 | Legado `3.23.110211` |

## 2.0 主线：跟飞牛服务端对齐

2.0.0 – 2.0.3 四轮一次性把书架与软总线捋顺了：

1. **书架同名去重**：同名（不同书源）的书只留一本，优先保留本地已有 `.nb` 缓存的那本，
   其余副本的阅读进度先并过去再删掉；推送与自动同步都按这个规则取书。
2. **删除墓碑**：用户手动删掉的书不会被自动同步补回来——同步时记住上一轮服务端推过的书，
   本地已删的记成墓碑，之后服务端再推同一本一律跳过（通知栏显示「跳过已删 N 本」）。
3. **⑧⑨ 转后台**：上传本机缓存、书源双向同步不再手点，连上服务端就每 5 分钟自动跑一轮；
   缓存只上传整本已下完的，通知栏汇总「上传 N 本 / 待命 M 本未下完」。
4. **软总线两条队列**（2.0.2）：`bookinfo`（简介与封面补全）单并发独立跑，
   search / toc / content / rank 走检索队列固定 2 并发；搜索被慢源拖住时，简介照样准时回传。
5. **`bookinfo` 超时 9 秒**（2.0.2）：对齐服务端 `docs/APP同步接口.md` §18.2 的「超时 10 秒」，
   手机先放手，服务端按时按原样返回，不再白等啃不动的详情页。
6. **启动页**换成「飞牛阅读」竖版毛笔字。
7. **目录分页**（2.0.3，仅跟进文档）：服务端 2.0.3 / 2.0.4 把目录引擎分页上限从 5 页放到 40 页、
   没写 `nextTocUrl` 时按页面「下一页」兜底，客户端一行代码没改；实测同一本单书源从「只回 50 章」
   变成一次回完全量目录（末条「第3139章 白骨王座（三更）」）。

### lean LEDE 更新日志与许愿（1.11 引入，仍在）

`coolsnowwolf/lede` 没有独立 CHANGELOG，它的「更新日志」就是 master 分支的提交历史。两件事：

- **内置订阅**：`https://github.com/coolsnowwolf/lede/commits/master.atom`（GitHub 官方 Atom 源，分组「固件」），
  「订阅」页右上角菜单与「我的」页都能进；内置 RSS 解析器已补 Atom（`<entry>` / `<link href>` / `<updated>`）支持。
- **给 lean 许愿（GitHub）**：`我的 → 给 lean 许愿（GitHub）`，输入框写愿望、点「提交 PR」，
  App 用你自己的 GitHub 账号 fork lede、建 `wish-<时间>` 分支、写 `wishlist/<时间>-<账号>.md`，再向上游开 PR；
  不想等 fork 可以点「改发 Issue」。Token 只存本机（SharedPreferences），不进备份、不写日志，退出登录立刻清除。

权限：classic token 勾 `public_repo`；细粒度 token 需要 `Contents` 读写 + `Pull requests` 读写，
另加 `Repository creation` 才能自动 fork（缺权限时界面直接回显 GitHub 原文与建议）。

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

### 客户端接入面板（我的 → 接入 FnReader 服务器，①–⑤）

服务端 2.0.x 起，这个面板负责书架、书源、软总线的对接；缓存上传与书源同步已改自动，不再占按钮：

| 项 | 干什么 | 接口 |
| --- | --- | --- |
| ① | 导入书源（推荐接法，含「飞牛书库」源） | `GET /api/m/sources.json` |
| ② | 全部同步到飞牛书架（新书自动建、重名跳过、封面一起回传） | `POST /api/m/books` + `/api/m/progress/batch` + `/api/m/cover` |
| ③ | 打开飞牛阅读网页 | 内置浏览器 |
| ④ | 接入手册 | 同步接口 / APK 接入 / 手机接入 API 三份文档 |
| ⑤ | 手机解析待命（飞牛跑不了的源派给本机解析） | `GET /api/m/remote/pending` + `POST /api/m/remote/result` |

后台自动动作（连上服务端即开启，每 5 分钟一轮，不再有 ⑧⑨ 按钮）：

- **缓存上传**：`POST /api/m/cache`，传 `.nb` 原文，服务端转码落 `cache/<书名>/`；单次请求上限 30 MB，超了整本跳过
- **书源双向同步**：首次 `POST /api/m/sources` 合并；之后 `POST/GET /api/m/sources/sync` 按 `lastUpdateTime` 增量，
  不推「全量 + since=0」（服务端会当成整库快照删源，详见 `docs/app回传接受规范.md` §9）

### 软总线任务类型

`GET /api/m/remote/pending` 派下来的任务由手机用本机书源规则跑完，再 `POST /api/m/remote/result` 回传：

| 类型 | 手机干什么 | 超时 |
| --- | --- | --- |
| `search` | 本机书源搜索；站点限流时用本机书架同源同名兜底 | 18 s |
| `toc` | 本机书源抓目录 | 18 s |
| `content` | 本机书源抓正文 | 23 s |
| `rank` | 本机书源抓榜单 | 23 s |
| `bookinfo` | 用本机书源详情规则补简介 / 封面 / 作者 / 分类 / 字数 / 最新章节 / 章数 | 9 s（服务端等 10 s） |

队列：`bookinfo` 单并发独立队列；其余四种走检索队列、2 并发。

## 与「阅读（API19 版）」的区别

| 项 | 阅读Pro | 阅读（yuedu-android4） |
| --- | --- | --- |
| 最低系统 | Android 7.0 / API 24 | Android 4.4 / API 19 |
| 包名 | `io.legado.pro` | `io.legado.api19` |
| ABI | arm64-v8a、armeabi-v7a 各一包 | 仅 armeabi-v7a |
| targetSdk | 33 | 30 |
| 4.4 兼容补丁 | 保留但不再触发（TLS1.2 兜底、TTS 三参、PFD 回退等） | 全局生效 |

两者可以同时装在一台设备上，书源与书架各自独立。两仓共用版本号（当前 2.0.3 / 20300）。

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
