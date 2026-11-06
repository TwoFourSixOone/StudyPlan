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
