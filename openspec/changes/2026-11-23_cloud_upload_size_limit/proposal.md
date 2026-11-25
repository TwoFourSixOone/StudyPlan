# Proposal：云端上传按 2MB 请求体上限自动压缩试卷图片

> **Change**：`2026-11-23_cloud_upload_size_limit`

## 现象（2026-11-23 真机）

- 微信开发者工具**直连本地后端**上传试卷图片：正常；
- 部署到**云端（云托管公网域名）**后上传失败，提示与大小相关（客户端侧"图片超过 2MB"量级）。

## 根因

1. 上传走 `uni.uploadFile` → 云托管**公网域名**（`VITE_CLOUD_BASE_URL`），云托管入口对单次 HTTP 请求体有上限
   （约 **2MB** 量级，含 multipart 头/boundary 开销）；本地直连后端没有该限制。
   > 参考：云托管 `callContainer` 的 params 上限 100KB（[官方 FAQ](https://developers.weixin.qq.com/doc/knowledge/c654547ebbebbd89.html)）；
   > 公网入口的请求体限制更宽但仍远小于后端自定的 9.5MB。
2. 前端压缩策略是「**>800KB 才压一次 quality 85**」，且上限判定用的是后端 9.5MB ——
   于是 2MB 以上的压缩结果照样发出去，在云端被网关层直接拒掉（后端日志里看不到请求）。

## 方案

| 层 | 改动 |
|---|---|
| 小程序（`ExamUpload.vue`） | 新增**云端模式**判定（与 `utils/upload.ts` 同一套 `VITE_USE_LOCAL`/`DEV` 解析）；云端模式把每张图**多档压缩**（quality 85 → 70 → 55 → 45）直到 ≤ **1.8MB**（留 multipart 余量）；仍超限给出可操作提示「请裁剪或重新拍摄后重试」，不再把超限请求发出去 |
| 小程序 | `compressImage(src, quality)` 支持指定质量；`SIZE_TIP` 在云端模式显示「云端单次上传上限约 2MB：会自动压缩」 |
| 本地模式 | **行为不变**（仅 >800KB 才压一次 quality 85，保住试卷小字识别率） |
| 后端 | 不改：本地 multipart 10MB / 业务 9.5MB 保持不变 |

## 不在本次范围

- **分片上传 / 直传对象存储（COS）**：彻底绕开网关请求体限制，但需改造上传协议与后端接收逻辑，另立 change；
- 图片 resize（会降低 OCR 识别率，本次只压质量不减分辨率）；
- 其它上传入口（如 admin 端数据导入是不走小程序的上传，另议）。

## 验收

- 云端模式：≥2MB 原图 → 上传前被压到 ≤1.8MB 并成功上传解析；压缩后仍超限 → 明确提示且不发请求；
- 本地模式：与之前一致（>800KB 压一次 quality 85，9.5MB 上限判定不变）；
- `student-app build:mp-weixin` 通过；后端全量 `mvn -o test` 不受影响。
