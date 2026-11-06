/**
 * 学习目标 API
 *
 * 后端契约：
 * - POST   /api/student/goal          创建目标
 * - GET    /api/student/goal/active   查看活跃目标（可选 subjectId）
 * - GET    /api/student/goal          目标列表（分页）
 * - POST   /api/student/goal/{id}/complete  结束目标
 */
import { get, post } from '@/utils/request.js';

/**
 * 创建学习目标
 * @param {Object} data - { subjectId, direction, targetScore, startDate, endDate }
 * @returns {Promise<Object>} GoalVO
 */
export function createGoal(data) {
  return post('/student/goal', data);
}

/**
 * 查看当前活跃目标列表
 * @param {number} [subjectId] - 可选科目过滤
 * @returns {Promise<Array<Object>>} GoalVO[] 或 []
 */
export function getActiveGoals(subjectId) {
  return get('/student/goal/active', subjectId ? { subjectId } : {});
}

/**
 * 查看目标列表（含历史）
 * @param {number} [page=1]
 * @param {number} [size=10]
 * @returns {Promise<PageResult<GoalVO>>}
 */
export function getGoalList(page = 1, size = 10) {
  return get('/student/goal', { page, size });
}

/**
 * 结束目标
 * @param {number} goalId
 * @returns {Promise<void>}
 */
export function completeGoal(goalId) {
  return post(`/student/goal/${goalId}/complete`);
}
