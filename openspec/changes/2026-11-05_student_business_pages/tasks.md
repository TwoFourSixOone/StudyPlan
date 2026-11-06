# Tasks：学生端小程序全部业务页面

> **Change 名称**：`2026-11-05_student_business_pages`  
> **创建日期**：2026-11-05  
> **版本**：草案 v3（二次审查修正版）  
> **状态**：Task 1-5 已完成，待 Task 6 联调测试

---

## 任务清单

### Task 1：API 接口层 + App Store + 基础设施

**描述**：
- 创建 `composables/useAuthGuard.js`（统一登录态守卫 composable）
- 创建 `stores/app.js`（Pinia store：currentSubjectId、currentDate、subjectList + init 方法在 onLaunch 时加载科目列表）
- 创建 `utils/upload.js`（uploadWithAuth 封装，手动注入 JWT header）
- 创建 `api/goal.js`（createGoal、getActiveGoal、getGoalList、completeGoal；updateGoal 推迟到需要编辑目标 UI 时再建）
- 创建 `api/homework.js`（generateHomework、getHomeworkByDate、submitAnswer、gradeHomework）
- 创建 `api/mastery.js`（getDashboard、getChangeHistory）
- 创建 `api/exam.js`（仅 uploadExam；getTaskProgress/getResultDetail/getResultHistory 推迟到下轮）
- 创建 `api/record.js`（getRecordList、getRecordDetail）
- 创建 `api/plan.js`（仅 triggerAdjustment、getAdjustmentHistory；getDiffDetail/confirmDiff/rejectDiff 推迟到下轮）
- 更新 `api/auth.js` 的导入路径（`@/utils/request.js`）
- 更新 `App.vue`：在 onLaunch 中调用 `appStore.init()` 加载科目列表
- **开发前预检查**：核对 `EmptyState` 和 `LoadingState` 组件 props（text/image 等），如需补充则在 Task 1 内一并修改

**涉及文件**：
- `student-app/src/composables/useAuthGuard.js`（新增）
- `student-app/src/stores/app.js`（新增）
- `student-app/src/utils/upload.js`（新增）
- `student-app/src/api/goal.js`（新增）
- `student-app/src/api/homework.js`（新增）
- `student-app/src/api/mastery.js`（新增）
- `student-app/src/api/exam.js`（新增）
- `student-app/src/api/record.js`（新增）
- `student-app/src/api/plan.js`（新增）
- `student-app/src/api/auth.js`（修改导入路径）
- `student-app/src/App.vue`（修改：添加 appStore.init()）
- `student-app/src/components/EmptyState.vue`（确认/补充 props）
- `student-app/src/components/LoadingState.vue`（确认/补充 props）

**验证**：`npm run build:mp-weixin` 无报错 ✅ 已通过

**预估时间**：50 分钟

---

### Task 2a：首页骨架（目标卡片 + 日期 + 作业列表只读）

**描述**：
- 替换 Index.vue 占位内容为完整首页骨架
- 顶部：科目选择器（Picker，数据源 appStore.subjectList）
- 活跃目标卡片：调用 `GET /api/student/goal/active`，展示目标名、科目、剩余天数
- 若 subjectList 为空（onLaunch 加载失败），onShow 时调 `appStore.init()` 重试一次
- 日期选择器：默认今天，可切换日期（更新 appStore.currentDate）
- 作业列表：调用 `GET /api/homework/by-date`，展示题目列表（只读，题干 + 题型标记）
- 无目标/无作业时使用 EmptyState
- 加载中展示 LoadingState
- 添加 `useAuthGuard()` 登录态守卫

**涉及文件**：
- `student-app/src/pages/index/Index.vue`（改造）

**验证**：`npm run build:mp-weixin` 无报错 ✅ 已通过

**预估时间**：50 分钟

---

### Task 2b：作答交互（题型渲染 + 提交）

**描述**：
- 在 Task 2a 基础上添加答题功能
- 题型渲染：单选题（radio）、多选题（checkbox）、填空题（input）
- 答题状态管理：记录用户选择的答案
- 提交答案：调用 `POST /api/homework/submit`
- 提交后刷新当前日期作业列表

**涉及文件**：
- `student-app/src/pages/index/Index.vue`（继续改造）

**验证**：`npm run build:mp-weixin` 无报错 ✅ 已通过

**预估时间**：40 分钟

---

### Task 2c：批改结果展示

**描述**：
- 在 Task 2b 基础上添加批改功能
- 批改按钮：调用 `POST /api/homework/grade`
- 批改结果展示：每题对错标记、正确答案、解析
- 批改后刷新页面数据

**涉及文件**：
- `student-app/src/pages/index/Index.vue`（继续改造）

**验证**：`npm run build:mp-weixin` 无报错 ✅ 已通过

**预估时间**：30 分钟

---

### Task 3：作业页 Homework.vue

**描述**：
- 替换占位内容为作业浏览页
- 读取 appStore.currentDate 和 currentSubjectId，调用 `GET /api/homework/by-date`
- 日期切换浏览历史作业（更新 appStore.currentDate，首页同步）
- 当 by-date 返回空列表时，展示“生成作业”按钮，调用 `POST /api/homework/generate`，成功后刷新列表
- 题目详情展示（题干、选项、解析）
- 答题状态标记：根据 HomeworkVO.studentAnswer（null=未答）和 isCorrect（null=未批改）展示状态（Task 1 调通接口时确认字段存在，若缺失则降级为仅展示题目）
- **作业页仅浏览，提交/批改在首页完成**
- 添加 `useAuthGuard()` 登录态守卫

