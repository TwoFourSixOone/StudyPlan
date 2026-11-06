import { createRouter, createWebHistory } from 'vue-router';
import AppLayout from '@/components/AppLayout.vue';

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: AppLayout,
    redirect: '/questions',
    children: [
      {
        path: '/questions',
        name: 'QuestionList',
        component: () => import('@/views/question/QuestionList.vue'),
        meta: { title: '题库管理' }
      },
      {
        path: '/knowledge-points',
        name: 'KnowledgePointList',
        component: () => import('@/views/knowledge-point/KnowledgePointList.vue'),
        meta: { title: '知识点管理' }
      },
      {
        path: '/knowledge-points/:id',
        name: 'KnowledgePointDetail',
        component: () => import('@/views/knowledge-point/KnowledgePointDetail.vue'),
        meta: { title: '知识点详情' }
      },
      {
        path: '/dependencies',
        name: 'DependencyList',
        component: () => import('@/views/dependency/DependencyList.vue'),
        meta: { title: '先修关系管理' }
      },
      {
        path: '/standards',
        name: 'StandardList',
        component: () => import('@/views/standard/StandardList.vue'),
        meta: { title: '课程标准管理' }
      },
      {
        path: '/import-logs',
        name: 'ImportLogList',
        component: () => import('@/views/import-log/ImportLogList.vue'),
        meta: { title: '导入日志' }
      },
      {
        path: '/import-errors',
        name: 'ImportErrorList',
        component: () => import('@/views/import-log/ImportErrorList.vue'),
        meta: { title: '导入错误记录' }
      }
    ]
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

// 路由守卫：检查登录状态
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('VITE_DEMO_TOKEN');
  
  if (to.meta.public) {
    // 公开页面（登录页）直接放行
    next();
  } else if (!token) {
    // 未登录，跳转到登录页
    next('/login');
  } else {
    // 已登录，正常访问
    next();
  }
});

export default router;
