/**
 * 考试试卷 API
 *
 * 后端契约：
 * - POST  /api/student/exam/upload       上传试卷图片（OCR 识别）
 * - GET   /api/student/exam/analysis/{id} 试卷分析结果
 */
import { get } from '@/utils/request';
import { uploadWithAuth } from '@/utils/upload';

/** 试卷上传路径 */
export const EXAM_UPLOAD_PATH = '/student/exam/upload';

/**
 * 上传试卷图片
 * @param filePath 本地文件路径
 * @returns 上传结果
 */
export function uploadExamImage(filePath: string): Promise<any> {
  return uploadWithAuth('/student/exam/upload', filePath);
}

/**
 * 获取试卷分析结果
 * @param id 试卷 ID
 * @returns 试卷分析结果
 */
export function getExamAnalysis(id: number): Promise<any> {
  return get(`/student/exam/analysis/${id}`);
}
