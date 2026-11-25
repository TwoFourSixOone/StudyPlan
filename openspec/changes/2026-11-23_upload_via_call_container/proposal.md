# Proposal：试卷图片上传走云托管 callContainer（base64），绕开域名白名单

> **Change**：`2026-11-23_upload_via_call_container`

## 背景

云托管**默认域名**（`*.sh.run.tcloudbase.com`）被小程序后台明确拒绝：

> 云托管域名仅用作测试使用，不可用在正式环境下，请修改。

因此 `uni.uploadFile`（走公网 HTTPS 域名）在预览/体验版/正式版的真机上无法使用；
而同一套接口的**普通请求**一直正常——因为它走的是 `wx.cloud.callContainer`（微信内网通道，**不需要域名白名单**）。
真机调试能成功也是同一原因（调试通道不校验白名单）。

要立即可用又不去买/备案域名，最直接的方案：**把图片上传也走 `callContainer`**，以 base64 放进 JSON body。

## 方案

| 端 | 改动 |
|---|---|
| 小程序 | 云端模式（`USE_LOCAL=false` 且 `CLOUD_ENV`+`CLOUD_SERVICE` 已配置）下：读图片为 base64 → `wx.cloud.callContainer` POST `/api/student/exam/papers/{paperId}/images/base64`；本地模式仍走原 `uni.uploadFile` multipart |
| 小程序 | base64 目标大小收紧到 **原始 ≤ 900KB**（base64 约 1.2MB），压缩梯度 85→70→55→45 |
| 后端 | 新增 `POST /api/student/exam/papers/{paperId}/images/base64`：base64 解码 → 用 `InMemoryMultipartFile` 适配成 `MultipartFile` → **复用现有 `ExamPaperService#uploadImage`**（归属校验 / 张数 / 累计大小 / 类型魔数 / 分辨率 / 落盘全都不变） |
| 降级 | `callContainer` 不可用（未配置 env/service 或调用失败）→ 回退 `uni.uploadFile`（若后续配了合法域名，这条路仍可用） |

## 不在本次范围

- 购买/备案域名（用户选择先不配）；
- 云开发存储直传（`wx.cloud.uploadFile` + 后端下载）——如 base64 受请求体上限影响，再做该方案；
- 其它上传入口（admin 端导入不走小程序）。

## 验收

- 云端模式：真机（预览/体验版，非真机调试）上传试卷图片成功，云端日志出现 `上传试卷图片(base64): paperId=..., size=...`；
- 本地模式：行为不变（multipart 直连本地）；
- base64 非法 / 超限 → 明确错误码（`1139`/`1184`），不落盘；
- `mvn -o test` 与 `student-app build:mp-weixin` 通过。
