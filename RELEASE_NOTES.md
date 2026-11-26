# 阅读Pro 3.4.2.1

同步 FnReader `v3.4.1`（main `fa03949`，线上服务端实际运行 `3.4.2`，API 与 3.4.1 一致）。两仓共用版号 `3.4.2.1` / versionCode `3400201`。

## 跟进的协议变更（§17.2.2 本地小说文件绑定，3.4.1+）
- `/api/m/books` 与 `/api/m/progress/batch` 都接受 `localFile=true` 或非 http 的 `bookUrl`，按 `fileName || name` 查 ebook 索引（忽略扩展名/书名号，先精确后模糊）
- 命中 → 绑 `ebook://<sha1>`（`localEbook{sha1,format,name,status}`、`contentSource=local`、`tocUrl=ebook://sha1`，目录取本地解析结果、进度用本次上报值）
- 未命中 → 登记 `local://<normBookName>` pending（带 `sourceFileName`），电子书扫描完成后 12 秒内二次联动，命中同名电子书自动升级为 `ebook://` 并保留进度
- 响应 `{ok, added, updated, bound, pending, skippedDeleted, total}`；`bound` 为本轮绑定成功数、`pending` 为待扫描数

## 客户端改动
- `FnReaderAutoSync.pushBooks` / `pushProgress` 返回值改为 `[added|updated, bound, pending]`，回传摘要新增「已绑定 N 本、待扫描 M 本」
- 老服务端（≤3.4.0）不返回这两个字段，按缺省 0 处理，原有回传逻辑不变
- 本地书 `bookUrl` 继续用 `file://`：服务端每次按 `fileName` 重新绑定，幂等不重复建书
- 书源同步分页（`nextSince`/`truncated`）与 `pull` 字段级合并沿用 3.3.5.1

## 服务端 3.4.0/3.4.2 同期变化
- 内置 Node 运行时 v22.12.0（按 `bin/node` 布局优先），去掉 `nodejs_v26` 依赖
- 正文替换误伤保护：替换后正文缩水 <55% 时本章回退原文
- 封面客户端优先、死链过滤；书源同步分页与类型规范化；软总线设备计数与负载均衡

## 产物
- `yuedu_pro_app_3.4.2.1-arm64.apk`（arm64-v8a）
- `yuedu_pro_app_3.4.2.1-armv7.apk`（armeabi-v7a）

包名 `io.legado.pro`，minSdk 24 / targetSdk 33，正式签名（v2），无 debuggable。
