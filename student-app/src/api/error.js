/**
 * 错题管理 API
 */
import { get } from '@/utils/request.js';

/**
 * 查询错题列表（分页）
 * @param {Object} params - 查询参数
 * @param {string} [params.knowledgePointId] - 知识点ID（可选）
 * @param {string} [params.status] - 状态（active/completed，可选）
 * @param {number} [params.page=1] - 页码
 * @param {number} [params.size=10] - 每页数量（最大100）
 * @returns {Promise<Object>} PageResult<ErrorQuestionVO>
 */
export function getErrorQuestions(params = {}) {
  return get('/error-questions', params);
}

/**
 * 查询错题统计
 * @returns {Promise<Object>} ErrorQuestionStatisticsVO
 */
export function getErrorStatistics() {
  return get('/error-questions/statistics');
}
