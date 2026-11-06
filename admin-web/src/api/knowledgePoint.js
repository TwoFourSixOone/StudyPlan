import request from './request';

/**
 * 查询知识点列表
 * @param {Object} params - 查询参数
 * @param {string} params.keyword - 关键词
 * @param {string} params.status - 状态：pending_review/active/disabled
 * @param {string} params.subject - 学科（修复 D4）
 * @param {number} params.page - 页码（默认 1）
 * @param {number} params.size - 每页大小（默认 10，修复 D3）
 * @returns {Promise<PageResult<KnowledgePointListVO>>}
 */
export function getKnowledgePointList(params) {
  return request.get('/admin/knowledge-points', { params });
}

/**
 * 获取知识点详情
 * @param {string} id - 知识点 ID
 * @returns {Promise<KnowledgePointDetailVO>}
 */
export function getKnowledgePointDetail(id) {
  return request.get(`/admin/knowledge-points/${id}`);
}

/**
 * 标记知识点已校对（pending_review → active）
 * @param {string} id - 知识点 ID
 * @returns {Promise<void>}
 */
export function reviewKnowledgePoint(id) {
  return request.put(`/admin/knowledge-points/${id}/review`);
}

/**
 * 启用/停用知识点（active ↔ disabled）
 * @param {string} id - 知识点 ID
 * @param {string} status - 目标状态
 * @returns {Promise<void>}
 */
export function updateKnowledgePointStatus(id, status) {
  return request.put(`/admin/knowledge-points/${id}/status`, null, { params: { status } });
}

/**
 * 批量校对标记
 * @param {string[]} ids - 知识点 ID 列表，传空数组则校验所有待校对知识点
 * @returns {Promise<number>} 成功标记的数量
 */
export function batchReviewKnowledgePoints(ids) {
  return request.put('/admin/knowledge-points/batch-review', ids || []);
}
