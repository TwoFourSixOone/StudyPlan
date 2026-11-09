/**
 * 统一请求工具（request.ts）
 * 
 * 核心功能：
 * - 封装 uni.request 为 Promise 风格
 * - 请求拦截：惰性获取 Pinia store 注入 JWT（避免循环依赖，I-1）
 * - 响应拦截：先 HTTP 状态码，再 body.code（I-2）
 * - 通道 A：被动捕获 X-Refresh-Token 响应头自动续期
 * - 通道 B：401 反应式重登 + 单次重放（防循环）
 * - 并发重登锁：isReLogging + subscribers（含失败回调，B-2）
 * - 错误提示去重：500ms 节流
 * - 重放仅对幂等请求生效（GET/PUT/DELETE），POST 不自动重放
 * - 云托管支持：开发模式用本地后端，真机/构建模式用 wx.cloud.callContainer
 */

import { showError } from '@/utils/message';
import { useAuthStore } from '@/stores/auth';

// ========== 基础配置 ==========
// 微信小程序环境必须使用完整 URL（Vite proxy 仅对 H5 生效）
// 开发环境指向本地后端，生产环境需替换为正式域名
const BASE_URL = 'http://localhost:80/api';
const TIMEOUT = 30000; // 30s 超时

// ========== 云托管配置 ==========
// 默认使用云托管（真机调试必须），可通过环境变量 VITE_USE_LOCAL=1 切换本地
const USE_LOCAL = import.meta.env.DEV

// 云托管环境 ID 和服务名（根据实际部署配置修改）
const CLOUD_ENV = 'prod-d8g1ado543d76cfe4';
const CLOUD_SERVICE = 'springboot-0kxz';

// 云托管公网地址（callContainer 失败时降级使用）
const CLOUD_BASE_URL = 'https://springboot-0kxz-337659-13-1520028634.sh.run.tcloudbase.com';

// ========== 类型定义 ==========

interface RequestOptions {
  url: string;
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  data?: any;
  header?: Record<string, string>;
  showError?: boolean;
}

interface ReLoginSubscriber {
  resolve: (token: string) => void;
  reject: (error: any) => void;
}

// ========== 并发重登锁（B-2：含失败回调） ==========
let isReLogging = false;
let reLoginSubscribers: ReLoginSubscriber[] = [];

function onReLoginSuccess(token: string): void {
  reLoginSubscribers.forEach(cb => cb.resolve(token));
  reLoginSubscribers = [];
  isReLogging = false;
}

function onReLoginFailed(error: any): void {
  reLoginSubscribers.forEach(cb => cb.reject(error));
  reLoginSubscribers = [];
  isReLogging = false;
}

function addReLoginSubscriber(): Promise<string> {
  return new Promise((resolve, reject) => {
    reLoginSubscribers.push({ resolve, reject });
  });
}

// ========== 获取 Pinia store ==========
// stores/auth.ts 的 _doLogin 使用 uni.request 而非本模块，无循环依赖
// 惰性调用 useAuthStore()（Pinia 必须 app.use(pinia) 后才可用，I-1）
let _authStore: ReturnType<typeof useAuthStore> | null = null;
function getAuthStore(): ReturnType<typeof useAuthStore> {
  if (!_authStore) {
    _authStore = useAuthStore();
  }
  return _authStore;
}

// ========== 通道 B：重登流程 ==========

/**
 * 执行静默重登（通道 B）
 * 加锁保证：多请求同时 401 只触发一次 wx.login()
 * @returns 新 token
 */
async function doReLogin(): Promise<string> {
  if (isReLogging) {
    // 已有重登在进行中，挂起等待
    return addReLoginSubscriber();
  }

  isReLogging = true;

  try {
    const authStore = getAuthStore();
    await authStore.silentReLogin();
    const newToken = authStore.token;
    onReLoginSuccess(newToken!);
    return newToken!;
  } catch (error) {
    onReLoginFailed(error);
    throw error;
  }
}

// ========== 请求方法 ==========

/**
 * 统一请求函数
 * @param options 请求配置
 * @returns 响应数据（Result.data）
 */
