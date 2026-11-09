/**
 * 每日作业 API
 *
 * 后端契约：
 * - POST  /api/homework/generate  生成每日作业（subjectId + planDate）
 * - GET   /api/homework/by-date   查询指定日期作业（subjectId + planDate）
 * - POST  /api/homework/submit    提交单题答案
 * - POST  /api/homework/grade     批改作业（subjectId + planDate）
 */
import { get, post } from '@/utils/request';

/**
 * 生成每日作业
 * @param subjectId 科目 ID
 * @param planDate 计划日期 - YYYY-MM-DD
 * @returns void
 */
export function generateHomework(subjectId: number, planDate: string): Promise<void> {
  return post(`/homework/generate?subjectId=${subjectId}&planDate=${planDate}`);
}

/**
 * 查询指定日期作业列表
 * @param subjectId 科目 ID
 * @param planDate 计划日期 - YYYY-MM-DD
 * @returns 作业列表
 */
export function getHomeworkByDate(subjectId: number, planDate: string): Promise<any[]> {
  return get('/homework/by-date', { subjectId, planDate });
}

/**
 * 提交单题答案
 * @param data 答案数据 { questionId, studentAnswer, ... }
 * @returns void
 */
export function submitAnswer(data: Record<string, any>): Promise<void> {
  return post('/homework/submit', data);
}

/**
 * 批改作业
 * @param subjectId 科目 ID
 * @param planDate 计划日期 - YYYY-MM-DD
 * @returns 批改结果
 */
export function gradeHomework(subjectId: number, planDate: string): Promise<any> {
  return post(`/homework/grade?subjectId=${subjectId}&planDate=${planDate}`);
}
