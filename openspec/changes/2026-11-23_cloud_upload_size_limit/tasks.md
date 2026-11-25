# Tasks：云端上传按 2MB 请求体上限自动压缩

> **Change**：`2026-11-23_cloud_upload_size_limit`　**状态**：已实施（2026-11-23）

## T1 云端模式判定

- [x] `ExamUpload.vue` 新增 `resolveUseLocal()`（与 `utils/upload.ts` 同一套规则：`VITE_USE_LOCAL` 优先，否则 `DEV`）
- [x] `IS_CLOUD_UPLOAD = !resolveUseLocal()`
- **验收**：云端模式与本地模式的压缩策略可区分

## T2 云端安全阈值 + 多档压缩

- [x] `CLOUD_SAFE_BYTES = 1.8MB`、`CLOUD_COMPRESS_QUALITIES = [85, 70, 55, 45]`
- [x] `compressImage(src, quality)` 支持指定质量
- [x] 云端模式：逐档压缩直到 ≤1.8MB（小图也尝试，避免"刚好踩 2MB"）；压缩后仍超限 → 明确提示且**不发请求**
- [x] 本地模式：行为不变（>800KB 压一次 quality 85）
- [x] `SIZE_TIP` 云端模式显示"云端单次上传上限约 2MB：会自动压缩"
- **验收**：≥2MB 原图在云端被压到 ≤1.8MB

## T3 文档

- [x] 本 change 工件（proposal/design/tasks/verify）
- [x] `student-app/README.md` 补云端上传限制与自动压缩说明

## T4 验证

- [x] `cd student-app && npm run build:mp-weixin` → DONE Build complete
- [x] `mvn -o test`（本次不改后端，回归用）→ 1039 通过
- [ ] 真机 + 云托管联调：≥2MB 原图上传成功、请求体 < 2MB