export function request<T = any>(options: RequestOptions): Promise<T> {
  const {
    url,
    method = 'GET',
    data = {},
    header = {},
    showError: shouldShowError = true
  } = options;

  return new Promise((resolve, reject) => {
    // 请求拦截：注入 JWT token（惰性获取 store，I-1）
    const authStore = getAuthStore();
    if (authStore.token) {
      header['Authorization'] = `Bearer ${authStore.token}`;
    }
    debugger;
    if (USE_LOCAL) {
      // 本地开发：使用 uni.request
      uni.request({
        url: BASE_URL + url,
        method,
        data,
        header: {
          'Content-Type': 'application/json',
          ...header
        },
        timeout: TIMEOUT,
        success: (res: any) => {
          handleResponse<T>(res, options, resolve, reject);
        },
        fail: (err: any) => {
          if (shouldShowError) {
            showError('网络连接失败，请检查网络');
          }
          reject(new Error(err.errMsg || '网络请求失败'));
        }
      });
    } else {
      // 云托管：优先使用 callContainer，失败时降级到公网 HTTPS
      try {
        wx.cloud.callContainer({
          config: { env: CLOUD_ENV },
          path: '/api' + url,  // 添加 /api 前缀，与后端 @RequestMapping 匹配
          header: {
            'Content-Type': 'application/json',
            ...header,
            'X-WX-SERVICE': CLOUD_SERVICE
          },
          method,
          data,
          success: (res: any) => {
            handleResponse<T>(res, options, resolve, reject);
          },
          fail: () => {
            // callContainer 失败，降级到公网 HTTPS
            fallbackToPublic<T>(url, method, data, header, options, resolve, reject);
          }
        });
      } catch (e) {
        // SDK 层面异常（如 appid 不匹配、wx.cloud 未初始化），降级到公网 HTTPS
        fallbackToPublic<T>(url, method, data, header, options, resolve, reject);
      }
    }
  });
}

/**
 * callContainer 失败时降级到公网 HTTPS
 */
function fallbackToPublic<T>(
  url: string,
  method: string,
  data: any,
  header: Record<string, string>,
  options: RequestOptions,
  resolve: (value: T) => void,
  reject: (reason: any) => void
): void {
  const { showError: shouldShowError = true } = options;
  
  uni.request({
    url: CLOUD_BASE_URL + '/api' + url,  // 添加 /api 前缀
    method: method as any,
    data,
    header: {
      'Content-Type': 'application/json',
      ...header
    },
    timeout: TIMEOUT,
    success: (res: any) => {
      handleResponse<T>(res, options, resolve, reject);
    },
    fail: (err: any) => {
      if (shouldShowError) {
        showError('网络连接失败，请检查网络');
      }
      reject(new Error(err.errMsg || '网络请求失败'));
    }
  });
}

/**
 * 重放请求降级到公网 HTTPS
 */
function fallbackToPublicReplay<T>(
  url: string,
  method: string,
  data: any,
  header: Record<string, string>,
  resolve: (value: T) => void,
  reject: (reason: any) => void,
  shouldShowError: boolean
): void {
  uni.request({
    url: CLOUD_BASE_URL + '/api' + url,  // 添加 /api 前缀
    method: method as any,
    data,
    header: {
      'Content-Type': 'application/json',
      ...header
    },
    timeout: TIMEOUT,
    success: (replayRes: any) => {
      if (replayRes.statusCode === 401) {
        const store = getAuthStore();
        store.logout();
        reject(new Error('REPLAY_UNAUTHORIZED'));
        return;
      }
      handleReplayResponse<T>(replayRes, resolve, reject, shouldShowError);
    },
    fail: (err: any) => {
      if (shouldShowError) {
        showError('网络请求超时，请稍后重试');
      }
      reject(new Error(err.errMsg || '重放请求失败'));
    }
  });
}

/**
 * 响应处理（I-2：先 HTTP 状态码，再 body.code）
 */
