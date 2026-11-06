/**
 * 试卷 API（本 change 仅上传）
 *
 * 后端契约：
 * - POST  /api/student/exam/upload   上传试卷（multipart/form-data）
 *
 * 注意：上传走 uni.uploadFile，不走 request.js。
 * 本文件仅做路径常量导出，实际上传使用 utils/upload.js 的 uploadWithAuth。
 */

/** 试卷上传路径 */
export const EXAM_UPLOAD_PATH = '/student/exam/upload';
