/**
 * 学习目标 API
 *
 * 后端契约：
 * - POST   /api/student/goal          创建目标
 * - GET    /api/student/goal/active   查看活跃目标（可选 subjectId）
 * - GET    /api/student/goal          目标列表（分页）
 * - POST   /api/student/goal/{id}/complete  结束目标
 */
import { get, post } from '@/utils/request';

/**
 * 创建学习目标
 * @param data 目标数据 { subjectId, direction, targetScore, startDate, endDate }
 * @returns GoalVO
 */
export function createGoal(data: Record<string, any>): Promise<any> {
  return post('/student/goal', data);
}

/**
 * 查看当前活跃目标列表
 * @param subjectId 可选科目过滤
 * @returns GoalVO[] 或 []
 */
export function getActiveGoals(subjectId?: number): Promise<any[]> {
  return get('/student/goal/active', subjectId ? { subjectId } : {});
}

/**
 * 查看目标列表（含历史）
 * @param page 页码
 * @param size 每页数量
 * @returns 分页结果
 */
export function getGoalList(page: number = 1, size: number = 10): Promise<any> {
  return get('/student/goal', { page, size });
}

/**
 * 结束目标
 * @param goalId 目标 ID
 * @returns void
 */
export function completeGoal(goalId: number): Promise<void> {
  return post(`/student/goal/${goalId}/complete`);
}
