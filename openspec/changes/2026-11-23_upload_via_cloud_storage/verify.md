# Verify：试卷图片走云开发存储直传

> **Change**：`2026-11-23_upload_via_cloud_storage`　**验证**：2026-11-23（单测 + 构建；真机待联调）

## 现象 → 结论 → 实现

| 现象 | 结论 | 实现 |
|---|---|---|
| `uni.uploadFile` → `uploadFile 合法域名校验出错` | 云托管默认域名被官方禁用于正式环境 | 不走公网域名 |
| 改 base64 + `callContainer` → `errCode: -606001` | callContainer 请求体上限 **100KB** | 不走 base64 |
| 云存储直传 | 无 100KB 限制、无域名白名单 | `wx.cloud.uploadFile` → `getTempFileURL` → 后端 `images/url` 下载 |

## 实现清单

| 层 | 文件 | 说明 |
|---|---|---|
| 下载器 | `src/main/java/com/jiang/client/CloudStorageImageDownloader.java`（新增） | https + 域名后缀白名单（防 SSRF）、10MB 上限、脱敏日志 |
| DTO | `src/main/java/com/jiang/dto/ExamImageUrlRequest.java`（新增） | url / contentType / pageNo |
| 控制器 | `ExamPaperController#uploadImageByUrl` | `POST /papers/{paperId}/images/url`；下载 → `InMemoryMultipartFile` → 复用 `uploadImage` |
| 小程序 | `utils/upload.ts` | `isCloudStorageUploadEnabled()` + `uploadExamImageViaCloudStorage()` |
| 小程序 | `api/exam.ts` | 云端走云存储直传；失败不再静默降级 |
| 小程序 | `ExamUpload.vue` | 通道文案/压缩目标/提示同步 |

## 测试

```
mvn -o '-Dtest=CloudStorageImageDownloaderTest,ExamPaperUrlUploadTest' test → 9 例通过
mvn -o test                                                            → 1048 通过 / 0 失败 / 0 跳过
cd student-app && npm run build:mp-weixin                               → DONE Build complete
产物核对：api/exam.js 含 images/url 与 isCloudStorageUploadEnabled；utils/upload.js 含 wx.cloud.uploadFile 与 getTempFileURL
```

| 用例 | 覆盖 |
|---|---|
| 下载器（7） | 受信域名成功、http 拒绝、非白名单拒绝（含 `tcb.qcloud.la.evil.com` 绕过）、空响应 1183、超 10MB 1184、下载异常 1183、自定义后缀 |
| 控制器（2） | 下载字节原样交 service、contentType 缺省与 pageNo 透传 |

## 未验证（真机）

| 项 | 说明 |
|---|---|
| 端到端上传 | 需部署新后端 + 上传新小程序后，在体验版（非真机调试）传一张试卷图，确认日志 `上传试卷图片(云存储): paperId=…, size=…` |
| 云存储可用性 | `wx.cloud.uploadFile` 依赖云开发存储已开通；若失败会在页面显示真实 errMsg |
| OCR 识别率 | 压缩到 2MB 后小字识别率需真实试卷比对 |
| 后端出网 | 云托管实例需能出网访问 `*.tcb.qcloud.la`（默认允许） |
| 存储清理 | `exam-upload/` 原始文件暂无生命周期清理 |

## 已知取舍

- 只有 URL 走 callContainer，体积几十字节，彻底避开 100KB 限制；
- 后端多一次下载（内网/公网带宽开销），换来"零域名依赖 + 不牺牲图片质量"；
- 若将来绑定备案域名，multipart 通道仍可用（代码保留）。