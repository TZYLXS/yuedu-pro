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
| `app/build.gradle` | `versionCode 19001 → 24001`，输出名 `${appName}_${flavor}_${version}${abiSuffix}.apk` |
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
| `yuedu_pro_app_1.0.26.110803-armv7.apk` | 17.45 MB | armeabi-v7a | `sdkVersion 24` / `targetSdkVersion 33` / `io.legado.pro` / v2 签名 |
| `yuedu_pro_app_1.0.26.110803-arm64.apk` | 17.50 MB | arm64-v8a | 同上 |

原生库（每个 APK 只带自己那一套）：

- `lib/armeabi-v7a/` 或 `lib/arm64-v8a/`：`libarchive-jni`、`libimage_processing_util_jni`、`librenderscript-toolkit`

DEX：6 个（`classes.dex` ~8.6MB、`classes2.dex` 仅 2.9KB、`classes3.dex` ~0.46MB、`classes4.dex` ~8.3MB、
`classes5.dex` ~9.8MB、`classes6.dex` ~2.6MB），minSdk 24 由系统原生处理 multidex，不需要安装 multidex 补丁。

## 还没做的

- **真机运行验证**：测试机 adb 掉线，未实测启动、搜索、阅读。接上 USB 后跑 `scripts/verify_device.bat`。
- 应用内「检查更新」仍指向 Legado 官方仓库，换成本仓库的 Release 需要改 `AppUpdateService` 的地址。
- 未做混淆/资源压缩（`minifyEnabled false`），体积偏大，后续可按需开启。
