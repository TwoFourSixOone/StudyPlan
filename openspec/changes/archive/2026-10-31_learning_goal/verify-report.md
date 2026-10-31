# Verify Report：学习目标管理

> **Change ID**: `2026-10-31_learning_goal`  
> **校验日期**: 2026-10-31  
> **校验结论**: ✅ **对齐** — 可以进入归档

---

## Proposal 对齐（24 项验收标准）

全部 ✅ 通过。详见校验记录。

## Design 对齐

| 设计要点 | 状态 | 说明 |
|---------|------|------|
| §1.2 包结构 | ✅ | 文件路径与 design 一致 |
| §3.1 DTO 设计 | ✅ | 字段、注解完整 |
| §3.2 VO 设计 | ✅ | 字段与 design 一致 |
| §3.3 GoalService 接口 | ✅ | 8 个方法签名完全一致 |
| §3.4 SubjectService 接口 | ✅ | 1 个方法签名一致 |
| §3.5 createGoal 逻辑 | ⚠️ 适配 | `KnowledgePoint::getSubject`（String 名称）替代 design 伪代码中的 `getSubjectId`（Long），代码注释已说明 |
| §3.5 updateGoal 逻辑 | ✅ | null 语义、endDate 延长校验符合 design |
| §3.5 getActiveGoal 判定口径 | ✅ | `status='active' AND endDate >= today` 完全一致 |
| §3.6 SubjectServiceImpl GROUP BY | ⚠️ 适配 | `GROUP BY subject`（名称列）替代 design 中的 `GROUP BY subject_id`，功能正确 |
| §3.7 Controller 路由 | ✅ | 学生端/管理端/科目路由正确 |
| §3.8 ErrorCodes | ✅ | 5 个新错误码，无 SCORE_INVALID |
| §4.3 技术债登记 | ✅ | TD-01（索引缺失）+ TD-02（N+1 查询）已登记 |
| §5 异常处理表 | ✅ | 统一 BusinessException + ErrorCodes |
| §6 隐性约定对齐 | ✅ | 约定 #1/#2/#3/#4/#7/#9 全部遵循 |

## Tasks 对齐（13 个节点）

全部 ✅ 完成。

## 隐性约定对齐

| 约定 | 状态 |
|------|------|
| #1 统一响应结构 | ✅ |
| #2 异常处理规范 | ✅ |
| #3 分页参数规范 | ✅ |
| #4 时间格式规范 | ✅ |
| #7 tenant_id 隔离 | ✅ |
| #9 @Transactional rollbackFor | ✅（S-01 修复后） |

## 超出范围改动

- `src/test/resources/schema.sql`（修改）：为集成测试补充 subject + learning_goal 表定义，属测试基础设施必要补充，低风险。

## 代码评审修复项

本次校验同步完成了独立代码评审的修复：

| 编号 | 严重度 | 修复内容 |
|------|--------|---------|
| S-01 | 严重 | 写方法添加 @Transactional(rollbackFor) |
| S-02 | 严重 | 补充跨租户科目访问测试 |
| W-01 | 警告 | updateGoal Subject 查询添加注释说明 |
| W-02 | 警告 | selectMaps 别名兼容性验证通过 |
| W-03 | 警告 | 管理端 status 参数枚举校验 |
| W-04 | 警告 | 集成测试补充 endDate 缩短场景 |
| W-05 | 警告 | 管理端列表测试补充无筛选场景 |
| W-06 | 警告 | VO 补充 @NoArgsConstructor/@AllArgsConstructor |
| W-07 | 警告 | 提取 findModifiableGoal 公共方法 |
| I-01 | 建议 | DATE_INVALID 错误码描述细化 |
| I-05 | 建议 | ArgumentCaptor 增强 Mock 参数验证 |

## 测试结果

- **编译**：BUILD SUCCESS
- **测试**：168 tests, 0 failures, 0 errors, 5 skipped
