/**
 * 每日作业 API
 *
 * 后端契约：
 * - POST  /api/homework/generate  生成每日作业（subjectId + planDate）
 * - GET   /api/homework/by-date   查询指定日期作业（subjectId + planDate）
 * - POST  /api/homework/submit    提交单题答案
 * - POST  /api/homework/grade     批改作业（subjectId + planDate）
 */
import { get, post } from '@/utils/request.js';

/**
 * 生成每日作业
 * @param {number} subjectId
 * @param {string} planDate - YYYY-MM-DD
 * @returns {Promise<void>}
 */
export function generateHomework(subjectId, planDate) {
  return post(`/homework/generate?subjectId=${subjectId}&planDate=${planDate}`);
}

/**
 * 查询指定日期作业列表
 * @param {number} subjectId
 * @param {string} planDate - YYYY-MM-DD
 * @returns {Promise<Array<HomeworkVO>>}
 */
export function getHomeworkByDate(subjectId, planDate) {
  return get('/homework/by-date', { subjectId, planDate });
}

/**
 * 提交单题答案
 * @param {Object} data - { questionId, studentAnswer, ... }
 * @returns {Promise<void>}
 */
export function submitAnswer(data) {
  return post('/homework/submit', data);
}

/**
 * 批改作业
 * @param {number} subjectId
 * @param {string} planDate - YYYY-MM-DD
 * @returns {Promise<GradeResultVO>}
 */
export function gradeHomework(subjectId, planDate) {
  return post(`/homework/grade?subjectId=${subjectId}&planDate=${planDate}`);
}
