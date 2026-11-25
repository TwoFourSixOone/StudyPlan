# Design：试卷图片走云开发存储直传

> **Change**：`2026-11-23_upload_via_cloud_storage`

## D1 三条通道的实测结论

| 通道 | 白名单 | 请求体限制 | 结论 |
|---|---|---|---|
| `uni.uploadFile` → 云托管公网域名 | 需要，且**默认域名被官方禁用于正式环境** | 网关 ~2MB | ❌ 正式环境不可用 |
| `wx.cloud.callContainer` + base64 | 不需要 | **100KB**（`-606001`） | ❌ 图片必然超限 |
| **`wx.cloud.uploadFile`（云存储）+ 后端下载** | 不需要 | 无（走存储通道） | ✅ 本次采用 |

## D2 接口契约

```
POST /api/student/exam/papers/{paperId}/images/url
Content-Type: application/json
Authorization: Bearer <student token>

{ "url": "https://<云存储临时地址>", "contentType": "image/jpeg", "pageNo": 1 }

200 → { code:200, data: ExamImageUploadVO }   // 与 multipart 版一致
1139 → URL 非 https / 非白名单域名 / 非图片类型
1183 → 下载为空或下载失败
1184 → 超过 10MB 下载上限 / 超过单张业务上限 9.5MB
1185 → 累计超过 30MB
```

## D3 后端实现

- `CloudStorageImageDownloader`（`com.jiang.client`）：
  `ensureTrustedUrl`（https + host 后缀白名单）→ `RestTemplate` GET byte[] → 空/超 10MB 抛业务异常；
  日志脱敏；异常不外泄底层细节；
- `ExamPaperController#uploadImageByUrl`：下载字节 → `InMemoryMultipartFile` 适配 →
  **原样调用** `ExamPaperService#uploadImage`（校验链、分布式锁、落盘、页序追加全部复用）；
- 日志：`上传试卷图片(云存储): paperId=…, size=…, contentType=…, pageNo=…`（与 multipart 版可区分）。

## D4 小程序实现

- `utils/upload.ts`：
  - `isCloudStorageUploadEnabled()`：`!USE_LOCAL && CLOUD_ENV && wx.cloud.uploadFile`；
  - `uploadExamImageViaCloudStorage(url, filePath, pageNo)`：
    `wx.cloud.uploadFile({cloudPath})` → `wx.cloud.getTempFileURL({fileList})` →
    `wx.cloud.callContainer({path: '/api…/images/url', data: JSON.stringify({url, contentType, pageNo})})`；
- `api/exam.ts#uploadPaperImage`：云端优先云存储直传；失败**不再静默降级**（降级只会撞域名白名单），
  直接把 errMsg 抛给页面显示；
- `ExamUpload.vue`：页面显示「上传通道：云端云存储直传」；云存储通道压缩目标 2MB（multipart 1.8MB，本地按 800KB 阈值）。

## D5 风险与取舍

- **临时 URL 有效期**：`getTempFileURL` 默认 2 小时，后端在收到请求后立即下载，无过期风险；
- **下载带宽**：后端需能出网访问云存储（云托管环境默认允许出网）；
- **云存储残留**：上传到 `exam-upload/` 的原始文件暂不清理（可后续加生命周期规则）；
- **依赖云开发环境**：需在小程序侧启用云开发并配置 `VITE_CLOUD_ENV`（本项目已配置，普通接口的 callContainer 一直在用）。

## D6 测试

| 层 | 用例 |
|---|---|
| `CloudStorageImageDownloaderTest`（7） | 受信域名下载成功、http 拒绝、非白名单拒绝（含相似域名绕过）、空响应 1183、超 10MB 1184、下载异常 1183、自定义后缀 |
| `ExamPaperUrlUploadTest`（2） | 下载字节原样交 service、contentType 缺省 jpeg 与 pageNo 透传 |
| 回归 | `mvn -o test` 全量；`npm run build:mp-weixin` |