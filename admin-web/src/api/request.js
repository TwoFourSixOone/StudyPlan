import axios from 'axios';
import { showError } from '@/utils/message';

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
});

// 请求拦截器：注入 JWT token
request.interceptors.request.use(
  (config) => {
    // 从 localStorage 获取 token（登录后保存）
    const token = localStorage.getItem('VITE_DEMO_TOKEN');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// 响应拦截器
request.interceptors.response.use(
  (response) => {
    const { code, message, data } = response.data;
    if (code === 200) {
      return data;
    } else {
      showError(message || '请求失败');
      return Promise.reject(new Error(message));
    }
  },
  (error) => {
    if (error.response?.status === 401) {
      showError('未登录或登录已过期，请重新登录');
      // 清除 token 并跳转到登录页
      localStorage.removeItem('VITE_DEMO_TOKEN');
      window.location.href = '/login';
    } else if (error.response?.status === 403) {
      showError('您没有权限访问');
    } else if (error.response?.status === 404) {
      showError('请求的资源不存在');
    } else if (error.response?.status === 500) {
      showError('服务器开小差了，请稍后重试');
    } else {
      showError(error.message || '网络错误');
    }
    return Promise.reject(error);
  }
);

export default request;