async function handleResponse<T>(
  res: any,
  options: RequestOptions,
  resolve: (value: T) => void,
  reject: (reason: any) => void
): Promise<void> {
  const { statusCode, header: resHeader, data: body } = res;
  const { url, method = 'GET', data, showError: shouldShowError = true } = options;

  // ===== 通道 A：被动捕获 X-Refresh-Token（无感知续期） =====
  const refreshToken = resHeader?.['X-Refresh-Token'];
  if (refreshToken) {
    const authStore = getAuthStore();
    authStore._saveToken(refreshToken); // R-2：同步刷新 expireTime
  }

  // ===== 1. HTTP 401 → 触发通道 B 重登 =====
  if (statusCode === 401) {
    // 非幂等请求（POST）不自动重放，提示用户重新操作（R-4）
    if (method.toUpperCase() === 'POST') {
      if (shouldShowError) {
        showError('登录已过期，请重新操作');
      }
      reject(new Error('UNAUTHORIZED'));
      return;
    }

    try {
      // 加锁重登（并发安全，B-2）
      await doReLogin();

      // 重放原请求（单次，防循环）
      const authStore = getAuthStore();
      const replayHeader: Record<string, string> = {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${authStore.token}`
      };

      if (USE_LOCAL) {
        // 本地开发：使用 uni.request
        uni.request({
          url: BASE_URL + url,
          method,
          data,
          header: replayHeader,
          timeout: TIMEOUT,
          success: (replayRes: any) => {
            if (replayRes.statusCode === 401) {
              // 重放再次 401 → 不再重登，跳转登录页（防循环）
              const store = getAuthStore();
              store.logout();
              reject(new Error('REPLAY_UNAUTHORIZED'));
              return;
            }
            // 重放成功：走正常响应解析
            handleReplayResponse<T>(replayRes, resolve, reject, shouldShowError);
          },
          fail: (err: any) => {
            if (shouldShowError) {
              showError('网络请求超时，请稍后重试');
            }
            reject(new Error(err.errMsg || '重放请求失败'));
          }
        });
      } else {
        // 云托管：使用 callContainer
        try {
          wx.cloud.callContainer({
            config: { env: CLOUD_ENV },
            path: '/api' + url,  // 添加 /api 前缀
            header: {
              ...replayHeader,
              'X-WX-SERVICE': CLOUD_SERVICE
            },
            method: method as any,
            data,
            success: (replayRes: any) => {
              if (replayRes.statusCode === 401) {
                const store = getAuthStore();
                store.logout();
                reject(new Error('REPLAY_UNAUTHORIZED'));
                return;
              }
              handleReplayResponse<T>(replayRes, resolve, reject, shouldShowError);
            },
            fail: () => {
              // callContainer 失败，降级到公网 HTTPS
              fallbackToPublicReplay<T>(url, method, data, replayHeader, resolve, reject, shouldShowError);
            }
          });
        } catch (e) {
          fallbackToPublicReplay<T>(url, method, data, replayHeader, resolve, reject, shouldShowError);
        }
      }
    } catch (reLoginError) {
      // 重登失败：跳转登录页
      const authStore = getAuthStore();
      authStore.logout();
      reject(reLoginError);
    }
    return;
  }

  // ===== 2. HTTP 403/404/500/其他错误 =====
  if (statusCode !== 200) {
    if (shouldShowError) {
      const errorMap: Record<number, string> = {
        403: '您没有权限访问',
        404: '请求的资源不存在',
        500: '服务器开小差了，请稍后重试'
      };
      showError(errorMap[statusCode] || `请求失败（${statusCode}）`);
    }
    reject(new Error(`HTTP ${statusCode}`));
    return;
  }

  // ===== 3/4. HTTP 200：解析 body.code =====
  if (body?.code === 200) {
    resolve(body.data);
  } else {
    // 业务错误
    if (shouldShowError) {
      showError(body?.message || '请求失败');
    }
    reject(new Error(body?.message || '业务错误'));
  }
}

/**
 * 重放请求的响应处理（简化版，不再处理 401）
 */
function handleReplayResponse<T>(
  replayRes: any,
  resolve: (value: T) => void,
  reject: (reason: any) => void,
  shouldShowError: boolean
): void {
  const { statusCode, data: body } = replayRes;

  if (statusCode !== 200) {
    if (shouldShowError) {
      showError(`请求失败（${statusCode}）`);
    }
    reject(new Error(`HTTP ${statusCode}`));
    return;
  }

  if (body?.code === 200) {
    resolve(body.data);
  } else {
    if (shouldShowError) {
      showError(body?.message || '请求失败');
    }
    reject(new Error(body?.message || '业务错误'));
  }
}

// ========== 快捷方法 ==========

export function get<T = any>(url: string, data?: any, options: Partial<RequestOptions> = {}): Promise<T> {
  return request<T>({ url, method: 'GET', data, ...options });
}

export function post<T = any>(url: string, data?: any, options: Partial<RequestOptions> = {}): Promise<T> {
  return request<T>({ url, method: 'POST', data, ...options });
}

export function put<T = any>(url: string, data?: any, options: Partial<RequestOptions> = {}): Promise<T> {
  return request<T>({ url, method: 'PUT', data, ...options });
}

export function del<T = any>(url: string, data?: any, options: Partial<RequestOptions> = {}): Promise<T> {
  return request<T>({ url, method: 'DELETE', data, ...options });
}

export default request;
