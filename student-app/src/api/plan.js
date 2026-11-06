/**
 * 计划调整 API（本 change 仅触发 + 历史）
 *
 * 后端契约：
 * - POST  /api/student/plan/adjust       触发调整（goalId）
 * - GET   /api/student/plan/adjustments  调整历史（分页）
 */
import { get, post } from '@/utils/request.js';

/**
 * 触发计划调整
 * @param {number} goalId
 * @returns {Promise<AdjustTriggerVO>}
 */
export function triggerAdjustment(goalId) {
  return post('/student/plan/adjust', { goalId });
}

/**
 * 获取调整历史
 * @param {number} [page=1]
 * @param {number} [size=10]
 * @returns {Promise<PageResult<AdjustmentHistoryVO>>}
 */
export function getAdjustmentHistory(page = 1, size = 10) {
  return get('/student/plan/adjustments', { page, size });
}
