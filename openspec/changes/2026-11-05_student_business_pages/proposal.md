# Proposal：学生端小程序全部业务页面

> **Change 名称**：`2026-11-05_student_business_pages`  
> **创建日期**：2026-11-05  
> **版本**：草案 v3（二次审查修正版）

---

## 1. 需求背景

`2026-11-05_student_miniapp` change 已完成学生端登录认证基础框架（JWT 双通道刷新、并发重登锁、错误去重等），4 个 TabBar 页面均为占位状态（"XX开发中"）。后端全部学生端 API 已就绪。

本 change 的目标是将学生端 4 个 TabBar 页面从占位状态升级为完整业务页面，跑通"设定目标 → 查看计划 → 完成作业 → 掌握度看板 → 个人中心"的核心学习闭环前端流程。

---

## 2. 核心目标

1. 学生登录后能设定学习目标（选科目 + 设目标）
2. 首页展示每日计划与作业，支持逐题作答与提交
3. 掌握度看板展示知识点掌握度与变更历史
4. 个人中心展示用户信息、试卷上传入口、学习记录、计划调整
5. 所有页面复用已建好的 `utils/request.js`（JWT 注入、401 重登、错误去重）

---

## 3. 功能范围

### 包含

| 页面 | Tab | 功能点 | 后端 API |
|------|-----|--------|----------|
| **首页 Index** | 首页 | 活跃目标展示（目标名、科目、剩余天数）、每日作业列表、逐题作答、提交答案、批改结果 | `GET /api/student/goal/active`、`GET /api/homework/by-date`、`POST /api/homework/submit`、`POST /api/homework/grade` |
| **作业 Homework** | 作业 | 按日期浏览作业、题目详情（含已答/已批改状态）、生成每日作业 | `GET /api/homework/by-date`、`POST /api/homework/generate` |
| **看板 Mastery** | 看板 | 掌握度列表（按科目筛选）、知识点变更历史（分页） | `GET /api/student/mastery/dashboard`、`GET /api/student/mastery/history/{kpId}` |
| **我的 Profile** | 我的 | 用户信息、科目列表、学习目标管理（创建/列表/结束）、试卷上传、学习记录（列表+详情）、计划调整（触发+历史）、登出 | `GET /api/auth/current`、`GET /api/student/subject`、`POST/GET /api/student/goal`、`POST /api/student/exam/upload`、`GET /api/student/records`、`GET /api/student/records/{type}/{id}`、`POST /api/student/plan/adjust`、`GET /api/student/plan/adjustments`、`authStore.logout()` |

### 不包含

- 试卷上传后的 OCR 解析进度轮询 UI（后端有 `GET /api/student/exam/task/{taskId}` 接口，本 change 仅做上传，不展示进度）
- 计划调整的 diff 确认/拒绝交互（后端有 `POST /api/student/plan/diff/{diffId}/confirm|reject` 接口，本 change 仅做触发按钮 + 历史列表，不做 diff 详情确认页）
- 试卷分析结果详情页（后端有 `GET /api/student/exam/result/{resultId}` 接口，推迟到下轮）
- 知识点评测独立入口（通过每日作业中的评测题覆盖）
- 学习记录的详细回溯页面（仅展示列表 + 基础详情）
- 真机预览与发布

---

## 4. 隐性约定检查

| 约定/规则 | 是否涉及 | 说明 |
|------|---------|------|
| 隐性约定 #1 统一响应结构 | ✅ | 前端 `request.js` 已处理 `Result<T>` 解析 |
| 隐性约定 #7 JWT 登录态 | ✅ | 所有页面复用 auth store + request.js |
| 隐性约定 #18 `api/` 目录模块异常 | ✅ | 请求工具在 `utils/` 目录 |
| 隐性约定 #19 完整 URL | ✅ | BASE_URL = `http://localhost:80/api` |
| 隐性约定 #20 开发者工具缓存 | ✅ | 联调时需清除缓存 |
| 产品规则 #3 分页参数从 1 开始 | ✅ | 列表页分页默认 `page=1` |

---

## 5. 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|---------|
| 后端部分接口返回数据结构与前端预期不符 | 中 | 先调通接口，再开发 UI |
| 文件上传（试卷）不走 request.js | 中 | 需手动注入 JWT header，封装 `uploadWithAuth()` 工具函数（见 Design §1.5） |
| 页面间状态共享（如选中的科目、日期） | 低 | 使用 Pinia app store 管理；页面只共享 currentDate/subjectId，各自请求数据 |
| 单个 change 范围过大 | 中 | 按页面拆分为独立 Task，每完成一个 Task 编译验证 |

---

## 6. 前置依赖

- `2026-11-05_student_miniapp`（已归档）— 登录框架、request.js、auth store、TabBar 占位页面
