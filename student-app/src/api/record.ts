/**
 * 学习记录 API
 *
 * 后端契约：
 * - GET  /api/student/records                    记录列表（分页 + 筛选）
 * - GET  /api/student/records/{recordType}/{id}  记录详情
 */
import { get } from '@/utils/request';

/**
 * 获取学习记录列表
 * @param params 筛选参数 { startDate, endDate, recordType, page, size }
 * @returns 分页结果
 */
export function getRecordList(params: Record<string, any> = {}): Promise<any> {
  return get('/student/records', params);
}

/**
 * 获取学习记录详情
 * @param recordType 记录类型枚举 code
 * @param id 记录 ID
 * @returns 记录详情
 */
export function getRecordDetail(recordType: string, id: number): Promise<any> {
  return get(`/student/records/${recordType}/${id}`);
}
