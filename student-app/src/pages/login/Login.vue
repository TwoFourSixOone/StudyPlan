<template>
  <view class="login-page">
    <view class="login-header">
      <text class="app-name">智学伴</text>
      <text class="app-slogan">AI 自适应学习助手</text>
    </view>

    <view class="login-body">
      <button class="login-btn" :loading="loading" @click="handleLogin">
        微信一键登录
      </button>
      <text v-if="errorMsg" class="error-msg">{{ errorMsg }}</text>
    </view>

    <view class="login-footer">
      <text class="footer-text">登录即同意《用户协议》和《隐私政策》</text>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue';
import { useAuthStore } from '@/stores/auth';

const authStore = useAuthStore();
const loading = ref(false);
const errorMsg = ref('');

async function handleLogin() {
  loading.value = true;
  errorMsg.value = '';
  try {
    await authStore.login();
  } catch (err) {
    errorMsg.value = err.message || '登录失败，请稍后重试';
  } finally {
    loading.value = false;
  }
}
</script>

<style lang="scss" scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: $bg-white;
  padding-bottom: env(safe-area-inset-bottom);
}

.login-header {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding-top: 200rpx;
}

.app-name {
  font-size: 72rpx;
  font-weight: bold;
  color: $primary-color;
  margin-bottom: $spacing-md;
}

.app-slogan {
  font-size: $font-lg;
  color: $text-secondary;
}

.login-body {
  padding: 0 $spacing-xl;
  margin-bottom: $spacing-xl;
}

.login-btn {
  width: 100%;
  height: 88rpx;
  line-height: 88rpx;
  background-color: #07c160;
  color: #fff;
  font-size: $font-lg;
  border-radius: $radius-lg;
  border: none;

  &::after {
    border: none;
  }
}

.error-msg {
  display: block;
  text-align: center;
  margin-top: $spacing-md;
  font-size: $font-sm;
  color: $danger-color;
}

.login-footer {
  padding: $spacing-lg $spacing-xl;
  text-align: center;
}

.footer-text {
  font-size: $font-xs;
  color: $text-placeholder;
}
</style>
