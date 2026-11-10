# Verify Report：学生端小程序全部业务页面

> **Change ID**: `2026-11-05_student_business_pages`
> **校验日期**: 2026-11-10
> **校验结论**: ✅ **对齐** — 可以进入归档

---

## 前置检查

| 检查项 | 结果 |
|--------|------|
| 含 proposal / design / tasks | ✅ |
| 代码已实施 | ✅（9 页面 + 8 API 模块 + 2 store + 5 utils + 3 组件） |
| 联调完成 | ✅（用户确认，Task 6 已勾选） |
| 无对应后端改动 | ✅ 纯前端，复用已就绪 API |

---

## Proposal 对齐（5 个页面职责）

| # | 功能点 | 状态 | 落点 |
|---|--------|------|------|
| 1 | 首页：活跃目标总览 | ✅ | `pages/index/Index.vue`，`getActiveGoals()` |
| 2 | 作业页：按日期浏览、生成、批改 | ✅ | `pages/homework/Homework.vue`：`getHomeworkByDate` / `generateHomework` / `gradeHomework` |
| 3 | 答题详情：逐题作答、提交 | ✅ | `pages/homework-detail/HomeworkDetail.vue`：`submitAnswer` |
| 4 | 掌握度看板：列表 + 变更历史分页 | ✅ | `pages/mastery/Mastery.vue`：`getDashboard` / `getChangeHistory` |
| 5 | 个人中心：目标/上传/记录/调整/登出 | ✅ | `pages/profile/Profile.vue`：`createGoal`/`getActiveGoals`/`getGoalList`/`completeGoal`/`uploadWithAuth`/`getRecordList`/`triggerAdjustment`/`getAdjustmentHistory`/`logout` |

> 核心目标第 2 条已按 R1 决策改写为「首页做目标总览；作业 Tab 展示作业并触发批改，答题详情页完成作答与提交」。

---

## Design 对齐

| 设计要点 | 状态 | 说明 |
|---------|------|------|
| §1.1 页面结构 | ✅ | 已补 `homework-detail/HomeworkDetail.vue`，首页标注为「活跃目标总览」 |
| §1.2 Pinia app store（currentSubjectId/currentDate/subjectList） | ✅ | `stores/app.ts` |
| §1.3 `useAuthGuard` 统一守卫 | ✅ | `utils/useAuthGuard.ts`，9 个页面均调用；重复的 `composables/` 副本已删 |
| §1.4 只共享筛选参数、不共享业务数据 | ✅ | 作业页与答题详情页各查各的；首页不参与作业数据流（design 已同步） |
| §1.5 上传封装 `uploadWithAuth` | ✅ | `utils/upload.ts`，含 401 重登 + 单次重试；地址改为 env 注入 |
| §1.6 请求层双通道续期 + 401 重放 | ✅ | `utils/request.ts`：通道 A 响应头续期、通道 B 401 重登单次重放、并发锁、错误去重 |
| 隐性约定 #19 完整 URL | ✅ | 本地/云托管均拼完整 URL，地址由 `.env.*` 注入，源码无硬编码 |

### 文档与实现的一致性修正（本轮）

| 项 | 修正 |
|---|------|
| 首页职责 | proposal §2/§3 + design §1.1/§1.4 统一为「目标总览」 |
| 请求地址 | `request.ts` + `upload.ts` 均改为 env 驱动；design 的代码示例与约定表同步 |
| 目标查询口径 | 首页由 `getGoalList(1,50)` + 前端过滤改为 `getActiveGoals()` |

---

## Tasks 对齐（6 个节点）

| 任务 | 状态 |
|------|------|
| Task 1 API + Store + 基础设施 | ✅ |
| Task 2a 首页骨架（范围收窄为目标总览） | ✅ |
| Task 2b 作答交互（改址 HomeworkDetail） | ✅ |
| Task 2c 批改结果（改址 Homework） | ✅ |
| Task 3 作业页 | ✅ |
| Task 4 掌握度看板 | ✅ |
| Task 5 个人中心 | ✅ |
| Task 6 联调测试 | ✅ |

> 7 条实现偏差已写入 tasks.md 的「工件与实现的偏差说明」。

---

## 事实核对

| 断言 | 核对方式 | 结果 |
|------|---------|------|
| 页面全部注册 | `pages.json` 9 个 `path` | ✅ |
| 首页不再调作业接口 | `Index.vue` 仅 import `getActiveGoals` | ✅ |
| 批改在作业页 | `Homework.vue` 调 `gradeHomework` | ✅ |
| 作答在详情页 | `HomeworkDetail.vue` 调 `submitAnswer` | ✅ |
| 无重复守卫文件 | `composables/` 已删除，`utils/useAuthGuard.ts` 被 8 处引用 | ✅ |
| 编译通过 | `npm run build:mp-weixin` | ✅ EXIT=0，DONE |
| 生产包无本地地址 | `dist/.../request.js`、`upload.js` 是否含 `localhost` | ✅ 均为 false（死分支被消除） |
| 生产包含云地址 | 同上 | ✅ 均含云托管域名 |
| 环境变量已内联 | 产物无 `VITE_` 字面量 | ✅ |

---

## 未覆盖与已知技术债

| 项 | 说明 |
|----|------|
| R2 页面过载 | `Profile.vue` 1040 行、`Mastery.vue` 530、`HomeworkDetail.vue` 543，未拆分（按用户要求保留） |
| 前端无自动化测试 | uni-app 侧无单测基线，验证依赖编译 + 真机联调 |
| 循环 chunk 告警 | `stores/auth ↔ utils/request` 双向 import，靠惰性取 store 规避；既有问题，非本次引入 |
| 重复的 `resolveUseLocal()` | `request.ts` 与 `upload.ts` 各有一份，可抽到 `utils/env.ts`（低优先级） |

---

## 自检声明

本次校验由实施者完成，其中以下 4 项为本轮自行修改，建议独立 reviewer 复核：
`proposal.md` 职责改写（R1）、`tasks.md` 状态与偏差说明、`utils/request.ts` + `utils/upload.ts` 的 env 化（R4）、`Index.vue` 查询口径（R6）、删除重复守卫（R3）。
其余页面实现为前序工作产出，本次仅做核对。

---

## 测试结果

- **前端构建**：`npm run build:mp-weixin` → BUILD SUCCESS
- **后端基线**（未改动，仅回归确认）：`521 tests, 0 failures, 0 errors, 5 skipped`

---

## 结论

✅ **对齐** — 5 个页面职责、6 个任务节点全部落地，1 处职责迁移（R1）已回写 proposal/design/tasks，构建通过且生产包不含本地地址。

R2（页面拆分）与前端测试基线为已登记技术债，不阻塞归档。

**可进入 archive。**
