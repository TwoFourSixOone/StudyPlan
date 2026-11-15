# Tasks：试卷解析 v3

> **Change 名称**：`2026-11-12_exam_quality_v3`

---

## T1 整卷单次调用

- [x] `exam_parse_paper_v1.txt`（跨页/答题卡/多选/全卷题号/figureRect）
- [x] `ExamParseAgent.parsePaper` + `ExamParsePaperContext` + `ExamParseAgentImpl.parsePaper`
- [x] `ExamParseServiceImpl`：`single-call` 开关；成功走 mergeAndAttribute，空/异常回退逐图
- [x] `maxTokens=8000`（AgentScopeConfig + GenerateOptions）
- [x] 预算 180s → 300s；前端轮询 90 → 150
- **验收**：编译通过；843 用例全绿（回退路径保持原测试语义）✅

## T2 答题卡 / 跨页 / 多选（Prompt 契约）

- [x] 跨页合并规则（题干拼接、imageIndex 取起始图）
- [x] 答题卡按题号匹配；题目页空白不等于 blank
- [x] 多选：输出全部正确标签；禁止因学生少选而少写答案
- **验收**：Prompt 契约测试（v2）+ 真实卷待验证 ✅/⏳

## T3 配图 base64 落库

- [x] `V14__add_exam_question_figure_base64.sql`（本地已执行）
- [x] 实体/Mapper（batchInsert、selectByPaper）、`FigureData`（url + base64）
- [x] VO `figureBase64` + 前端 `figureBase64 || questionImageUrl`
- **验收**：`mvn test` 通过；前端构建通过 ✅

## T4 请求体控制

- [x] `ImageDownscaler`（1600px / quality 0.82）
- [x] 整卷读取总字节 >6MB 时降采样并记录日志
- **验收**：编译通过；4 图实测请求体 0.89MB / 21.8s ✅

## T5 回归与文档

- [x] `mvn test`：843 / 0 fail / 5 skip
- [x] `npm run build:mp-weixin` 通过
- [x] 变更工件 + verify（本文件同目录）
- **验收**：全绿 ✅

## T6 待真实卷验证（重启后）

- [ ] 跨页题合并为一道
- [ ] 答题卡答案被正确匹配
- [ ] 多选按卷面判断（不再漏判）
- [ ] 配图位置正确（base64 渲染）