**涉及文件**：
- `student-app/src/pages/homework/Homework.vue`（改造）

**验证**：`npm run build:mp-weixin` 无报错 ✅ 已通过

**预估时间**：45 分钟

---

### Task 4：掌握度看板 Mastery.vue

**描述**：
- 替换占位内容为掌握度看板
- 顶部：科目筛选（Picker，数据源 appStore.subjectList）
- 页面 onShow 时重新拉取 dashboard 数据，确保从首页提交作业后切过来能拿到最新数据
- 掌握度列表：后端返回全量知识点（通常 ≤ 50 个），前端不分页，直接展示
- 每个知识点展示：名称、掌握度进度条（颜色：红<0.4、黄0.4-0.7、绿>0.7）、置信度
- 点击知识点 → 展开/弹窗变更历史：调用 `GET /api/student/mastery/history/{kpId}`
- 变更历史列表：时间、变更类型（作业/评测/考试）、变动量、变更前值→变更后值
- 变更历史支持上拉加载更多（分页，每次 10 条）
- 无数据时使用 EmptyState
- 添加 `useAuthGuard()` 登录态守卫

**涉及文件**：
- `student-app/src/pages/mastery/Mastery.vue`（改造）

**验证**：`npm run build:mp-weixin` 无报错 ✅ 已通过

**预估时间**：1 小时

---

### Task 5：个人中心 Profile.vue

**描述**：
- 替换占位内容为个人中心
- 用户信息卡片：昵称、openid（从 authStore.studentInfo 读取）
- 功能列表（cell 列表）：
  - **学习目标**：弹窗/页面（创建目标表单：选科目 + 目标描述 + 周期；查看目标列表；结束目标；不支持编辑目标）
  - **试卷上传**：选科目 → 选日期 → `uni.chooseImage` 选图 → `uploadWithAuth()` 上传
  - **学习记录**：列表页（按日期/类型筛选），调用 `GET /api/student/records`；点击记录查看基础详情 `GET /api/student/records/{type}/{id}`
  - **计划调整**：触发按钮 `POST /api/student/plan/adjust` + 调整历史列表 `GET /api/student/plan/adjustments`
  - **登出**：调用 `authStore.logout()`
- 添加 `useAuthGuard()` 登录态守卫

**涉及文件**：
- `student-app/src/pages/profile/Profile.vue`（改造）

**验证**：`npm run build:mp-weixin` 无报错 ✅ 已通过

**预估时间**：2 小时

---

### Task 6：联调测试

**描述**：
- 启动后端服务
- 在微信开发者工具中清除缓存后重新编译
- **逐页面验证**：
  - 登录 → 首页加载 → 科目选择 → 目标展示
  - 首页作业列表 → 答题 → 提交 → 批改
  - 作业页日期切换 → 历史作业浏览
  - 掌握度看板 → 科目筛选 → 进度条 → 变更历史
  - 个人中心 → 用户信息 → 目标管理 → 试卷上传 → 学习记录 → 计划调整 → 登出
- **机制验证**：
  - 401 自动重登（破坏 token 后操作）
  - 所有页面 onShow 登录态兜底（清除 token 后切 Tab）
  - 错误提示去重（快速多次失败请求）
- **数据一致性验证**：
  - 提交作业后切换到掌握度看板，验证掌握度是否更新
  - 首页和作业页切换 Tab 后数据是否同步（通过 currentDate 同步）

**涉及文件**：全部前端文件

**预估时间**：1 小时 30 分钟

---

## 任务依赖关系

```
Task 1（API + Store + 基础设施）
  ├─ Task 2a（首页骨架）
  │   ├─ Task 2b（作答交互）
  │   │   └─ Task 2c（批改结果）
  ├─ Task 3（作业页）
  ├─ Task 4（掌握度看板）
  └─ Task 5（个人中心）

Task 2a-5 完成后
  └─ Task 6（联调测试）
```

---

## 总预估时间

- **基础层**（Task 1）：50 分钟
- **首页**（Task 2a-2c）：2 小时
- **作业页**（Task 3）：45 分钟
- **掌握度看板**（Task 4）：1 小时
- **个人中心**（Task 5）：2 小时
- **联调测试**（Task 6）：1 小时 30 分钟

**小计**：约 8 小时 5 分钟  
**Buffer（20%）**：约 1 小时 37 分钟  
**总计**：约 9 小时 40 分钟（1.5 个工作日）

---

## 验收标准

1. 所有 Task 完成
2. 首页能展示活跃目标和当日作业，支持答题、提交、批改
3. 作业页能按日期切换浏览历史作业
4. 掌握度看板能按科目筛选、展示进度条和变更历史（变更历史上拉加载，看板本身不分页）
5. 个人中心能展示用户信息、创建/管理目标、上传试卷、查看学习记录、触发计划调整、登出
6. 所有页面通过 `useAuthGuard()` 统一校验登录态，未登录自动跳转登录页
7. 401 自动重登机制正常工作
8. 提交作业后切换看板，页面触发重新请求；若后端为同步更新则数据立即变化，若为异步更新则记录实际生效延迟并在联调报告中注明
9. 编译无错误，微信开发者工具中可正常运行
