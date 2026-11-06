import request from './request';

/**
 * 查询课程标准列表
 * @param {Object} params - 查询参数
 * @param {string} params.keyword - 关键词
 * @param {string} params.stage - 学段
 * @param {string} params.subject - 学科
 * @param {number} params.page - 页码（默认 1）
 * @param {number} params.size - 每页大小（默认 20）
 * @returns {Promise<PageResult<StandardListVO>>}
 */
export function getStandardList(params) {
  return request.get('/admin/standards', { params });
}
