/**
 * 登录相关 API 接口
 * 
 * 后端契约：
 * - POST /api/auth/login  { code: string } → Result<{ token, studentInfo }>
 * - GET  /api/auth/current → Result<StudentVO>
 * - PUT  /api/student/profile/grade → Result<Void>（产品规则 #11）
 *
 * 注意：新增的接口函数请并入已有模块，不要新建 `api/xxx.ts`。
 * uni-app 编译 mp-weixin 时，运行期新增的 api 文件不会被注册，会报
 * `module 'api/xxx.js' is not defined`（隐性约定 #18）。
 */

import { get, post, put } from '@/utils/request';

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

/**
 * 设置/修改年级（产品规则 #11）
 * @param grade 年级 1-12
 */
export function updateGrade(grade: number): Promise<void> {
  return put('/student/profile/grade', { grade });
}
