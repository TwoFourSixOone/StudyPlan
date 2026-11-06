/**
 * 带 JWT 认证的文件上传封装
 *
 * uni.uploadFile 不走 request.js，需手动注入 Authorization header。
 * 含 401 自动重登 + 单次重试逻辑。
 */
import { useAuthStore } from '@/stores/auth';
import { showError } from '@/utils/message.js';

const BASE_URL = 'http://localhost:80/api';

/**
 * 带 JWT 认证的文件上传（含 401 自动重登 + 单次重试）
 * @param {string} url - 上传路径（如 /student/exam/upload）
 * @param {string} filePath - 本地文件路径
 * @param {string} name - 文件字段名（默认 'files'）
 * @param {Object} formData - 额外表单数据
 * @returns {Promise<Object>} 响应 data
 */
export function uploadWithAuth(url, filePath, name = 'files', formData = {}) {
  return _doUpload(url, filePath, name, formData, false);
}

function _doUpload(url, filePath, name, formData, isRetry) {
  return new Promise((resolve, reject) => {
    const authStore = useAuthStore();
    uni.uploadFile({
      url: BASE_URL + url,
      filePath,
      name,
      formData,
      header: {
        'Authorization': `Bearer ${authStore.token}`
      },
      success: (res) => {
        // 401 未授权：尝试静默重登后重试一次
        if (res.statusCode === 401 && !isRetry) {
          authStore.silentReLogin()
            .then(() => _doUpload(url, filePath, name, formData, true))
            .then(resolve)
            .catch(() => {
              uni.reLaunch({ url: '/pages/login/Login' });
              reject(new Error('登录已过期，请重新登录'));
            });
          return;
        }
        // 解析响应（res.data 为字符串，需 JSON.parse）
        let data;
        try {
          data = JSON.parse(res.data);
        } catch (e) {
          showError('上传失败：响应格式异常');
          reject(new Error('上传响应解析失败'));
          return;
        }
        if (data.code === 200) {
          resolve(data.data);
        } else {
          showError(data.message || '上传失败');
          reject(new Error(data.message || '上传失败'));
        }
      },
      fail: (err) => {
        showError('上传失败，请检查网络');
        reject(new Error(err.errMsg || '上传失败'));
      }
    });
  });
}
