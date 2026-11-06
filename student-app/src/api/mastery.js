/**
 * 掌握度 API
 *
 * 后端契约：
 * - GET  /api/student/mastery/dashboard          掌握度看板（全量列表）
 * - GET  /api/student/mastery/history/{kpId}     知识点变更历史（分页）
 */
import { get } from '@/utils/request.js';

/**
 * 获取掌握度看板
 * @param {string} [subject] - 科目名称过滤
 * @returns {Promise<Array<MasteryDashboardVO>>}
 */
export function getDashboard(subject) {
  const data = subject ? { subject } : {};
  return get('/student/mastery/dashboard', data);
}

/**
 * 获取知识点变更历史
 * @param {string} knowledgePointId
 * @param {number} [page=1]
 * @param {number} [size=10]
 * @returns {Promise<PageResult<MasteryChangeLogVO>>}
 */
export function getChangeHistory(knowledgePointId, page = 1, size = 10) {
  return get(`/student/mastery/history/${knowledgePointId}`, { page, size });
}
