/**
 * 登录相关 API 接口
 * 
 * 后端契约：
 * - POST /api/auth/login  { code: string } → Result<{ token, studentInfo }>
 * - GET  /api/auth/current → Result<StudentVO>
 */

import { get, post } from '@/utils/request';

/**
 * 微信登录（code 换 JWT）
 * @param code wx.login() 获取的一次性 code
 * @returns { token, studentInfo }
 */
export function login(code: string): Promise<{ token: string; studentInfo: any }> {
  return post('/auth/login', { code });
}

/**
 * 获取当前登录用户信息
 * @returns StudentVO
 */
export function getCurrentUser(): Promise<any> {
  return get('/auth/current');
}
