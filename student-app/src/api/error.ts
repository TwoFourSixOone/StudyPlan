/**
 * 错题管理 API
 */
import { get } from '@/utils/request';

/**
 * 查询错题列表（分页）
 * @param params 查询参数 { knowledgePointId, status, page, size }
 * @returns 分页结果
 */
export function getErrorQuestions(params: Record<string, any> = {}): Promise<any> {
  return get('/error-questions', params);
}

/**
 * 查询错题统计
 * @returns 统计数据
 */
export function getErrorStatistics(): Promise<any> {
  return get('/error-questions/statistics');
}
