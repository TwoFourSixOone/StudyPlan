import request from './request';

/**
 * 管理员登录（用户名 + 密码）
 * @param {Object} credentials - 登录凭证
 * @param {string} credentials.username - 用户名
 * @param {string} credentials.password - 密码
 * @returns {Promise<AdminLoginResponse>}
 */
export function adminLogin(credentials) {
  return request.post('/admin/auth/login', credentials);
}

/**
 * 获取当前管理员信息
 * @returns {Promise<AdminInfo>}
 */
export function getAdminInfo() {
  return request.get('/admin/auth/current');
}

/**
 * 修改当前管理员密码（Change: 2026-11-19_admin_password_change）
 * @param {Object} payload - 改密参数
 * @param {string} payload.oldPassword - 原密码
 * @param {string} payload.newPassword - 新密码（6~64 位）
 * @returns {Promise<void>}
 */
export function changePassword(payload) {
  return request.post('/admin/auth/change-password', payload);
}
