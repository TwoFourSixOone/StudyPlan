import { defineStore } from 'pinia';
import { ref, computed } from 'vue';

/**
 * 登录态 Store
 * 
 * 管理 JWT token、用户信息、过期时间的存储与读取。
 * Storage 读写封装在 store 内部，页面不直接操作 Storage。
 * 
 * R-1 决策：锁归属在 request.js 层，store 的 _doLogin 本身不加锁。
 * R-2 决策：_saveToken 作为唯一入口，通道 A 和通道 B 共用，同步刷新 expireTime。
 */
export const useAuthStore = defineStore('auth', () => {
  // ========== State ==========
  const token = ref(null);
  const studentInfo = ref(null);
  const expireTime = ref(null);

  // ========== Computed ==========
  const isLoggedIn = computed(() => {
    if (!token.value || !expireTime.value) return false;
    return expireTime.value > Date.now();
  });

  // ========== Actions ==========

  /**
   * 从 Storage 恢复登录态（同步读取）
   */
  function init() {
    token.value = uni.getStorageSync('auth_token') || null;
    const userStr = uni.getStorageSync('auth_user');
    studentInfo.value = userStr ? JSON.parse(userStr) : null;
    expireTime.value = uni.getStorageSync('auth_expire') || null;
  }

  /**
   * 保存 token（唯一入口）
   * 同步解码 JWT payload.exp，更新 expireTime（R-2）
   */
  function _saveToken(newToken) {
    token.value = newToken;
    uni.setStorageSync('auth_token', newToken);

    // 轻量 base64 解码 JWT payload 获取 exp（I-4）
    const exp = _decodeExp(newToken);
    if (exp) {
      expireTime.value = exp;
      uni.setStorageSync('auth_expire', exp);
    }
  }

  /**
   * 保存用户信息
   */
  function _saveStudentInfo(info) {
    studentInfo.value = info;
    uni.setStorageSync('auth_user', JSON.stringify(info));
  }

  /**
   * 公共登录核心（R-1：_doLogin 本身不加锁，锁在 request.js 401 路径控制）
   * @param {Object} options
   * @param {boolean} options.silent - true=静默重登（不跳转），false=用户主动登录（跳转首页）
   */
  async function _doLogin({ silent = false } = {}) {
    const DEV_MODE = false;
    let code;
    if (DEV_MODE) {
      code = 'dev_test_code_001';
    } else {
      const loginRes = await new Promise((resolve, reject) => {
        uni.login({
          provider: 'weixin',
          success: (res) => resolve(res),
          fail: (err) => reject(err)
        });
      }).catch(err => {
        throw new Error('微信登录失败：' + (err.errMsg || JSON.stringify(err)));
      });

      if (!loginRes?.code) {
        throw new Error('微信登录失败：未获取到 code, res=' + JSON.stringify(loginRes));
      }
      code = loginRes.code;
    }

    const response = await new Promise((resolve, reject) => {
      uni.request({
        url: 'http://localhost:80/api/auth/login',
        method: 'POST',
        header: { 'Content-Type': 'application/json' },
        data: { code },
        success: (res) => resolve(res.data),
        fail: (err) => {
          reject(new Error('网络请求失败：' + (err.errMsg || JSON.stringify(err))));
        }
      });
    });

    if (response?.code === 200 && response?.data?.token) {
      _saveToken(response.data.token);
      if (response.data.studentInfo) {
        _saveStudentInfo(response.data.studentInfo);
      }

      if (!silent) {
        uni.switchTab({ url: '/pages/index/Index' });
      }
    } else {
      throw new Error(response?.message || '登录失败');
    }
  }

  /**
   * 用户主动登录
   */
  async function login() {
    await _doLogin({ silent: false });
  }

  /**
   * 401 静默重登（通道 B，供 request.js 调用）
   * 注意：不带锁，调用方（request.js）需自行保证并发安全（R-1）
   */
  async function silentReLogin() {
    await _doLogin({ silent: true });
  }

  /**
   * 登出 — 原子清除三个 Storage key（I-3）
   */
  function logout() {
    token.value = null;
    studentInfo.value = null;
    expireTime.value = null;
    uni.removeStorageSync('auth_token');
    uni.removeStorageSync('auth_user');
    uni.removeStorageSync('auth_expire');
    uni.reLaunch({ url: '/pages/login/Login' });
  }

  /**
   * 轻量 base64 解码 JWT payload.exp（I-4，不引入 jwt-decode）
   * 兼容微信小程序环境（部分基础库版本无 atob）
   * @param {string} jwtToken 
   * @returns {number|null} 过期时间戳（ms），解码失败返回 null
   */
  function _decodeExp(jwtToken) {
    try {
      const parts = jwtToken.split('.');
      if (parts.length !== 3) return null;
      // JWT payload 是 base64url 编码，需补全 padding 并替换字符
      let payload = parts[1]
        .replace(/-/g, '+')
        .replace(/_/g, '/');
      const pad = payload.length % 4;
      if (pad) payload += '='.repeat(4 - pad);
      // 兼容：优先全局 atob，否则用纯 JS 实现（微信小程序部分基础库无 atob）
      let decoded;
      if (typeof atob === 'function') {
        decoded = atob(payload);
      } else {
        decoded = _base64Decode(payload);
      }
      const claims = JSON.parse(decoded);
      return claims.exp ? claims.exp * 1000 : null; // exp 是秒，转为毫秒
    } catch {
      return null;
    }
  }

  /**
   * 纯 JS base64 解码（atob 不可用时的兆底）
   */
  function _base64Decode(str) {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    let result = '';
    let buffer = 0;
    let bits = 0;
    for (let i = 0; i < str.length; i++) {
      const val = chars.indexOf(str[i]);
      if (val === -1) continue;
      buffer = (buffer << 6) | val;
      bits += 6;
      if (bits >= 8) {
        bits -= 8;
        result += String.fromCharCode((buffer >> bits) & 0xFF);
      }
    }
    return result;
  }

  return {
    // state
    token,
    studentInfo,
    expireTime,
    // computed
    isLoggedIn,
    // actions
    init,
    login,
    silentReLogin,
    logout,
    _doLogin,
    _saveToken
  };
});
