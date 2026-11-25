# Design：试卷图片上传走 callContainer（base64）

> **Change**：`2026-11-23_upload_via_call_container`

## D1 传输通道对比

| 方式 | 是否需要域名白名单 | 请求体限制 | 现状 |
|---|---|---|---|
| `uni.uploadFile`（公网 HTTPS） | ✅ 需要（云托管默认域名被官方禁用于正式环境） | 网关约 2MB | 云端不可用 |
| `wx.cloud.callContainer`（微信内网） | ❌ 不需要 | 云托管入口限制（实测需控制在 ~1.5MB 以内较稳） | ✅ 普通接口已在用 |

结论：把上传也搬到 `callContainer`。

## D2 后端接口

```
POST /api/student/exam/papers/{paperId}/images/base64
Content-Type: application/json
Authorization: Bearer <student token>

{
  "dataBase64": "<base64>",
  "contentType": "image/jpeg",
  "pageNo": 1
}

200 → { code:200, data: ExamImageUploadVO }        // 与 multipart 版返回一致
1139 → 类型不支持（非 jpg/jpeg/png 或魔数不符）
1184 → 单张超过 9.5MB
1185 → 累计超过 30MB
1189 → 张数已达上限
```

实现要点：
- `ExamImageBase64Request`（`@NotBlank dataBase64`、`contentType` 缺省 `image/jpeg`、`pageNo` 可空）；
- `InMemoryMultipartFile`（`com.jiang.common.util`）：把 `byte[]` 适配为 `MultipartFile`，
  从而**完全复用** `ExamPaperService#uploadImage`（校验链、分布式锁、落盘、页序追加都不变）；
- 解码用 `Base64.getMimeDecoder()`（容忍换行）；解码前先按 base64 长度粗判上限，避免超大字符串占内存；
- 日志：`上传试卷图片(base64): paperId=..., size=..., contentType=..., pageNo=...`（与 multipart 版区分，便于排查走的是哪条通道）。

## D3 前端

```ts
// utils/upload.ts
export async function uploadImageViaContainer(paperId, filePath, pageNo)
  → readFileBase64(filePath) → wx.cloud.callContainer({ path: `/api/student/exam/papers/${paperId}/images/base64`, ... })
export function isCloudContainerEnabled(): boolean
export function uploadExamImage(paperId, filePath, pageNo, options) // 统一入口：容器优先，降级 multipart
```

- base64 读取：`uni.getFileSystemManager().readFile({ filePath, encoding: 'base64' })`；
- 调 `callContainer` 时 header 必须带 `X-WX-SERVICE`（与 `request.ts` 一致）；
- 失败分类提示：`callContainer` 不可用 → 自动降级 `uni.uploadFile`；两者都失败才抛错（错误信息含通道名，便于排障）。

## D4 大小策略

| 通道 | 目标原始大小 | 压缩梯度 |
|---|---|---|
| callContainer（base64） | **≤ 900KB**（base64 约 1.2MB） | 85 → 70 → 55 → 45 |
| multipart（本地/已配域名） | ≤ 1.8MB | 85 → 70 → 55 → 45 |

900KB 是对云托管入口限制的保守取值（base64 膨胀 33%）；若实测仍失败，可再降到 700KB（常量）。

## D5 风险

- **base64 体积**：比二进制大 33%，因此目标尺寸更小，OCR 小字识别率可能略降；若实测识别率下降明显，再评估"云开发存储直传"方案；
- **内存**：小程序端 base64 字符串 + 后端解码各占一份内存；单张 ≤900KB 原始（≈1.2MB base64）在两端都可接受；
- **无域名依赖**：不依赖 uploadFile 合法域名，离线/内网环境行为一致；
- 若后续买了备案域名，multipart 通道仍然可用（降级顺序不变）。
