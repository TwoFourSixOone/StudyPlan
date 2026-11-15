# Verify：新用户引导（方案 A）— 先诊断后定目标

> Change：`2026-11-15_new_student_diagnosis`　验证：t4（不通过）→ t5 修复 → t6 复验（通过）

## 实现
- 后端 `GET /api/student/exam/papers/{paperId}/diagnosis`（4 条固定 SQL、越权 1136、accuracy 4 位、judged=0→null、薄弱点 Top10、只读无 DDL）
- 前端 Profile 引导卡（可跳过/遮罩可关）、`exam-diagnosis` 页、上传页 `from=diagnosis` 跳转、`goalPrefill` 预填
- 文档：规则 #1/#4、隐性约定 #36

## 验证证据
- `mvn test`：854 / 0 failure / 5 skipped，BUILD SUCCESS；诊断相关 61 tests 全绿
- `npm run build:mp-weixin`：DONE，exit 0；产物含 `gradeStatus/gradeTimedOut/judgedCount` 守卫与 `from=diagnosis` 分支
- t4 findings：F1（8 图满额未判分即跳诊断页）/F4（judged=0 显示 0/0）/F2（design 字段名）已修复；t6 复验确认关闭
- F5（文档同步判分门禁）已回填 product #4 / 隐性 #36 / design D2；F6 见下

## F6 说明
普通入口的 8 图路径也命中「taskId 优先轮询」分支，跳 `ExamDetail` 的时机从 TX-A（paper completed）变为 TX-B（task completed，即判分后），URL/交互不变——属修复带来的可接受时序变化。

## 遗留
- **真机/连库 E2E 未执行**（本环境无 DB）：8 图满额自动触发 + 判分窗口需真机实测复核
- F3 已知缺口：新端点无 MockMvc、2 条新 Mapper SQL 无 SQL 形态测试（Service 全 mock）；接受理由：契约由 t6 静态核对 + 现有 MapperSqlTest 风格覆盖不足，后续补
