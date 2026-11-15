# Tasks：新用户诊断（方案 A）

> **Change**：`2026-11-15_new_student_diagnosis`

## T1 后端诊断接口（t1 / backend-dev）✅
- [x] `ExamDiagnosisVO`/`ExamDiagnosisWeakKpVO`；Controller/Service 新增 `getDiagnosis`
- [x] 4 条固定 SQL（归属 1136、错题数、Kp+掌握度批量）
- [x] accuracy 4 位/judged=0→null；薄弱点 Top10 排序；0 题兜底；只读无 DDL
- [x] `ExamDiagnosisServiceTest` 6 用例；`mvn test` 854 全绿

## T2 前端引导与诊断页（t2 / frontend-dev）✅
- [x] `api/exam.ts` + `getExamDiagnosis` + 类型
- [x] `pages/exam-diagnosis`（表现+薄弱点+置信度提示+三个按钮；空数据轮询兜底）
- [x] Profile 年级保存后引导卡（去上传/跳过，不阻断）
- [x] 上传页 `from=diagnosis` 完成跳诊断页；普通入口不变
- [x] `stores/app.ts` goalPrefill；Profile onShow 预填科目 + 3–6 月提示
- [x] `npm run build:mp-weixin` 通过
- 备注：引导卡未带 subjectId 预选（该场景无科目上下文，无调用方）

## T3 文档回写（t3 / docs-writer）✅
- [x] 产品规则 #1 引导顺序、#4 诊断口径与 confidenceNote；术语表补「试卷诊断」
- [x] 隐性约定 #36（诊断聚合只读/4 条 SQL/不触发 LLM 与调整；编号起点 #37）
- 备注：`confidenceNote` 以实现原文为准（「基于 1 份试卷的诊断，仅供参考；建议持续完成每日作业后复看掌握度」），已认可；「引导卡未带 subjectId」无用户可见行为，不写入产品文档

## T4 独立验证（t4 / reviewer）⏳
- [ ] 越权 1136 / 0 题 / judged=0 / 排序截断 / 错题数口径
- [ ] 前端引导可跳过、老入口不变、from 分支、空数据兜底；构建通过
- [ ] 与 proposal/design 字段与口径一致

## T5 集成提交（captain）
- [ ] 单独 commit + verify.md；按既有拆分风格
