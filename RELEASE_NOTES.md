# 阅读Pro 3.4.0.1

同步 FnReader `v3.4.0`（main `d2e059f`，2026-11-27）。两仓共用版号 `3.4.0.1` / versionCode `3400001`。

## 本版性质
仅同步服务端版本，**无客户端代码改动**：`docs/APP同步接口.md`、`docs/软总线路由与权重.md`、`docs/AI构建指南.md` 与 `3.3.5` 完全一致，`/api/m/*` 契约未变。

## 服务端 3.4.0 变化
- 随包内置 Node 运行时 v22.12.0（按 `bin/node` 布局优先使用），manifest 去掉 `nodejs_v26` 依赖，彻底避免「未找到 node.js v26」
- 正文兜底与替换规则误伤保护：替换后正文明显缩水（<55%）时本章回退原文
- 封面链路：客户端封面优先，按请求 Host 返回绝对地址，死链过滤
- 书源同步：分页 `nextSince` 与类型/布尔规范化
- 软总线：设备计数与负载均衡、搜索优先手机与分批扫描

## 客户端沿用能力（3.3.5.1 已落地）
- 书源同步分页循环（`nextSince`/`truncated`，最多 10 轮）
- `pull` 字段级合并：规则取服务端，`enabled`/`enabledExplore`/`enabledCookieJar`/`customOrder`/`respondTime`/`bookSourceGroup` 保留本地，`lastUpdateTime` 取较大值
- 本地书回传：`/api/m/books`、`/api/m/progress`、`/api/m/progress/batch` 带 `localFile=true` 与 `fileName`，供服务端扫 ebook 目录绑定

## 产物
- `yuedu_pro_app_3.4.0.1-arm64.apk`（arm64-v8a）
- `yuedu_pro_app_3.4.0.1-armv7.apk`（armeabi-v7a）

包名 `io.legado.pro`，minSdk 24 / targetSdk 33，正式签名（v2），无 debuggable。
