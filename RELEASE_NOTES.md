# 阅读Pro 3.4.2.2

修复应用内「检查更新」永久提示「已是最新版本」。两仓共用版号 `3.4.2.2` / versionCode `3400202`，服务端仍为 FnReader `3.4.2`（接口契约未变）。

## 问题现象
- 3.2.0.1、3.3.5.1 等版本点「检查更新」，Toast 恒为「已是最新版本」，而 GitHub 最新 Release 已是 `v3.4.2.1`
- MuMu 装 3.2.0.1 原包实测复现；`pm clear` 清空数据后仍复现，排除 HTTP 缓存因素

## 根因
`AppUpdateGitHub.versionCodeOf(tag)` 把 tag 的次版本按 `*10000` 计算，而 `app/build.gradle` 的 versionCode 次版本按 `*100000`：

| tag | 解析出的 code | 真实 versionCode |
| --- | --- | --- |
| v3.4.2.1 | 3 040 201 | 3 400 201 |
| v3.2.0.1 | 3 020 001 | 3 200 001 |
| v3.0.10.1 | 3 031 001 | 3 001 001 |

解析值恒小于当前 versionCode，任何新版本都被判成「更旧」，于是永远提示已是最新。

## 修复
- 解析公式统一为 `大版本*1000000 + 次版本*100000 + 修订*100 + 构建`（三段 tag 按修订位补齐），与 `build.gradle` 完全同口径
- Pro 与 A4 同步修改；A4 自有差异（`/app/legado-web`、`rawServer` 判 `/app/`、`serverBase` path 分支、`negativeButton` 文案）未动
- 验证：用同版本 json-path 2.8.0 复算，`versionCodeOf("v3.4.2.1") = 3400201`、`3.0.10.1 = 3001001`，与历史 versionCode 全部对得上

## 注意
已装 3.4.2.1 及更早版本的机器，因旧代码公式错误无法通过应用内更新升到本版，需手动下载一次安装包；从本版起自更新恢复正常。

## 产物
- `yuedu_pro_app_3.4.2.2-arm64.apk`（arm64-v8a）
- `yuedu_pro_app_3.4.2.2-armv7.apk`（armeabi-v7a）

包名 `io.legado.pro`，minSdk 24 / targetSdk 33，正式签名（v2），无 debuggable。
