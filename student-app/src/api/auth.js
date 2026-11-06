/**
 * 登录相关 API 接口
 * 
 * 后端契约：
 * - POST /api/auth/login  { code: string } → Result<{ token, studentInfo }>
 * - GET  /api/auth/current → Result<StudentVO>
 */

import { get, post } from '@/utils/request.js';

/**
 * 微信登录（code 换 JWT）
 * @param {string} code wx.login() 获取的一次性 code
 * @returns {Promise<{token: string, studentInfo: object}>}
 */
export function login(code) {
  return post('/auth/login', { code });
}

/**
 * 获取当前登录用户信息
 * @returns {Promise<object>} StudentVO
 */
export function getCurrentUser() {
  return get('/auth/current');
}
