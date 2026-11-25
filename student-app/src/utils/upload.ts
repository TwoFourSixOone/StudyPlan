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
// 云托管环境 ID / 服务名（callContainer 通道，与 request.ts 同源）
const CLOUD_ENV = import.meta.env.VITE_CLOUD_ENV || '';
const CLOUD_SERVICE = import.meta.env.VITE_CLOUD_SERVICE || '';
const CLOUD_CONTAINER_ENABLED = Boolean(CLOUD_ENV && CLOUD_SERVICE);

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

// ========== 云开发存储直传（Change: 2026-11-23_upload_via_cloud_storage）==========
/**
 * 是否可用「云存储直传」上传（云端模式 + 已配置云开发环境）
 *
 * <p>背景：云托管默认域名不能用于正式环境的「uploadFile 合法域名」，而 callContainer 请求体上限只有 100KB
 * （错误码 -606001），无法直接传图片。因此改为：
 * ① {@code wx.cloud.uploadFile} 把图片传到云开发存储（不受 100KB 限制、无需域名白名单）；
 * ② {@code wx.cloud.getTempFileURL} 取临时 HTTPS 地址；
 * ③ 通过 callContainer 调后端 {@code /images/url}，由服务端下载并复用既有校验/落盘流程。</p>
 */
export function isCloudStorageUploadEnabled(): boolean {
  return !USE_LOCAL && Boolean(CLOUD_ENV) && typeof wx !== 'undefined' && !!wx.cloud
    && typeof wx.cloud.uploadFile === 'function';
}

/** 云存储上传 + 换临时 URL（同时返回 fileID，供上传成功后删除原图） */
function uploadToCloudStorage(
  filePath: string,
  pageNo: number
): Promise<{ fileID: string; url: string; contentType: string }> {
  const lower = String(filePath || '').toLowerCase();
  const ext = lower.endsWith('.png') ? 'png' : 'jpg';
  const contentType = ext === 'png' ? 'image/png' : 'image/jpeg';
  const cloudPath = `exam-upload/${Date.now()}-${pageNo}-${Math.random().toString(36).slice(2, 8)}.${ext}`;
  return new Promise((resolve, reject) => {
    wx.cloud.uploadFile({
      cloudPath,
      filePath,
      success: (res: any) => {
        const fileID = res && res.fileID;
        if (!fileID) {
          reject(new Error('云存储上传未返回 fileID'));
          return;
        }
        wx.cloud.getTempFileURL({
          fileList: [fileID],
          success: (tempRes: any) => {
            const first = tempRes && tempRes.fileList && tempRes.fileList[0];
            const url = first && first.tempFileURL;
            if (!url) {
              reject(new Error('获取云存储临时地址失败'));
              return;
            }
            resolve({ fileID, url, contentType });
          },
          fail: (err: any) => reject(new Error(err?.errMsg || '获取云存储临时地址失败'))
        });
      },
      fail: (err: any) => reject(Object.assign(new Error(err?.errMsg || '云存储上传失败'), { errMsg: err?.errMsg }))
    });
  });
}

/**
 * 云存储直传主流程：上传云存储 → 取临时 URL → 调后端落库
 *
 * @param url 后端接口路径（不含 /api，例如 /student/exam/papers/4/images/url）
 * @param filePath 本地图片路径
 * @param pageNo 卷面页序
 */
export async function uploadExamImageViaCloudStorage(
  url: string,
  filePath: string,
  pageNo: number
): Promise<any> {
  const { fileID, url: tempUrl, contentType } = await uploadToCloudStorage(filePath, pageNo);
  const authStore = useAuthStore();
  return new Promise((resolve, reject) => {
    wx.cloud.callContainer({
      config: { env: CLOUD_ENV },
      path: '/api' + url,
      method: 'POST',
      header: {
        'Content-Type': 'application/json',
        'X-WX-SERVICE': CLOUD_SERVICE,
        'Authorization': `Bearer ${authStore.token}`
      },
      // 只传 URL（几十字节），远低于 callContainer 100KB 上限
      data: JSON.stringify({ url: tempUrl, contentType, pageNo }),
      timeout: 60000,
      success: (res: any) => {
        let data: any;
        try {
          data = typeof res.data === 'string' ? JSON.parse(res.data) : res.data;
        } catch (e) {
          showError(`上传失败：响应格式异常（HTTP ${res.statusCode}）`);
          reject(Object.assign(new Error('上传响应解析失败'), { statusCode: res.statusCode }));
          return;
        }
        if (data && data.code === 200) {
          // 后端已下载并落库成功 → 删除云存储原图，避免对象存储堆积（失败只记日志，不影响本次上传）
          deleteCloudFileQuietly(fileID);
          resolve(data.data);
        } else {
          showError(data?.message || '上传失败');
          reject(Object.assign(new Error(data?.message || '上传失败'), { code: data?.code }));
        }
      },
      fail: (err: any) => reject(Object.assign(new Error(err?.errMsg || '上传失败'), { errMsg: err?.errMsg }))
    });
  });
}
/**
 * 删除云存储临时原图（后端落库成功后调用）
 *
 * <p>失败只打日志：残留文件不影响业务，可通过对象存储生命周期规则兜底清理。
 * 注意：必须等后端**下载成功**后再删，否则重试会因源文件已删而失败。</p>
 */
function deleteCloudFileQuietly(fileID: string): void {
  if (!fileID || typeof wx === 'undefined' || !wx.cloud || typeof wx.cloud.deleteFile !== 'function') {
    return;
  }
  wx.cloud.deleteFile({
    fileList: [fileID],
    success: () => console.log('[upload] 云存储原图已删除', fileID),
    fail: (err: any) => console.warn('[upload] 云存储原图删除失败（可忽略，注意清理）', { fileID, errMsg: err?.errMsg })
  });
}