/**
 * 统一登录态守卫 composable
 *
 * 在每个 TabBar 页面的 setup 中调用一行即可：
 *   useAuthGuard()
 *
 * 内部注册 onShow 钩子，每次页面显示时检查 authStore.isLoggedIn，
 * 未登录则 reLaunch 到登录页。
 */
import { onShow } from '@dcloudio/uni-app';
import { useAuthStore } from '@/stores/auth';

export function useAuthGuard() {
  onShow(() => {
    const authStore = useAuthStore();
    if (!authStore.isLoggedIn) {
      uni.reLaunch({ url: '/pages/login/Login' });
    }
  });
}
