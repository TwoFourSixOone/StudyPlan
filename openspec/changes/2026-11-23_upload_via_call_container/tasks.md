# Tasks：试卷图片上传走 cloud callContainer（base64）

> **Change**：`2026-11-23_upload_via_call_container`　**状态**：已实施（2026-11-23）

## T1 后端 base64 上传通道

- [x] 新增 `InMemoryMultipartFile`（`com.jiang.common.util`）：把 `byte[]` 适配为 `MultipartFile`
- [x] 新增 `ExamImageBase64Request`（`dataBase64` 必填 / `contentType` 缺省 jpeg / `pageNo` 可空）
- [x] `ExamPaperController#uploadImageBase64`：`POST /papers/{paperId}/images/base64`
      → base64 解码（MIME 解码容忍换行；解码前按长度粗判 >10MB 直接 1184）
      → 包成 `InMemoryMultipartFile` → **复用** `ExamPaperService#uploadImage`（归属/张数/累计/魔数/分辨率/落盘/页序全不变）
- [x] 日志 `上传试卷图片(base64): paperId=..., size=..., contentType=..., pageNo=...`（与 multipart 版区分，便于确认走哪条通道）
- **验收**：正常 base64 落盘；空/非法 → 1183；超大 → 1184

## T2 小程序通道切换

- [x] `utils/upload.ts`：新增 `isCloudContainerEnabled()`、`uploadImageViaContainer()`（`readFile` 读 base64 →
      `wx.cloud.callContainer` + `X-WX-SERVICE` + `Authorization`）
- [x] `api/exam.ts#uploadPaperImage`：云端容器可用 → 走 `/images/base64`；失败且无业务码 → 降级 `uploadWithAuth`（multipart）
- [x] `ExamUpload.vue`：callContainer 通道压缩目标收紧到 **900KB**（base64 约 1.2MB）；超限提示区分通道
- **验收**：`npm run build:mp-weixin` 通过；本地模式行为不变

## T3 测试

- [x] 新增 `ExamPaperBase64UploadTest`（6 例）：正常解码交 service、contentType 缺省、空 base64、非法 base64、
      超大 base64 前置拦截、含换行的 MIME base64
- [x] `mvn -o test` → **1045 通过 / 0 失败 / 0 跳过**

## T4 文档与工件

- [x] `student-app/README.md` 云端上传说明更新为「callContainer base64 通道」
- [x] 本 change 工件（proposal/design/tasks/verify）

## T5 待验证（真机）

- [ ] 云托管环境（非真机调试）上传试卷图片成功，云端日志出现 `上传试卷图片(base64): ...`
- [ ] 解析结果与本地一致（题目数/配图/答案）
- [ ] 大图（≥2MB 原图）压缩到 ≤900KB 后 OCR 仍可识别小字
