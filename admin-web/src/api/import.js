import request from './request';

/**
 * 执行数据导入
 * @param {File} file - JSON 文件对象
 * @param {string} dataType - 数据类型（question/topic/dependency/standard）
 * @returns {Promise<ImportResultDTO>}
 */
export function importData(file, dataType) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('dataType', dataType);
  return request.post('/admin/import', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    timeout: 120000 // 导入接口超时时间 2 分钟（数据量大时可能需要较长时间）
  });
}

/**
 * 查询导入日志
 * @param {Object} params - 查询参数
 * @param {string} params.dataType - 数据类型（可选）
 * @param {number} params.page - 页码（默认 1）
 * @param {number} params.size - 每页大小（默认 20）
 * @returns {Promise<PageResult<ImportLogVO>>}
 */
export function getImportLogs(params) {
  return request.get('/admin/import/logs', { params });
}

/**
 * 查询导入错误记录
 * @param {Object} params - 查询参数
 * @param {number} params.importId - 导入批次 ID（必填）
 * @param {number} params.page - 页码（默认 1）
 * @param {number} params.size - 每页大小（默认 20）
 * @returns {Promise<PageResult<DataImportErrorLog>>}
 */
export function getImportErrors(params) {
  return request.get('/admin/import/errors', { params });
}
