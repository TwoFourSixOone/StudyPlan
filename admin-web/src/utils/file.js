/**
 * 校验文件格式
 * @param {File} file - 文件对象
 * @param {string} accept - 接受的文件类型，如 '.json,.txt'
 * @returns {boolean} 是否符合要求
 */
export function validateFileType(file, accept) {
  const fileExt = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();
  const acceptExts = accept.split(',').map((ext) => ext.trim().toLowerCase());
  return acceptExts.includes(fileExt);
}

/**
 * 校验文件大小
 * @param {File} file - 文件对象
 * @param {number} maxSize - 最大文件大小（字节）
 * @returns {boolean} 是否符合要求
 */
export function validateFileSize(file, maxSize) {
  return file.size <= maxSize;
}

/**
 * 获取文件格式提示文本
 * @param {string} accept - 接受的文件类型
 * @param {number} maxSize - 最大文件大小（字节）
 * @returns {string} 提示文本
 */
export function getFileTypeTip(accept, maxSize) {
  const maxSizeMB = (maxSize / 1024 / 1024).toFixed(0);
  return `仅支持 ${accept} 格式文件，且大小不超过 ${maxSizeMB}MB`;
}

/**
 * 读取文件内容为文本
 * @param {File} file - 文件对象
 * @returns {Promise<string>} 文件内容
 */
export function readFileAsText(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = (e) => resolve(e.target.result);
    reader.onerror = (e) => reject(e);
    reader.readAsText(file);
  });
}

/**
 * 读取文件内容为 JSON
 * @param {File} file - 文件对象
 * @returns {Promise<Object>} JSON 对象
 */
export async function readFileAsJSON(file) {
  const text = await readFileAsText(file);
  return JSON.parse(text);
}
