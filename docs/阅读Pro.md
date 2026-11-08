# 阅读Pro · 改动说明

底座：[LegadoTeam/legado](https://github.com/LegadoTeam/legado) tag `3.23.110211`
（commit `56c3301dc441c23aaa75bac973943f7469c202be`），不是当前 HEAD。

选这个 tag 的原因：它的依赖组合（AGP 8.1.2 / Gradle 8.3 / Kotlin 1.9.20 / media3 1.1.1 /
room 2.6.0 / okhttp 4.11.0 / compileSdk 34）在 JDK 17 上可稳定构建，且源码已按 targetSdk 33 适配。

## 相对 API19 版（yuedu-android4）的改动

| 位置 | 改动 |
| --- | --- |
| `app/build.gradle` | `applicationId` → `io.legado.pro`（与官方版、API19 版共存） |
| `app/build.gradle` | `minSdk 19 → 24`，`targetSdk 30 → 33` |
| `app/build.gradle` | 新增 `-PtargetAbi=armv7/arm64` 开关，决定 `abiFilters` 与产物名后缀 |
| `app/build.gradle` | `versionCode 19001 → 10800`（当前 1.8），输出名 `${appName}_${flavor}_${version}${abiSuffix}.apk` |
| `modules/book`、`modules/rhino1.7.3` | `minSdk 24`、`targetSdk 33`，与主模块一致 |
| `res/values*/strings.xml` | `app_name` → 阅读Pro（含 zh / zh-HK / zh-TW 三个语言目录） |
| `AndroidManifest.xml` | 删除 `<uses-sdk tools:overrideLibrary=...>`（三方库 minSdk 21 < 24，合并无需 override） |
| `README.md`、`docs/*` | 改为 Pro 版说明 |

## 从 API19 版继承、在 API24+ 上不再触发的补丁

这些代码保留但走不到，删了反而增加回归风险，因此原样留着：

- `help/http/Tls12SocketFactory.kt`：只在 `SDK_INT < 21` 时补 `TLSv1.2`
- `help/TTS.kt` 的 `speakCompat`：`SDK_INT >= 21` 走四参 `speak`
- `ui/file/HandleFileActivity.kt`：仅 `SDK_INT < LOLLIPOP` 回退 `FilePickerDialog`
- `modules/book/.../PfdHelper.java`：`@RequiresApi(21)` 分支优先，19 的 `FileInputStream` 回退不会进
- `App.kt` 的 `MultiDexApplication` + `multiDexEnabled true`：minSdk 24 本身原生支持 multidex，留着无害

同样继承下来的移除项：Cronet（整包删除，全部走 OkHttp）、Firebase（analytics/perf 依赖注释掉）。
如需恢复 Cronet，需要重新引入 `app/cronetlib/*.jar` 与 `download.gradle`，并接受 `Cronet_Version` 构建字段的取值来源。

## 签名

`keystore/yuedu.jks`（alias `yuedu`，storepass/keypass `yuedu2026`，JKS，36500 天），**不入库**。

APK 里同时有 `META-INF/CERT.SF` / `CERT.RSA`（v1）与 v2 签章：

- `jarsigner -verify` → 「jar 已验证」（自签证书会提示 PKIX 路径，属正常）
- `apksigner verify --verbose` → `v1 false / v2 true`。这是 apksigner 在 minSdk ≥ 24 时不再校验 v1 的表现，不是签名缺失

Android 7.0 起系统按 v2 校验，v1 只是给仍做 JAR 校验的老安装器留的兜底。

## 构建与验证结果

环境：JDK 17（`jdk-17.0.21+9`）、Android SDK platform 34 + build-tools 34.0.0、Gradle 8.3（wrapper）。

```bash
./gradlew :app:assembleAppRelease -PtargetAbi=armv7
./gradlew :app:assembleAppRelease -PtargetAbi=arm64
```

注意：同一 variant 连续构建会清空 `app/build/outputs/apk/app/release/`，
所以每打完一个 ABI 要立刻把 APK 拷到 `dist/`，否则第二个 ABI 会覆盖掉第一个。

产物：

| APK | 体积 | ABI | 校验 |
| --- | --- | --- | --- |
| `yuedu_pro_app_1.8-armv7.apk` | 17.51 MB | armeabi-v7a | `sdkVersion 24` / `targetSdkVersion 33` / `io.legado.pro` / v2 签名 |
| `yuedu_pro_app_1.8-arm64.apk` | 17.56 MB | arm64-v8a | 同上 |

原生库（每个 APK 只带自己那一套）：

- `lib/armeabi-v7a/` 或 `lib/arm64-v8a/`：`libarchive-jni`、`libimage_processing_util_jni`、`librenderscript-toolkit`

DEX：6 个（`classes.dex` ~8.6MB、`classes2.dex` 仅 2.9KB、`classes3.dex` ~0.46MB、`classes4.dex` ~8.3MB、
`classes5.dex` ~9.8MB、`classes6.dex` ~2.6MB），minSdk 24 由系统原生处理 multidex，不需要安装 multidex 补丁。

## 还没做的

- **真机运行验证**：测试机 adb 掉线，未实测启动、搜索、阅读。接上 USB 后跑 `scripts/verify_device.bat`。
- 应用内「检查更新」仍指向 Legado 官方仓库，换成本仓库的 Release 需要改 `AppUpdateService` 的地址。
- 未做混淆/资源压缩（`minifyEnabled false`），体积偏大，后续可按需开启。

## 1.8：origin 必须是书源真实地址（修「没有书源」）

1.7 有个真实事故：客户端加书时 `origin` 传空、`originName` 传 `手机端`，服务端靠 `book.origin` 取目录与正文，
结果网页端打开提示「没有书源」、目录空、正文读不出来。1.8 在 `ui/config/FnReaderConfigDialog.kt` 里补齐：

| 位置 | 改动 |
| --- | --- |
| 新增 `SourceIndex` / `buildSourceIndex()` | 由 `bookSourceDao.flowAll()` 建「`bookSourceUrl` → (地址, 源名)」和「host → 同宿主书源」两张表 |
| `SourceIndex.resolve(book)` | 三级匹配：书的 origin 已是真实源地址 → 用规范的地址+源名；按 `bookUrl` 的 host 命中（多源优先同名）；按源名唯一命中；全不中才留空 |
| `looksLikeSearchUrl()` | `/search`、`?q=`、`&q=`、`?wd=`、`?keyword=`、`/so/`、`s?ie=` 一律不是详情页，跳过不推 |
| `pushJson(book, index, cover)` | 推书的 `origin` = `bookSourceUrl`、`originName` = 源名；结果区报「非详情页跳过 N 本」「无匹配书源 N 本」 |
| `cacheJson(book, folder, files, index, cover)` | ⑧ 缓存补 `layout:"legado"`、`chapters[].file`、`chapters[].raw`（`.nb` 原文）、`origin`/`originName` |
| `fetchCoverData()` / `downloadCover()` / `fetchCoverBytes()` | 本机没有封面文件时现下（8s 超时、≤3 MB），`Referer` 先用图片域名、403 再退书源地址，转 base64 随书推 |
| `syncSourcesBoth()` | `currentUrls` / `goneUrls`（靠 `fnReaderSourcesSnapshot` 快照）+ `deleted[{bookSourceUrl,lastUpdateTime}]`；`sourceJson()` 给没时间戳的源补当前毫秒；`pull` 覆盖本地 |
| 文件级工具 | `ORIGIN_PLACEHOLDERS`（`""` / `手机端` / `本地` / `未知` / `导入` / `默认` / `loc_book` / `local` / `null` / `none`）、`isPlaceholderOrigin`、`normSourceUrl`、`hostOfUrl`、`looksLikeSearchUrl` |

实测（MuMu 模拟器 + FnReader 1.11.1/1.11.2）：9 本书 origin 全部等于书源 `bookSourceUrl`；
6 本目录/正文可读；⑧ 落盘 `layout: legado` + `raw` 原文；5 本封面落 NAS `covers/`；⑨ 双向改名生效。

## 1.7 起：FnReader 1.11.0 的两个新接入项


服务端 `docs/APP同步接口.md` 新增 §16 缓存同步、§17 书源双向同步，客户端在
`ui/config/FnReaderConfigDialog.kt` 里加了两项（菜单 ①–⑦ 不动，新增 ⑧⑨）：

| 方法 | 作用 | 要点 |
| --- | --- | --- |
| `uploadCache()` | ⑧ 把本机 `book_cache/<书名>/` 的 `.nb` 整本推给 `POST /api/m/cache` | `raw` 传原文；单本 > 30 MB 跳过；章节图片 1 B–2 MB 转 base64；读超时 60s |
| `cacheJson()` | 组 §16 载荷 | `index` 取文件名前 5 位，`title` 取本地章节表，`file` 原名 |
| `syncSourcesBoth()` | ⑨ 书源双向同步 | 首次 `POST /api/m/sources` 合并上传；之后按 `lastUpdateTime > since` 增量 POST/GET；`pull` 覆盖、`pullDeleted` 删除 |
| `mergePull()` / `applyPullDeleted()` | 落服务端更进 / 删除的书源 | 都按 `bookSourceUrl` |

首次同步为什么不用 `POST /api/m/sources/sync` 推全量：实测「本机全量 + `since: 0`」会被服务端当成整库快照，
删掉本机没有的源（1253 → 995，丢 258 条），只能靠 `backup/sources-*.json` + `POST /api/m/sources` 补回。
这条坑写进了 `docs/app回传接受规范.md` §9。
