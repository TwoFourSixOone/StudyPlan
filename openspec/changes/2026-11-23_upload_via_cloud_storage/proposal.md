# Proposal：试卷图片走云开发存储直传，绕开 uploadFile 域名与 callContainer 100KB 限制

> **Change**：`2026-11-23_upload_via_cloud_storage`
> （前一版方案 `2026-11-23_upload_via_call_container` 已废弃：callContainer 请求体上限 100KB，见下）

## 现象（真机/开发者工具实测）

1. `uni.uploadFile` 走云托管公网域名 → `uploadFile 合法域名校验出错`；把该域名填进小程序后台被官方拒绝：
   「云托管域名仅用作测试使用，不可用在正式环境下」；
2. 改走 `wx.cloud.callContainer` + base64 → 返回 **`errCode: -606001`**
   （[官方 FAQ：请求包大小超过 100KB](https://developers.weixin.qq.com/doc/knowledge/c654547ebbebbd89.html)），
   ~1.2MB 的 base64 必然失败 → 该方案对小图片可行、对试卷照片不可行。

## 方案（本次）

```
小程序                    云开发存储              后端
wx.cloud.uploadFile  →   fileID  →  wx.cloud.getTempFileURL
                                     ↓ 临时 HTTPS URL（几十字节，走 callContainer）
                              POST /api/student/exam/papers/{paperId}/images/url
                                     ↓ 服务端下载 → 既有校验 → 落盘 → 页序追加
```

- 云存储上传**不受 100KB 限制**，也**不需要 uploadFile 合法域名**；
- 传给后端的只有 URL，callContainer 请求体远低于 100KB；
- 后端下载后复用**原有**校验链（归属/张数/累计大小/类型魔数/分辨率/落盘/页序），不新增业务逻辑；
- 本地开发仍走 multipart（`VITE_USE_LOCAL=true`），行为不变。

## 安全约束（防 SSRF）

- 只接受 `https`；
- 域名必须命中白名单后缀（默认 `tcb.qcloud.la,qcloud.la,myqcloud.com,tcloudbase.com`，可用
  `exam.upload.allowed-url-suffixes` 覆盖）；
- 下载体积上限 10MB，超限拒绝；
- 日志对 URL 脱敏（只打 host + 路径前缀）。

## 不在本次范围

- 购买/备案域名后改用 uploadFile（仍保留 multipart 通道，配置域名即可用）；
- 云存储文件的生命周期清理（临时文件保留策略另议）；
- 存量图片迁移。

## 验收

- 云端真机（体验版/正式版，**非真机调试**）上传试卷图片成功，云端日志出现
  `上传试卷图片(云存储): paperId=…, size=…`；
- 本地模式行为不变；
- 非白名单域名 / http / 超大文件被拒绝（对应错误码）；
- `mvn -o test` 与 `npm run build:mp-weixin` 通过。