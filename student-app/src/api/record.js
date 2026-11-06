/**
 * 学习记录 API
 *
 * 后端契约：
 * - GET  /api/student/records                    记录列表（分页 + 筛选）
 * - GET  /api/student/records/{recordType}/{id}  记录详情
 */
import { get } from '@/utils/request.js';

/**
 * 获取学习记录列表
 * @param {Object} [params] - { startDate, endDate, recordType, page, size }
 * @returns {Promise<PageResult<LearningRecordListVO>>}
 */
export function getRecordList(params = {}) {
  return get('/student/records', params);
}

/**
 * 获取学习记录详情
 * @param {string} recordType - 记录类型枚举 code
 * @param {number} id - 记录 ID
 * @returns {Promise<LearningRecordDetailVO>}
 */
export function getRecordDetail(recordType, id) {
  return get(`/student/records/${recordType}/${id}`);
}
