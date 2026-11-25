/**
 * 带 JWT 认证的文件上传封装
 *
 * uni.uploadFile 不走 request.ts，需手动注入 Authorization header。
 * 含 401 自动重登 + 单次重试逻辑。
 */
import { useAuthStore } from '@/stores/auth';
import { showError } from '@/utils/message';

// 上传地址与 request.ts 使用同一套环境变量（见 student-app/.env.example）
const USE_LOCAL = resolveUseLocal();
const LOCAL_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:80/api';
const CLOUD_BASE_URL = import.meta.env.VITE_CLOUD_BASE_URL || '';

/** 含 /api 前缀的上传基地址；未配置云托管地址时为空字符串 */
const UPLOAD_BASE_URL = USE_LOCAL
  ? LOCAL_BASE_URL
  : (CLOUD_BASE_URL ? CLOUD_BASE_URL + '/api' : '');

function resolveUseLocal(): boolean {
  const flag = import.meta.env.VITE_USE_LOCAL;
  if (flag === 'true') return true;
  if (flag === 'false') return false;
  return import.meta.env.DEV;
}

/**
 * 带 JWT 认证的文件上传（含 401 自动重登 + 单次重试）
 * @param url 上传路径（如 /student/exam/papers/{paperId}/images）
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
    if (!UPLOAD_BASE_URL) {
      showError('上传地址未配置，请检查 .env 中的 VITE_CLOUD_BASE_URL');
      reject(new Error('UPLOAD_BASE_URL_NOT_CONFIGURED'));
      return;
    }
    uni.uploadFile({
      url: UPLOAD_BASE_URL + url,
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
        // 网关层拦截（413/502/504）时后端没有日志，在客户端把状态码暴露出来
        if (res.statusCode === 413) {
          showError('图片超过云端上传上限（约 2MB），请重试或把图片裁剪后再传');
          reject(Object.assign(new Error('HTTP 413 请求体过大'), { statusCode: 413 }));
          return;
        }
        if (res.statusCode === 502 || res.statusCode === 504) {
          showError(`云端网关不可用（HTTP ${res.statusCode}），请稍后重试`);
          reject(Object.assign(new Error(`HTTP ${res.statusCode}`), { statusCode: res.statusCode }));
          return;
        }
        // 解析响应（res.data 为字符串，需 JSON.parse）
        let data: any;
        try {
          data = JSON.parse(res.data);
        } catch (e) {
          showError(`上传失败：响应格式异常（HTTP ${res.statusCode}）`);
          reject(Object.assign(new Error(`上传响应解析失败（HTTP ${res.statusCode}）`), { statusCode: res.statusCode }));
          return;
        }
        if (data.code === 200) {
          resolve(data.data);
        } else {
          showError(data.message || '上传失败');
          reject(Object.assign(new Error(data.message || '上传失败'), { code: data.code }));
        }
      },
      fail: (err: any) => {
        const errMsg = (err && err.errMsg) || '上传失败';
        // 真机最常见：uploadFile 走的是「uploadFile 合法域名」白名单（与 request 域名分开配置）
        if (/url not in domain list|domain list|not in domain/i.test(errMsg)) {
          showError('上传域名未加入小程序后台的「uploadFile 合法域名」，请配置后重试');
        } else if (/timeout/i.test(errMsg)) {
          showError('上传超时，请检查网络后重试');
        } else if (/exceed|too large|size/i.test(errMsg)) {
          showError('图片过大，上传被拒：请重试（会自动压缩）或裁剪后再传');
        } else {
          showError(`上传失败：${errMsg}`);
        }
        // 保留 errMsg 供页面展示与排障
        reject(Object.assign(new Error(errMsg), { errMsg }));
      }
    });
  });
}
