# Design：新用户诊断（方案 A）

## D1 后端诊断接口
`GET /student/exam/papers/{paperId}/diagnosis` → `ExamDiagnosisVO`：
`paperId/subjectId/subjectName/examDate/paperType/totalQuestions/judgedCount/correctCount/userScore/totalScore/accuracy/weakKnowledgePoints[{knowledgePointId,knowledgePointName,wrongCount,masteryScore}]/errorQuestionCount/confidenceNote`

- 4 条 SQL：① `selectDetailWithSubject`（归属，失败 1136）② `selectByPaperOrderByIndex` ③ `countActiveExamErrorsByPaper`（exam 来源负 ID）④ `selectNamesWithMasteryByIds`（批量 Kp+掌握度）
- accuracy：`correct/judged` HALF_UP 4 位；judged=0 → null
- 薄弱点：本卷 `is_correct=0` 且 kp 非空；wrongCount desc → masteryScore asc（null 最弱）→ kpId asc；≤10
- 只读、无 DDL、不触发 LLM；0 题/未解析 → 200 + 计数 0

## D2 前端引导
- Profile 保存年级成功 → 引导卡（使用“去上传试卷”/“跳过”，遮罩可关，不阻断）
- 上传页 `?from=diagnosis`：解析完成 redirectTo 诊断页；普通入口仍跳详情
- 诊断页：本卷表现 + 薄弱点 + confidenceNote；「根据诊断创建目标」→ `setGoalPrefill({subjectId,fromDiagnosis:true})` → Profile 消费（tabBar 不能带 query）
- 诊断页空数据：无 status 字段，totalQuestions=0 时用详情 status 判定解析中/失败/未提交，2s 轮询 5 分钟
- **判分门禁（F1 修复）**：上传页 `from=diagnosis` 必须等 `exam_paper.grade_status=graded` 才跳诊断页（2s×150≈5min，超时给「继续等待判分/去历史试卷」出口）；第 8 张响应 taskId 优先走任务轮询（task completed 与 TX-B grade CAS 同事务）。诊断页 `totalQuestions>0 && judgedCount===0` 时按详情 `gradeStatus` 轮询到 graded 后静默刷新；`judgedCount<=0` 得分显示「—」

## D3 文档
产品规则 #1 补引导顺序（诊断可跳过）；#4 补诊断口径、覆盖率/置信度提示。
