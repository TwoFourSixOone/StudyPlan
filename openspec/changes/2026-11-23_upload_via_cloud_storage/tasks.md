# Tasks：试卷图片走云开发存储直传

> **Change**：`2026-11-23_upload_via_cloud_storage`　**状态**：已实施（2026-11-23）

## T0 方案变更记录

- [x] 前版 `upload_via_call_container`（base64）废弃：真机返回 `errCode: -606001`（callContainer 请求体上限 100KB）
- [x] `ExamImageBase64Request` / `images/base64` 接口与其单测已删除；`InMemoryMultipartFile` 保留（两条通道共用）

## T1 后端下载通道

- [x] 新增 `CloudStorageImageDownloader`：https + 域名后缀白名单（防 SSRF）、10MB 上限、URL 脱敏日志、异常不外泄
- [x] 新增 `ExamImageUrlRequest`（url 必填 / contentType 缺省 jpeg / pageNo 可空）
- [x] `ExamPaperController#uploadImageByUrl`：`POST /papers/{paperId}/images/url`
      → 下载 → `InMemoryMultipartFile` → **复用** `ExamPaperService#uploadImage`
- [x] 日志 `上传试卷图片(云存储): paperId=…, size=…`

## T2 小程序

- [x] `utils/upload.ts`：`isCloudStorageUploadEnabled()`、`uploadExamImageViaCloudStorage()`
      （`wx.cloud.uploadFile` → `getTempFileURL` → `callContainer` 只传 URL）
- [x] `api/exam.ts#uploadPaperImage`：云端走云存储直传；失败不再静默降级 multipart
- [x] `ExamUpload.vue`：通道文案「云端云存储直传」；云存储通道压缩目标 2MB；SIZE_TIP 同步
- [x] 通道标签改为 setup 期字符串 + try/catch（避免渲染异常导致按钮消失）

## T2.5 上传成功后清理云存储原图

- [x] `utils/upload.ts`：`uploadToCloudStorage` 返回 `fileID`；后端落库成功后调用 `wx.cloud.deleteFile` 删除原图
- [x] 仅在**后端返回 code=200** 后删除（避免下载失败/重试时源文件已被删）；删除失败只打日志，不影响上传
- [x] 对象存储不再堆积：`exam-upload/` 下文件上传成功后即删除

## T3 测试

- [x] `CloudStorageImageDownloaderTest`（7 例）
- [x] `ExamPaperUrlUploadTest`（2 例）
- [x] `mvn -o test` → **1048 通过 / 0 失败 / 0 跳过**
- [x] `npm run build:mp-weixin` → DONE Build complete（产物含 `images/url`、`wx.cloud.uploadFile`）

## T4 文档

- [x] `student-app/README.md` 云端上传通道说明更新
- [x] 本 change 工件（proposal/design/tasks/verify）

## T5 待真机验证

- [ ] 体验版/正式版（非真机调试）上传试卷图片成功，云端日志出现 `上传试卷图片(云存储)`
- [ ] 解析结果与本地一致（题目数/配图/答案）
- [ ] 2MB 压缩后 OCR 小字识别率
- [ ] 云存储目录 `exam-upload/` 的清理策略（后续）