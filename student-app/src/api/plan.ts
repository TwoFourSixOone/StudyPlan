/**
 * 计划调整 API（本 change 仅触发 + 历史）
 *
 * 后端契约：
 * - POST  /api/student/plan/adjust       触发调整（goalId）
 * - GET   /api/student/plan/adjustments  调整历史（分页）
 */
import { get, post } from '@/utils/request';

/**
 * 触发计划调整
 * @param goalId 目标 ID
 * @returns 调整触发结果
 */
export function triggerAdjustment(goalId: number): Promise<any> {
  return post('/student/plan/adjust', { goalId });
}

/**
 * 获取调整历史
 * @param page 页码
 * @param size 每页数量
 * @returns 分页结果
 */
export function getAdjustmentHistory(page: number = 1, size: number = 10): Promise<any> {
  return get('/student/plan/adjustments', { page, size });
}
