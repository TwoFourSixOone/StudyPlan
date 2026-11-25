# Verify：试卷图片上传走 cloud callContainer（base64）

> **Change**：`2026-11-23_upload_via_call_container`　**验证**：2026-11-23（单测 + 构建；真机待联调）

## 现象 → 决策 → 实现

| 现象 | 结论 | 实现 |
|---|---|---|
| 小程序后台拒绝把云托管默认域名 `*.sh.run.tcloudbase.com` 配为合法域名（提示"仅用作测试使用，不可用在正式环境下"） | 无法用 `uni.uploadFile`（公网 HTTPS）在正式环境上传 | 上传改走 `wx.cloud.callContainer`（微信内网通道，不需要域名白名单），图片以 base64 放进 JSON body |
| 普通接口一直正常 | 它本来就走 callContainer | 复用同一通道，无新增依赖 |
| 真机调试能成功 | 调试通道不校验白名单 | 本次改动后正式/体验版也走内网通道，不再依赖白名单 |

## 实现清单

| 层 | 文件 | 说明 |
|---|---|---|
| 工具 | `src/main/java/com/jiang/common/util/InMemoryMultipartFile.java`（新增） | `byte[]` → `MultipartFile` 适配，复用既有校验链 |
| DTO | `src/main/java/com/jiang/dto/ExamImageBase64Request.java`（新增） | `dataBase64` / `contentType`（缺省 jpeg）/ `pageNo` |
| 控制器 | `ExamPaperController#uploadImageBase64` | `POST /papers/{paperId}/images/base64`；MIME 解码；>10MB 前置 1184；日志带 `(base64)` 标识 |
| 小程序 | `utils/upload.ts` | `isCloudContainerEnabled()` + `uploadImageViaContainer()`（readFile base64 → callContainer，带 `X-WX-SERVICE`） |
| 小程序 | `api/exam.ts#uploadPaperImage` | 云端走 base64 通道；容器不可用/无业务码失败 → 降级 multipart |
| 小程序 | `ExamUpload.vue` | callContainer 通道压缩目标 900KB（base64≈1.2MB）；超限提示区分通道 |

## 测试

```
mvn -o '-Dtest=ExamPaperBase64UploadTest' test → Tests run: 6, Failures: 0, Errors: 0
mvn -o test                                   → Tests run: 1045, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS
cd student-app && npm run build:mp-weixin      → DONE Build complete
```

| 用例 | 覆盖 |
|---|---|
| 正常 base64 | 解码字节与原始一致、contentType 透传、pageNo 透传 |
| contentType 缺省 | 按 `image/jpeg` 交给 service（最终仍由魔数校验兜底） |
| 空 / 非法 base64 | 抛 `EXAM_IMAGE_EMPTY(1183)`，不进业务层 |
| 超大 base64 | 解码前按长度粗判 → `EXAM_IMAGE_TOO_LARGE(1184)` |
| 含换行 base64 | MIME 解码正确 |

## 未验证（真机）

| 项 | 说明 |
|---|---|
| 云托管真机上传 | 需部署后在小程序体验版/正式版（**不要用真机调试**）上传一张图，确认云端日志出现 `上传试卷图片(base64): paperId=..., size=...` |
| 请求体上限 | base64 目标 900KB（≈1.2MB 传输）为保守值；若仍被云托管网关拒绝，降到 700KB（`CONTAINER_SAFE_BYTES` 常量） |
| OCR 识别率 | 900KB 压缩后小字是否仍可识别，需真实试卷比对；若明显下降，考虑"云开发存储直传"方案（另立 change） |
| 8 张并发 | callContainer 并发上传 8 张的内存/耗时表现未实测 |

## 已知取舍

- **base64 膨胀 33%**：因此压缩目标比 multipart 通道更小（900KB vs 1.8MB），识别率略降的风险由真机验证兜底；
- **内存占用**：单张 ≤900KB 原始（≈1.2MB base64），小程序端与后端各持一份，可接受；
- **双通道并存**：若将来绑定备案域名，multipart 通道仍可用（降级顺序不变），无需回滚代码。
