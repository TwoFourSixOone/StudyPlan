/**
 * 微信小程序全局类型声明
 */

// Vite 环境变量类型
interface ImportMetaEnv {
  readonly DEV: boolean;
  readonly PROD: boolean;
  /** 是否强制使用本地后端：'true' / 'false'，不配置则按 DEV 推断 */
  readonly VITE_USE_LOCAL?: string;
  /** 本地后端地址（含 /api 前缀） */
  readonly VITE_API_BASE_URL?: string;
  /** 微信云托管环境 ID */
  readonly VITE_CLOUD_ENV?: string;
  /** 微信云托管服务名（X-WX-SERVICE） */
  readonly VITE_CLOUD_SERVICE?: string;
  /** 云托管公网地址（不含 /api 前缀） */
  readonly VITE_CLOUD_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

// uni-app 全局对象
declare const uni: any;

// 微信云开发对象
declare namespace wx {
  const cloud: {
    init(config: { env: string; traceUser?: boolean }): void;
    callContainer(options: {
      config: { env: string };
      path: string;
      header: Record<string, string>;
      method: 'GET' | 'POST' | 'PUT' | 'DELETE';
      data?: any;
      success: (res: any) => void;
      fail: (err: any) => void;
    }): void;
  };
}

// 通用类型定义
interface PageResult<T> {
  total: number;
  page: number;
  size: number;
  records: T[];
}

interface Result<T> {
  code: number;
  message: string;
  data: T;
}
