/**
 * 带 JWT 认证的文件上传封装
 *
 * uni.uploadFile 不走 request.ts，需手动注入 Authorization header。
 * 含 401 自动重登 + 单次重试逻辑。
 */
import { useAuthStore } from '@/stores/auth';
import { showError } from '@/utils/message';

// 环境判断：默认云托管，VITE_USE_LOCAL=1 时切换本地
const USE_LOCAL = import.meta.env.VITE_USE_LOCAL === '1';
const BASE_URL = USE_LOCAL ? 'http://localhost:80/api' : 'https://springboot-0kxz-337659-13-1520028634.sh.run.tcloudbase.com/api';

/**
 * 带 JWT 认证的文件上传（含 401 自动重登 + 单次重试）
 * @param url 上传路径（如 /student/exam/upload）
 * @param filePath 本地文件路径
 * @param name 文件字段名（默认 'files'）
 * @param formData 额外表单数据
 * @returns 响应 data
 */
export function uploadWithAuth(
  url: string,
  filePath: string,
  name: string = 'files',
  formData: Record<string, any> = {}
): Promise<any> {
  return _doUpload(url, filePath, name, formData, false);
}

function _doUpload(
  url: string,
  filePath: string,
  name: string,
  formData: Record<string, any>,
  isRetry: boolean
): Promise<any> {
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
      success: (res: any) => {
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
        let data: any;
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
      fail: (err: any) => {
        showError('上传失败，请检查网络');
        reject(new Error(err.errMsg || '上传失败'));
      }
    });
  });
}
