<script setup>
import { onLaunch } from '@dcloudio/uni-app';
import { useAuthStore } from '@/stores/auth';
import { useAppStore } from '@/stores/app';

onLaunch(() => {
  const authStore = useAuthStore();
  const appStore = useAppStore();

  // 从 Storage 恢复登录态（同步读取 + 同步解码 exp，R-6）
  authStore.init();

  // 检查登录态：未登录则跳转登录页
  if (!authStore.isLoggedIn) {
    // 避免已在登录页时重复 reLaunch 导致 timeout（getCurrentPages 在 onLaunch 中可能为空）
    const pages = getCurrentPages();
    const currentPage = pages.length > 0 ? pages[pages.length - 1]?.route : '';
    if (currentPage !== 'pages/login/Login') {
      uni.reLaunch({ url: '/pages/login/Login' });
    }
    return; // 未登录，不加载科目列表
  }

  // 已登录：加载科目列表
  appStore.init();
});
</script>

<style>
/* 全局基础样式 */
page {
  background-color: #f5f5f5;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
    'Helvetica Neue', Arial, sans-serif;
  font-size: 28rpx;
  color: #333;
  box-sizing: border-box;
}

/* 安全区适配（I-6） */
.safe-area-bottom {
  padding-bottom: env(safe-area-inset-bottom);
}
</style>
