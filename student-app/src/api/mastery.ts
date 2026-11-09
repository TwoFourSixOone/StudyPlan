/**
 * 掌握度 API
 *
 * 后端契约：
 * - GET  /api/student/mastery/dashboard          掌握度看板（全量列表）
 * - GET  /api/student/mastery/history/{kpId}     知识点变更历史（分页）
 */
import { get } from '@/utils/request';

/**
 * 获取掌握度看板
 * @param subject 科目名称过滤
 * @returns 掌握度看板数据
 */
export function getDashboard(subject?: string): Promise<any[]> {
  const data = subject ? { subject } : {};
  return get('/student/mastery/dashboard', data);
}

/**
 * 获取知识点变更历史
 * @param knowledgePointId 知识点 ID
 * @param page 页码
 * @param size 每页数量
 * @returns 分页结果
 */
export function getChangeHistory(knowledgePointId: string, page: number = 1, size: number = 10): Promise<any> {
  return get(`/student/mastery/history/${knowledgePointId}`, { page, size });
}
