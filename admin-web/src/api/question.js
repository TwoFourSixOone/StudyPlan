import request from './request';

/**
 * 查询题目列表
 * @param {Object} params - 查询参数
 * @param {string} params.knowledgePointId - 知识点 ID（修复 D1/D2）
 * @param {string} params.questionType - 题型：numeric/choice（修复 D1）
 * @param {string} params.difficulty - 难度：basic/standard/challenge
 * @param {number} params.page - 页码（默认 1）
 * @param {number} params.size - 每页大小（默认 20）
 * @returns {Promise<PageResult<QuestionListVO>>}
 */
export function getQuestionList(params) {
  return request.get('/admin/questions', { params });
}

/**
 * 获取题目详情
 * @param {string} id - 题目 ID
 * @returns {Promise<QuestionDetailVO>}
 */
export function getQuestionDetail(id) {
  return request.get(`/admin/questions/${id}`);
}
