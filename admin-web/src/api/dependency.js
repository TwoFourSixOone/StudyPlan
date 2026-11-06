import request from './request';

/**
 * 查询先修关系列表
 * @param {Object} params - 查询参数
 * @param {string} params.knowledgePointId - 知识点 ID（修复 D5）
 * @param {string} params.prerequisiteId - 先修知识点 ID（修复 D5）
 * @param {string} params.dependencyType - 关系类型：hard/soft（修复 D6）
 * @param {number} params.page - 页码（默认 1）
 * @param {number} params.size - 每页大小（默认 20）
 * @returns {Promise<PageResult<DependencyListVO>>}
 */
export function getDependencyList(params) {
  return request.get('/admin/dependencies', { params });
}
